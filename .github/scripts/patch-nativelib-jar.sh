#!/usr/bin/env bash
set -euo pipefail

patch_py=${1:?usage: patch-nativelib-jar.sh <patch-hugepages.py>}
sn_version=${SN_VERSION:-0.5.6}
default_cache="${HOME}/.cache/coursier"
cache=${COURSIER_CACHE:-$default_cache}

if [ "$cache" = "$default_cache" ] && [ "${CI:-}" != "true" ]; then
  echo "refusing to patch the shared default coursier cache outside CI: $cache" >&2
  echo "set COURSIER_CACHE to a disposable cache to patch it locally" >&2
  exit 2
fi

rel="v1/https/repo1.maven.org/maven2/org/scala-native/nativelib_native0.5_3/$sn_version"
dir="$cache/$rel"
name="nativelib_native0.5_3-$sn_version.jar"
jar="$dir/$name"

if [ ! -f "$jar" ]; then
  echo "error: nativelib jar not resolved in this cache: $jar" >&2
  echo "run an sbt resolve (e.g. 'sbt --batch update') into this cache first" >&2
  exit 1
fi

mm=scala-native/gc/shared/MemoryMap.c

if unzip -p "$jar" "$mm" | grep -q MADV_HUGEPAGE; then
  echo "jar already carries the patch: $jar"
else
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  mkdir -p "$work/$(dirname "$mm")"
  unzip -p "$jar" "$mm" > "$work/$mm"
  python3 "$patch_py" "$work/$mm"
  ( cd "$work" && zip -q "$jar" "$mm" )
fi

sha1=$(sha1sum "$jar" | cut -d' ' -f1)
md5=$(md5sum "$jar" | cut -d' ' -f1)
printf '%s' "$sha1" > "$dir/.${name}__sha1"
printf '%s' "$md5"  > "$dir/.${name}__md5"
printf '%s' "$sha1" | python3 -c 'import sys,binascii;sys.stdout.buffer.write(binascii.unhexlify(sys.stdin.read().strip()))' > "$dir/.${name}__sha1.computed"

echo "patched: $jar"
echo "  sha1=$sha1"
unzip -p "$jar" "$mm" | grep -c MADV_HUGEPAGE | sed 's/^/  MADV_HUGEPAGE occurrences: /'
