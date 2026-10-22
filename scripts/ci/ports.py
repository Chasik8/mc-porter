#!/usr/bin/env python3
"""CI helper for ports (used by .github/workflows/*.yml and runnable locally).

  ports.py changed --base <git-ref> [--github-output]   which ports/<mod>/ a change touches + whether the fast regression applies
  ports.py all [--github-output]                        every port of ports/catalog.yaml (nightly reference regression)
  ports.py catalog-check                                catalog <-> ports/ consistency (smokeOnly iff no scenarios, profile exists)
  ports.py info <port-dir>                              JSON: name, licence, open, smokeOnly, scenario count
  ports.py inputs <port-dir> --dest <dir>               download the inputs with a direct url, verify sha256, print name=path lines

Needs PyYAML (preinstalled on GitHub's ubuntu runners; otherwise `pip install pyyaml`).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is missing: pip install pyyaml")

ROOT = Path(__file__).resolve().parents[2]
# Changes here also run the fast regression (rule tests are in check.sh; this adds the TorchMaster port end to end).
REGRESSION_PATHS = ("rules/", "rewrite/", "port/", "testkit/", "scripts/ci/")
REFERENCE_PORT = "ports/torchmaster"
CLOSED_LICENCES = {"ARR", "", "UNKNOWN", "NONE"}
BINARY_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".ogg", ".wav", ".jar", ".zip", ".class", ".dat", ".nbt"}


def load_catalog():
    return yaml.safe_load((ROOT / "ports/catalog.yaml").read_text(encoding="utf-8")) or {}


def catalog_entry(port_dir):
    for e in load_catalog().get("ports", []):
        if e.get("profile", "").rstrip("/") == port_dir.rstrip("/"):
            return e
    return None


def port_config(port_dir):
    files = sorted((ROOT / port_dir).glob("*.port.yaml"))
    if len(files) != 1:
        sys.exit(f"{port_dir} must contain exactly one *.port.yaml (found {len(files)})")
    return files[0], yaml.safe_load(files[0].read_text(encoding="utf-8"))


def scenarios(port_dir):
    return sorted((ROOT / port_dir / "scenarios").rglob("*.scenario.yaml"))


def info(port_dir):
    port_dir = port_dir.rstrip("/")
    entry = catalog_entry(port_dir)
    licence = (entry or {}).get("licence", "")
    is_open = bool(entry) and licence.upper() not in CLOSED_LICENCES and entry.get("distributable") is True
    n = len(scenarios(port_dir))
    cfg_name = port_config(port_dir)[1].get("name", Path(port_dir).name)
    return {
        "port": port_dir,
        "name": Path(port_dir).name,
        "configName": cfg_name,  # porter run's workspace is $PORTER_HOME/runs/<configName>
        "inCatalog": entry is not None,
        "licence": licence,
        "open": is_open,  # only these ports' jars may be uploaded as workflow artifacts
        "scenarios": n,
        "smokeOnly": n == 0,
        "ciSkip": bool(missing_urls(port_dir)),  # required input without a url: CI cannot run this port
    }


def missing_urls(port_dir):
    """Required inputs without a direct url: CI cannot fetch them (licence ARR, download only from the author's page)."""
    return [i["name"] for i in (port_config(port_dir)[1].get("inputs") or []) if not i.get("url") and not i.get("optional")]


def valid_port_dirs():
    out = []
    for d in sorted((ROOT / "ports").iterdir()):
        if d.is_dir() and not d.name.startswith("_") and list(d.glob("*.port.yaml")):
            out.append(f"ports/{d.name}")
    return out


def emit(result, github_output):
    # ports whose required inputs have no direct url are not run by CI: say so, loudly, and leave them out of the matrix
    skipped = [p for p in result["ports"] if p.get("ciSkip")]
    result["ports"] = [p for p in result["ports"] if not p.get("ciSkip")]
    for p in skipped:
        msg = (f"CI SKIPS {p['port']}: its required inputs ({', '.join(missing_urls(p['port']))}) have no direct url "
               "(licence ARR: the files can only be downloaded from the author's page). The port is NOT built or tested in CI; "
               "the maintainer builds and verifies it locally (and runs `porter inventory`, see CONTRIBUTING.md).")
        print(f"::warning::{msg}", file=sys.stderr)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
                f.write(f"### {p['port']}: skipped in CI\n{msg}\n")
    print(json.dumps(result))
    if github_output and os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write(f"matrix={json.dumps({'include': result['ports']})}\n")
            f.write(f"count={len(result['ports'])}\n")
            f.write(f"regression={'true' if result['regression'] else 'false'}\n")


def cmd_changed(a):
    names = subprocess.run(["git", "diff", "--name-only", f"{a.base}...HEAD"], cwd=ROOT, check=True,
                           capture_output=True, text=True).stdout.split()
    valid = set(valid_port_dirs())
    touched = []
    for n in names:
        m = re.match(r"ports/([^/]+)/", n)
        if m and f"ports/{m.group(1)}" in valid and f"ports/{m.group(1)}" not in touched:
            touched.append(f"ports/{m.group(1)}")
    regression = any(n.startswith(REGRESSION_PATHS) for n in names)
    if regression and REFERENCE_PORT not in touched and REFERENCE_PORT in valid:
        touched.append(REFERENCE_PORT)
    emit({"regression": regression, "ports": [info(p) for p in touched]}, a.github_output)


def cmd_all(a):
    emit({"regression": True, "ports": [info(p) for p in valid_port_dirs()]}, a.github_output)


def cmd_catalog_check(_a):
    problems = []
    profiles = set()
    for e in load_catalog().get("ports", []):
        prof = e.get("profile")
        if not prof:
            continue
        profiles.add(prof.rstrip("/"))
        if not (ROOT / prof).is_dir():
            problems.append(f"{e['id']}: profile {prof} does not exist")
            continue
        n = len(scenarios(prof))
        smoke = bool(e.get("smokeOnly"))
        if n == 0 and not smoke:
            problems.append(f"{e['id']}: {prof}/scenarios has no scenario files; set `smokeOnly: true` in the catalog entry "
                            "(the port is then verified by the automatic smoke pass only)")
        if n > 0 and smoke:
            problems.append(f"{e['id']}: has {n} scenario files but is marked smokeOnly: true; remove the flag")
    # a port of a mod that is not open-licence must contain no adapted code or assets (inventory category C = 0).
    # CI cannot decompile the original, so this is the static part: no patches/, no textures/, no images, sounds or archives.
    # The maintainer runs `porter inventory` locally for the content part (PR template asks for its output).
    for e in load_catalog().get("ports", []):
        prof = e.get("profile")
        closed = e.get("licence", "").upper() in CLOSED_LICENCES or e.get("distributable") is not True
        if not prof or not closed or not (ROOT / prof).is_dir():
            continue
        for d in ("patches", "textures"):
            if (ROOT / prof / d).exists():
                problems.append(f"{e['id']}: closed-licence profile has {prof}/{d}/ (inventory category C must be 0: no adapted code or assets)")
        for f in (ROOT / prof).rglob("*"):
            if f.is_file() and f.suffix.lower() in BINARY_SUFFIXES and f.name != "gradle-wrapper.jar":
                problems.append(f"{e['id']}: closed-licence profile contains {f.relative_to(ROOT)} (images, sounds and archives could be derived assets)")
    for p in valid_port_dirs():
        if p not in profiles:
            problems.append(f"{p} has a port config but no catalog entry with `profile: {p}`")
    if problems:
        print("catalog check FAILED:\n  " + "\n  ".join(problems), file=sys.stderr)
        sys.exit(1)
    print("catalog check passed")


def cmd_info(a):
    print(json.dumps(info(a.port)))


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


NOT_DIRECT = """\
::error::input '{name}' ({url}) is not a direct file download.
  {why}
  The profile must give a URL that returns the file itself, for example:
    - Modrinth CDN:        https://cdn.modrinth.com/data/<project>/versions/<version>/<file>.jar
    - GitHub release:      https://github.com/<owner>/<repo>/releases/download/<tag>/<file>
                           (or a source archive: https://github.com/<owner>/<repo>/archive/<commit>.zip)
    - CurseForge (files):  https://mediafilez.forgecdn.net/files/<first 4 digits>/<last 3 digits>/<file>.jar
                           (the CDN link of the file, or the CurseForge API download URL of the file id)
  A CurseForge/Modrinth *page* (curseforge.com/minecraft/mc-mods/..., modrinth.com/mod/...) is HTML, not the file.
  Keep that page in `page:` and put the direct link in `url:`. A url is only allowed for open-licence files."""


def cmd_inputs(a):
    _, cfg = port_config(a.port)
    dest = Path(a.dest)
    dest.mkdir(parents=True, exist_ok=True)
    failed = False
    for inp in cfg.get("inputs", []) or []:
        name, url = inp["name"], inp.get("url")
        if not url:
            if inp.get("optional"):
                print(f"-- {name}: optional and has no url, skipped (checks against the original are then skipped)", file=sys.stderr)
                continue
            print(f"::error::input '{name}' ({inp.get('file')}) has no direct `url:`. CI cannot fetch files it has no direct link to "
                  f"(page: {inp.get('page')}). If the mod is open-licence add a direct url (Modrinth CDN, GitHub release, CurseForge files CDN); "
                  "if it is not, CI cannot verify this port: the maintainer verifies it locally.", file=sys.stderr)
            failed = True
            continue
        target = dest / inp["file"]
        req = urllib.request.Request(url, headers={"User-Agent": "porter-ci/1 (+https://github.com/Chasik8/mc-porter)"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                ctype = r.headers.get("Content-Type", "")
                clen = int(r.headers.get("Content-Length") or 0)
                if ctype.lower().startswith("text/html"):
                    print(NOT_DIRECT.format(name=name, url=url, why=f"The server answered Content-Type: {ctype} (a web page)."), file=sys.stderr)
                    failed = True
                    continue
                if clen > 600 << 20:
                    print(f"::error::input '{name}': {clen} bytes is more than the 600 MB CI limit", file=sys.stderr)
                    failed = True
                    continue
                head = r.read(1 << 20)
                with open(target, "wb") as f:
                    f.write(head)
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
        except (urllib.error.URLError, TimeoutError) as e:
            print(NOT_DIRECT.format(name=name, url=url, why=f"Download failed: {e}"), file=sys.stderr)
            failed = True
            continue
        if head[:15].lower().lstrip().startswith((b"<!doctype html", b"<html")):
            print(NOT_DIRECT.format(name=name, url=url, why="The body is an HTML document."), file=sys.stderr)
            failed = True
            continue
        got = sha256_of(target)
        if got != str(inp["sha256"]).lower():
            print(f"::error::input '{name}': sha256 mismatch for {url}\n  declared {inp['sha256']}\n  got      {got}\n"
                  "  (the file changed upstream, or the profile has the wrong checksum)", file=sys.stderr)
            failed = True
            continue
        print(f"{name}={target}")
    if failed:
        sys.exit(1)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("changed")
    s.add_argument("--base", required=True)
    s.add_argument("--github-output", action="store_true")
    s.set_defaults(f=cmd_changed)
    s = sub.add_parser("all")
    s.add_argument("--github-output", action="store_true")
    s.set_defaults(f=cmd_all)
    s = sub.add_parser("catalog-check")
    s.set_defaults(f=cmd_catalog_check)
    s = sub.add_parser("info")
    s.add_argument("port")
    s.set_defaults(f=cmd_info)
    s = sub.add_parser("inputs")
    s.add_argument("port")
    s.add_argument("--dest", required=True)
    s.set_defaults(f=cmd_inputs)
    a = p.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
