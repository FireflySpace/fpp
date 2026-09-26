#!/usr/bin/env bash
tool=$(basename "$0")
clean=$(printf '%s' "$PATH" | tr ':' '\n' | grep -vxF "$FPP_SHIM" | paste -sd:)
real=$(PATH="$clean" command -v "$tool")
if [ -z "$real" ]; then
  echo "fpp-timing-wrapper: cannot resolve $tool outside $FPP_SHIM" >&2
  exit 127
fi
start=$(date +%s.%N)
"$real" "$@"; rc=$?
end=$(date +%s.%N)
awk -v a="$start" -v b="$end" -v t="$tool" 'BEGIN { printf "%s %.3f\n", t, b - a }' >> "$FPP_TOOL_LOG"
exit $rc
