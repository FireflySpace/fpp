#!/usr/bin/env bash
#
# Patch the resolved Scala Native nativelib jar with the MADV_HUGEPAGE
# MemoryMap.c hunks, then refresh coursier's checksum sidecars so the patched
# jar is accepted on the next resolution. Scala Native keys its re-extraction
# on the jar's SHA-1 (the `jarhash` file it writes beside the extracted C), so
# patching the jar forces a fresh extract of the patched C on the next
# nativeLink.
#
# Linux-only: macOS uses a different allocator/mmap path and skips this. Run
# after the nativelib jar has been resolved into the cache (e.g. `sbt update`).
#
# This mutates a coursier cache in place. It only does so against a developer's
# shared default cache when CI=true (GitHub Actions runners are disposable);
# outside CI the cache to patch must be given explicitly via COURSIER_CACHE.
#
# Env:
#   COURSIER_CACHE  coursier cache root to patch (default: coursier's default)
#   SN_VERSION      Scala Native version (default 0.5.6)
# Args:
#   $1  path to patch-hugepages.py (the MemoryMap.c 3-hunk patcher)
set -euo pipefail

patch_py=${1:?usage: patch-nativelib-jar.sh <patch-hugepages.py>}
sn_version=${SN_VERSION:-0.5.6}
default_cache="${HOME}/.cache/coursier"
cache=${COURSIER_CACHE:-$default_cache}

# Guard against mutating a developer's shared default cache outside CI.
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

# Idempotent: patch the jar only if it does not already carry the hunks, but
# always refresh sidecars below (a prior interrupted run may have left them
# stale relative to the patched jar).
if unzip -p "$jar" "$mm" | grep -q MADV_HUGEPAGE; then
  echo "jar already carries the patch: $jar"
else
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  mkdir -p "$work/$(dirname "$mm")"
  unzip -p "$jar" "$mm" > "$work/$mm"
  python3 "$patch_py" "$work/$mm"
  # Update the entry in place; zip preserves the rest of the archive.
  ( cd "$work" && zip -q "$jar" "$mm" )
fi

# Refresh coursier's cached checksums so the patched jar verifies. `jar__sha1`
# / `jar__md5` are hex; `jar__sha1.computed` is the raw 20-byte digest.
sha1=$(sha1sum "$jar" | cut -d' ' -f1)
md5=$(md5sum "$jar" | cut -d' ' -f1)
printf '%s' "$sha1" > "$dir/.${name}__sha1"
printf '%s' "$md5"  > "$dir/.${name}__md5"
printf '%s' "$sha1" | python3 -c 'import sys,binascii;sys.stdout.buffer.write(binascii.unhexlify(sys.stdin.read().strip()))' > "$dir/.${name}__sha1.computed"

echo "patched + residecared: $jar"
echo "  sha1=$sha1"
unzip -p "$jar" "$mm" | grep -c MADV_HUGEPAGE | sed 's/^/  MADV_HUGEPAGE occurrences: /'
