#!/usr/bin/env python3
"""Anti-spam throttle of the PR workflow: at most one CI run per hour per contributor.

Counts this workflow's pull_request runs of the same actor in the last 60 minutes (GitHub API, GITHUB_TOKEN with
`actions: read`). A run counts only if it really started the build (its `core` job was not skipped or cancelled), so a
run that was itself throttled, or was cancelled because the contributor pushed again to the same PR, does not extend
the wait. The repo owner and every non-PR event (schedule, workflow_dispatch, push to main) are exempt.

Env: GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_EVENT_NAME, GITHUB_ACTOR, GITHUB_REPOSITORY_OWNER,
     GITHUB_API_URL, THROTTLE_WORKFLOW (file name, default ci.yml), THROTTLE_PR (PR number, may be empty),
     THROTTLE_WINDOW_MIN (default 60), THROTTLE_NOW (ISO time, tests only).
Writes throttled=true|false and message=... to $GITHUB_OUTPUT; always exits 0 (the `build` job turns it into a red check).
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

E = os.environ
API = E.get("GITHUB_API_URL", "https://api.github.com")
REPO = E["GITHUB_REPOSITORY"]
WORKFLOW = E.get("THROTTLE_WORKFLOW", "ci.yml")
WINDOW = int(E.get("THROTTLE_WINDOW_MIN", "60"))
ACTOR = E["GITHUB_ACTOR"]
OWNER = E.get("GITHUB_REPOSITORY_OWNER", "")
RUN_ID = int(E.get("GITHUB_RUN_ID", "0"))
PR = E.get("THROTTLE_PR", "")


def parse(ts):
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def api(path, **query):
    url = f"{API}{path}" + ("?" + urllib.parse.urlencode(query) if query else "")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {E.get('GITHUB_TOKEN', '')}",
                                               "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def out(throttled, message):
    print(message)
    if E.get("GITHUB_OUTPUT"):
        with open(E["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write(f"throttled={'true' if throttled else 'false'}\n")
            f.write(f"message={message}\n")


def main():
    if E.get("GITHUB_EVENT_NAME") != "pull_request":
        return out(False, f"not throttled: event {E.get('GITHUB_EVENT_NAME')} is exempt")
    if ACTOR.lower() == OWNER.lower() or ACTOR.lower() == "chasik8":
        return out(False, f"not throttled: {ACTOR} is the repository owner")
    now = parse(E["THROTTLE_NOW"]) if E.get("THROTTLE_NOW") else datetime.now(timezone.utc)
    since = now - timedelta(minutes=WINDOW)
    runs = api(f"/repos/{REPO}/actions/workflows/{WORKFLOW}/runs", actor=ACTOR, event="pull_request",
               created=">=" + since.strftime("%Y-%m-%dT%H:%M:%SZ"), per_page=100).get("workflow_runs", [])
    counted = []
    for r in runs:
        if r["id"] == RUN_ID or parse(r["created_at"]) < since:
            continue
        if r.get("conclusion") == "cancelled":
            continue
        same_pr = PR and any(str(p.get("number")) == PR for p in r.get("pull_requests") or [])
        if same_pr and r["status"] != "completed":
            continue  # concurrency (cancel-in-progress) is cancelling it right now: the new push replaces it
        jobs = api(f"/repos/{REPO}/actions/runs/{r['id']}/jobs", per_page=100).get("jobs", [])
        core = [j for j in jobs if j["name"] == "core"]
        if core and core[0].get("conclusion") not in ("skipped", "cancelled"):
            counted.append(r)
    if not counted:
        return out(False, f"ok: no other CI run by {ACTOR} in the last {WINDOW} minutes")
    last = max(parse(r["created_at"]) for r in counted)
    until = (last + timedelta(minutes=WINDOW)).strftime("%H:%M")
    r = max(counted, key=lambda x: x["created_at"])
    what = "is still running" if r["status"] != "completed" else "finished"
    out(True, f"max 1 CI run per hour per contributor: your run {r['html_url']} {what}; push again after {until} UTC "
              f"(or re-run this workflow then). The maintainer can also re-run it earlier.")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # the throttle must never block a contributor because the API hiccupped
        out(False, f"throttle check skipped (API error: {e})")
        sys.exit(0)
