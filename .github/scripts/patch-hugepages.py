#!/usr/bin/env python3
# Patch Scala Native 0.5.6 gc/shared/MemoryMap.c to madvise(MADV_HUGEPAGE)
# the immix heap mmap regions. Semantically null (an advisory hint); only
# affects page backing. Idempotent. Usage: patch-hugepages.py <MemoryMap.c>
import sys, io

path = sys.argv[1]
src = io.open(path, encoding="utf-8").read()

if "MADV_HUGEPAGE" in src:
    print("already patched:", path)
    sys.exit(0)

# 1) Ensure the GNU MADV_HUGEPAGE macro is visible (needs _GNU_SOURCE on glibc).
marker = '// MemoryMap.c is used by all GCs and Zone\n'
assert marker in src, "unexpected header"
src = src.replace(
    marker,
    marker + "\n#ifndef _GNU_SOURCE\n#define _GNU_SOURCE\n#endif\n",
    1,
)

# 2) memoryMap(): hint huge pages right after the anonymous mmap.
anchor1 = (
    "    word_t *addr = mmap(NULL, memorySize, HEAP_MEM_PROT, HEAP_MEM_FLAGS,\n"
    "                        HEAP_MEM_FD, HEAP_MEM_FD_OFFSET);\n"
    "    if (addr == MAP_FAILED)\n"
    "        return NULL;\n"
    "    return addr;\n"
)
repl1 = (
    "    word_t *addr = mmap(NULL, memorySize, HEAP_MEM_PROT, HEAP_MEM_FLAGS,\n"
    "                        HEAP_MEM_FD, HEAP_MEM_FD_OFFSET);\n"
    "    if (addr == MAP_FAILED)\n"
    "        return NULL;\n"
    "#if defined(__linux__) && defined(MADV_HUGEPAGE)\n"
    "    // Back the GC heap with transparent huge pages under THP=madvise.\n"
    "    madvise(addr, memorySize, MADV_HUGEPAGE);\n"
    "#endif\n"
    "    return addr;\n"
)
assert anchor1 in src, "memoryMap anchor not found"
src = src.replace(anchor1, repl1, 1)

# 3) memoryMapPrealloc(): same hint after the prealloc mmap succeeds.
anchor2 = (
    "        mmap(NULL, memorySize, HEAP_MEM_PROT, HEAP_MEM_FLAGS_PREALLOC,\n"
    "             HEAP_MEM_FD, HEAP_MEM_FD_OFFSET);\n"
    "    if (addr == MAP_FAILED)\n"
    "        return NULL;\n"
)
repl2 = anchor2 + (
    "#if defined(__linux__) && defined(MADV_HUGEPAGE)\n"
    "    madvise(addr, memorySize, MADV_HUGEPAGE);\n"
    "#endif\n"
)
assert anchor2 in src, "memoryMapPrealloc anchor not found"
src = src.replace(anchor2, repl2, 1)

io.open(path, "w", encoding="utf-8").write(src)
print("patched:", path)
