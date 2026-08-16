#!/usr/bin/env python3

import json
import subprocess
import sys
import urllib.request


def git(*args: str) -> str:
    return subprocess.check_output(
        ("git", *args), stderr=subprocess.DEVNULL, text=True
    ).strip()


def github_get(url: str):
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "fpp-scala-native-profile",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers)) as response:
        return response.read()


def has_pin(commit: str, pin: str) -> bool:
    try:
        requirements = git("show", f"{commit}:requirements.txt").splitlines()
    except subprocess.CalledProcessError:
        return False
    return pin in requirements


def successful_fprime_revisions():
    repository_url = "https://api.github.com/repos/nasa/fprime"
    default_branch = json.loads(github_get(repository_url))["default_branch"]
    runs_url = (
        f"{repository_url}/actions/workflows/framework.yml/runs"
        f"?branch={default_branch}&status=success&per_page=100"
    )
    seen = set()
    for run in json.loads(github_get(runs_url))["workflow_runs"]:
        commit = run["head_sha"]
        if commit not in seen:
            seen.add(commit)
            yield commit


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} fpp-version")
    pin = f"fprime-fpp=={sys.argv[1]}"
    candidates = []
    for commit in successful_fprime_revisions():
        if has_pin(commit, pin):
            candidates.append(commit)
    if not candidates:
        raise SystemExit(
            f"no successful public F Prime Framework CI run has pin {pin!r}"
        )
    print(*candidates, sep="\n")


if __name__ == "__main__":
    main()
