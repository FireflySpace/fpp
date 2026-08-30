#!/usr/bin/env bash
#
# Build a CI-tested F Prime revision matching the staged FPP
# Env: GITHUB_WORKSPACE, FPRIME_DIR, FPRIME_VENV,
# FPRIME_PYTHON, LLVM_PROFILE_FILE. Matches GitHub's default bash (-e, pipefail).
set -eo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

fpp_version=$("$GITHUB_WORKSPACE/compiler/bin/fpp" --version | sed -n 's/^fpp v//p')
test -n "$fpp_version"
pin="fprime-fpp==$fpp_version"

# The matrix may supply an absolute interpreter path (manylinux) or a uv version
# request such as "3.10" (macOS). Materialize the venv before building.
uv venv --clear "$FPRIME_VENV" --python "$FPRIME_PYTHON"
export PATH="$GITHUB_WORKSPACE/compiler/bin:$FPRIME_VENV/bin:$PATH"
test "$(command -v fpp)" = "$GITHUB_WORKSPACE/compiler/bin/fpp"
fpp --version

# GCC 14's -Woverloaded-virtual + F Prime's -Werror errors on Svc/BufferAccumulator
export CFLAGS="-Wno-error=overloaded-virtual"
export CXXFLAGS="$CFLAGS"

# find-matching-fprime.sh clones into $FPRIME_DIR and prints candidate commits.
candidates=$(PYTHON="$FPRIME_VENV/bin/python" \
  "$script_dir/find-matching-fprime.sh" "$fpp_version")

cd "$FPRIME_DIR"
selected=
for commit in $candidates
do
  echo "Testing F Prime $commit with the staged FPP"
  git checkout --force "$commit"
  git clean -ffdX
  grep -Fqx "$pin" requirements.txt
  git submodule sync --recursive
  git submodule update --init --recursive
  git show -s --format='Candidate F Prime %H (%cs): %s'

  uv venv --clear "$FPRIME_VENV" --python "$FPRIME_PYTHON"
  uv pip install --python "$FPRIME_VENV/bin/python" -r requirements.txt
  if [[ "${FPRIME_PREPARE_ONLY:-false}" == true ]]
  then
    echo "Prepared matching F Prime revision $commit"
    exit 0
  fi
  if (
    cd TestDeploymentsProject
    fprime-util generate --force
    fprime-util build -j4
  )
  then
    selected=$commit
    break
  fi
  echo "F Prime $commit is incompatible with the staged FPP"
done
test -n "$selected"
echo "Profiling completed with compatible F Prime revision $selected"
