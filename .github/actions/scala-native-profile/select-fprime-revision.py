#!/usr/bin/env python3

import json
import os
import subprocess
import sys
import urllib.request


def git(*args: str) -> str:
    return subprocess.check_output(("git", *args), text=True).strip()


def has_pin(commit: str, pin: str) -> bool:
    try:
        requirements = git("show", f"{commit}:requirements.txt").splitlines()
    except subprocess.CalledProcessError:
        return False
    return pin in requirements


def passed_fprime_examples_build(commit: str) -> bool:
    url = (
        "https://api.github.com/repos/nasa/fprime/commits/"
        f"{commit}/check-runs?per_page=100"
    )
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "fpp-scala-native-profile",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers)) as response:
        checks = json.load(response)["check_runs"]
    return any(
        check["name"] == "run / Build"
        and check["conclusion"] == "success"
        and "/actions/runs/" in check["details_url"]
        for check in checks
    )


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} fpp-version")
    pin = f"fprime-fpp=={sys.argv[1]}"
    for commit in git("rev-list", "--first-parent", "HEAD").splitlines():
        if has_pin(commit, pin) and passed_fprime_examples_build(commit):
            print(commit)
            return
    raise SystemExit(
        f"no first-parent F Prime revision with pin {pin!r} has a successful "
        "fprime-examples 'run / Build' check"
    )


if __name__ == "__main__":
    main()
