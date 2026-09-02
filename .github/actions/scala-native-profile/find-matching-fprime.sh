#!/usr/bin/env bash
#
# Find the CI-tested F Prime revision(s) whose requirements.txt pins a given FPP
# version, newest first (one commit SHA per line on stdout; diagnostics on
# stderr). Runnable locally to debug revision selection, e.g.:
#
#   GITHUB_TOKEN=ghp_... .github/actions/scala-native-profile/find-matching-fprime.sh 3.3.0
#
# FPP version : $1, else `$FPP --version`, else $GITHUB_WORKSPACE/compiler/bin/fpp.
# F Prime clone: $FPRIME_DIR (reused if present), else a temp clone removed on exit.
# Python       : $PYTHON, else python3 (selector uses only the stdlib).
# Auth         : honors $GITHUB_TOKEN / $GH_TOKEN (recommended locally to avoid
#                the 60 req/hr unauthenticated GitHub API limit).
#
# select-fprime-revision.py reads each candidate's requirements.txt via
# `git show <sha>:requirements.txt`, so it runs inside the clone.
set -eo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "${1:-}" ]
then
  fpp_version=$1
else
  fpp_bin="${FPP:-${GITHUB_WORKSPACE:-.}/compiler/bin/fpp}"
  fpp_version=$("$fpp_bin" --version | sed -n 's/^fpp v//p')
fi
test -n "$fpp_version"
echo "Finding CI-tested F Prime revisions for fprime-fpp==$fpp_version" >&2

fprime_dir=${FPRIME_DIR:-}
cleanup=
if [ -z "$fprime_dir" ]
then
  fprime_dir=$(mktemp -d)
  cleanup=$fprime_dir
fi
trap '[ -n "$cleanup" ] && rm -rf "$cleanup"' EXIT

if [ ! -d "$fprime_dir/.git" ]
then
  git clone --quiet https://github.com/nasa/fprime.git "$fprime_dir" >&2
  git config --global --add safe.directory "$fprime_dir"
fi

( cd "$fprime_dir" && "${PYTHON:-python3}" "$script_dir/select-fprime-revision.py" "$fpp_version" )
