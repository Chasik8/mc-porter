#!/usr/bin/env python3
"""Publish-safety check, single pass (the rules are documented in scripts/publish-check.sh, which calls this).

Each file is read once and every check runs on its text in memory; the shell version started 15-20 grep processes
per file, which took minutes on Windows.
"""
import fnmatch
import os
import re
import subprocess
import sys

SKIP = {"gradle/wrapper/gradle-wrapper.jar", "scripts/publish-check.sh", "scripts/publish_check.py",
        "scripts/publish-denylist.txt", "scripts/publish-denylist.local.txt", "scripts/publish-allow.txt"}
NAME_OK = ("ports/ic2c-steam/", "docs/release/", "ports/catalog.yaml", "ports/README.md")
MAPPING_SUFFIXES = (".tiny", ".tsrg", ".tsrg2", ".srg", ".csrg", ".exc", "/fields.csv", "/methods.csv",
                    "/params.csv", "client.txt", "server.txt", ".proguard")

PROGUARD_CLASS = re.compile(r"^\s*[a-zA-Z0-9_.$]+ -> [a-zA-Z0-9_$]+:$", re.M)
PROGUARD_MEMBER = re.compile(r"^\s+[0-9]+:[0-9]+:", re.M)
BANNER = re.compile(r"Decompiled with CFR|Decompiled by Procyon|// Decompiled by Jad|fernflower|JD-Core", re.I)
SRG = re.compile(r"\b(m|f|func|field|p)_[0-9]+_[a-z]?\b")
CFR_VAR = re.compile(r"\bvar[0-9]+\b")
LOCAL_PATH = re.compile(r"[A-Za-z]:\\\\?Users\\\\?[A-Za-z0-9<>._-]+|/c/Users/[A-Za-z0-9<>._-]+"
                        r"|/home/[a-z][a-z0-9_-]+/|/Users/[A-Za-z][A-Za-z0-9._-]+/")
PLACEHOLDER = re.compile(r"<you>|<user>|you/|/home/me/")
PROJECT_PATH = re.compile(r"\b[A-Za-z]:[\\/]Project[\\/]")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
EMAIL_OK = re.compile(r"^(git@github\.com|.*@example\.(org|com))$")


def git_lines(*args):
    out = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    return [l for l in out.stdout.splitlines() if l.strip()]


def list_file(path):
    if not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return [l.strip() for l in fh if l.strip() and not l.lstrip().startswith("#")]


def main(argv):
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    if argv[:1] == ["--all"]:
        files = git_lines("ls-files")
    elif argv[:1] == ["--files"]:
        files = argv[1:]
    else:
        base = os.environ.get("BASE_REF", "origin/main")
        ok = subprocess.run(["git", "rev-parse", "--verify", "-q", base], capture_output=True).returncode == 0
        files = git_lines("diff", "--name-only", "--diff-filter=ACMR", base + "...HEAD") if ok else git_lines("ls-files")

    allow = list_file("scripts/publish-allow.txt")
    deny = list_file("scripts/publish-denylist.txt") + list_file("scripts/publish-denylist.local.txt")
    deny_res = [re.compile(r"^\s*package\s+" + re.escape(d), re.M) for d in deny]
    # written so that this script does not match itself; extend with PUBLISH_FORBIDDEN_NAMES='regex'
    forbidden = "i[c]2|industrial[c]raft"
    if os.environ.get("PUBLISH_FORBIDDEN_NAMES"):
        forbidden += "|" + os.environ["PUBLISH_FORBIDDEN_NAMES"]
    forbidden_re = re.compile(forbidden, re.I)
    traces_re = re.compile("cl[a]ude|anth[r]opic|orches[t]rator|co-auth[o]red-by", re.I)

    problems = []

    def problem(f, msg):
        problems.append(f"PUBLISH-CHECK: {f}: {msg}")

    for f in files:
        if not os.path.isfile(f) or f in SKIP:
            continue
        if f.startswith("private/") or "/private/" in f:
            problem(f, "private/ content must never be committed")
            continue
        low = f.lower()
        if low.endswith(MAPPING_SUFFIXES):
            problem(f, "looks like a mapping file (never redistribute mappings)")
            continue
        if low.endswith((".jar", ".class", ".zip")):
            problem(f, "binary archive/class file")
            continue
        with open(f, "rb") as fh:
            data = fh.read()
        is_text = b"\0" not in data[:8192] and len(data) > 0
        text = data.decode("utf-8", "replace") if is_text else ""

        name_ok = f.startswith(NAME_OK[:2]) or f in NAME_OK[2:]
        if not name_ok:
            if forbidden_re.search(f):
                problem(f, "path contains a forbidden private-mod name")
            m = forbidden_re.search(text)
            if m:
                problem(f, f"forbidden private-mod name ({m.group(0)})")
        if traces_re.search(f):
            problem(f, "path contains a development-process trace")
        m = traces_re.search(text)
        if m:
            problem(f, f"development-process trace ({m.group(0)})")
        if any(fnmatch.fnmatchcase(f, g) for g in allow):
            continue
        if len(data) > 1048576:
            problem(f, f"larger than 1 MB ({len(data)} bytes)")
        if not is_text:
            continue
        if PROGUARD_CLASS.search(text) and PROGUARD_MEMBER.search(text):
            problem(f, "ProGuard (Mojang) mapping content")
        if f.endswith(".java"):
            if BANNER.search(text):
                problem(f, "decompiler banner")
            srg = len(SRG.findall(text))
            if srg > 20:
                problem(f, f"{srg} SRG/obfuscated member names (decompiled code?)")
            cfr = len(CFR_VAR.findall(text))
            if cfr > 20:
                problem(f, f"{cfr} CFR-style names var1, var2, ... (decompiled code?)")
            for d, r in zip(deny, deny_res):
                if r.search(text):
                    problem(f, f"package of a non-open mod ({d})")
        m = LOCAL_PATH.search(text)
        if m and not PLACEHOLDER.search(m.group(0)):
            problem(f, f"local absolute path ({m.group(0)})")
        if PROJECT_PATH.search(text):
            problem(f, "local absolute path (drive:/Project)")
        for e in EMAIL.findall(text):
            if not EMAIL_OK.match(e):
                problem(f, f"e-mail address ({e})")
                break

    for p in problems:
        print(p)
    if problems:
        print(f"publish-check FAILED ({len(files)} file(s) checked). See the limits in the header of scripts/publish-check.sh.")
        return 1
    print(f"publish-check passed ({len(files)} file(s) checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
