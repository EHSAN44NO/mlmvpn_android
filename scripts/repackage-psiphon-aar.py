#!/usr/bin/env python3
"""
Move Psiphon's bundled gomobile runtime out of the `go` namespace.

WHY THIS EXISTS
---------------
This APK carries two gomobile-built libraries: `libv2ray.aar` (Xray) and
`psiphontunnel-<ver>.aar` (Psiphon's Go controller). gomobile gives every library it
builds the SAME two things:

  * the Java runtime classes `go.Seq`, `go.Universe`, `go.error`
  * one native library called `libgojni.so`, which is the entire Go program

so putting both in one APK fails twice over -- `Duplicate class go.Seq` at compile time,
and `2 files found with path lib/arm64-v8a/libgojni.so` at packaging time. Neither is a
merge conflict that a packaging rule can resolve: the two `libgojni.so` are different Go
programs (35 MB of Xray, 31 MB of Psiphon), so `pickFirst` would silently ship one and
leave every native method of the other unresolvable at runtime. That is a crash on first
use of whichever core lost, with nothing at build time to warn about it.

WHAT IT DOES
------------
Rewrites the Psiphon AAR so its runtime lives in `pg` instead of `go` and its native
library is `libpgjni.so` instead of `libgojni.so`. Xray's copy is left untouched, so the
two no longer collide anywhere.

Every replacement is BYTE-FOR-BYTE THE SAME LENGTH -- `go` -> `pg`, `gojni` -> `pgjni` --
which is the whole reason this is a safe transformation rather than a rewrite:

  * In `.class` files, constant-pool UTF-8 entries are length-prefixed. Same length means
    the prefix stays correct and no offset in the file moves, so no bytecode-level
    rewriting (and no bytecode library) is needed.
  * In the ELF, the JNI symbol names live in `.dynstr` and the class names the Go side
    passes to `FindClass` live in `.rodata`. Same length means no string table grows and
    no relocation has to be recomputed.

WHAT IT DELIBERATELY DOES NOT TOUCH
-----------------------------------
`go/error` inside the `.so`. It occurs exactly once there and it is not a class name --
it is the tail of a build path, `.../quic-go/errors.go`. Replacing it would corrupt a Go
runtime string for no gain. The Java class `go.error` IS renamed, because there it really
is a class.

The `gojni` string inside the `.so` is also left alone. It sits in Go's own data, and
these libraries carry no `DT_SONAME` (verified below), so the Android linker identifies
them by file name -- which the rename already changed.

USAGE
-----
    python scripts/repackage-psiphon-aar.py app/libs/psiphontunnel-2.0.39.aar

Writes `<name>-ns.aar` beside the input and prints a verification report. Re-run it after
upgrading psiphon-tunnel-core; keep the original AAR so the transformation stays
reproducible from an untouched source.
"""

import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile

# The rename. Both halves are the same length; see the module docstring for why that is
# load-bearing rather than a coincidence.
OLD_PKG, NEW_PKG = b"go", b"pg"
OLD_LIB, NEW_LIB = "gojni", "pgjni"

# Applied to .class files. `go/error` is here and NOT in SO_SUBS; that asymmetry is the
# point of the note in the docstring.
CLASS_SUBS = [
    (b"go/Seq", b"pg/Seq"),
    (b"go/Universe", b"pg/Universe"),
    (b"go/error", b"pg/error"),
    (OLD_LIB.encode(), NEW_LIB.encode()),
]

# Applied to libgojni.so. Exported JNI symbols first, then the FindClass/descriptor
# strings. The two sets are disjoint byte sequences (`Java_go_Seq` has an underscore where
# `go/Seq` has a slash), so the order between them does not matter.
SO_SUBS = [
    (b"Java_go_Seq", b"Java_pg_Seq"),
    (b"Java_go_Universe", b"Java_pg_Universe"),
    (b"go/Seq", b"pg/Seq"),
    (b"go/Universe", b"pg/Universe"),
]


def check_lengths():
    for old, new in CLASS_SUBS + SO_SUBS:
        if len(old) != len(new):
            raise SystemExit(f"substitution changes length: {old!r} -> {new!r}")


def has_soname(data):
    """True if the ELF declares DT_SONAME, which would need renaming too."""
    if data[:4] != b"\x7fELF":
        return False
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
        ent, tagfmt = 16, "<qQ"
    else:
        e_phoff = struct.unpack_from("<I", data, 0x1C)[0]
        e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x2A)
        ent, tagfmt = 8, "<iI"
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from("<I", data, off)[0] != 2:  # PT_DYNAMIC
            continue
        p_offset = struct.unpack_from("<Q" if is64 else "<I", data, off + (8 if is64 else 4))[0]
        cursor = p_offset
        while True:
            tag, _ = struct.unpack_from(tagfmt, data, cursor)
            if tag == 0:
                return False
            if tag == 14:  # DT_SONAME
                return True
            cursor += ent
    return False


def substitute(data, subs):
    total = 0
    for old, new in subs:
        n = data.count(old)
        if n:
            data = data.replace(old, new)
            total += n
    return data, total


# --------------------------------------------------------------------------------------
# ELF symbol hash table
#
# Renaming an exported symbol in `.dynstr` is only half the job, and the missing half is
# invisible until runtime. `dlsym` does not scan the symbol table -- it hashes the name and
# jumps to a bucket. Leave the table alone and every renamed symbol becomes unreachable
# while still being present, which the JVM reports as
#
#     UnsatisfiedLinkError: No implementation found for void pg.Seq.init()
#         (tried Java_pg_Seq_init and Java_pg_Seq_init__)
#
# on a library where `Java_pg_Seq_init` is right there in the exports. That is exactly how
# the first attempt at this failed.
#
# These libraries are linked `--hash-style=both`, so they carry BOTH tables, and the linker
# prefers the GNU one whenever DT_GNU_HASH is present. Only the SysV table can be rebuilt in
# place -- it imposes no ordering on `.dynsym`, so nothing else has to move. The GNU table
# demands `.dynsym` sorted by bucket, and reordering `.dynsym` renumbers every relocation
# that names a symbol by index; that is a rewrite, not a patch. So DT_GNU_HASH is dropped
# (see drop_gnu_hash) and the rebuilt SysV table becomes the one that answers.
# --------------------------------------------------------------------------------------

def elf_hash(name: bytes) -> int:
    """The SysV ELF hash, exactly as ld.so and the Android linker compute it."""
    h = 0
    for byte in name:
        h = ((h << 4) + byte) & 0xFFFFFFFF
        g = h & 0xF0000000
        if g:
            h ^= g >> 24
        h &= ~g & 0xFFFFFFFF
    return h


class Elf:
    """Just enough ELF to find the dynamic tables and map virtual addresses to offsets."""

    def __init__(self, data):
        self.data = bytearray(data)
        d = self.data
        if d[:4] != b"\x7fELF":
            raise ValueError("not an ELF")
        self.is64 = d[4] == 2
        if self.is64:
            self.phoff = struct.unpack_from("<Q", d, 0x20)[0]
            self.phentsize, self.phnum = struct.unpack_from("<HH", d, 0x36)
        else:
            self.phoff = struct.unpack_from("<I", d, 0x1C)[0]
            self.phentsize, self.phnum = struct.unpack_from("<HH", d, 0x2A)
        self.loads = []
        self.dynoff = None
        for i in range(self.phnum):
            off = self.phoff + i * self.phentsize
            p_type = struct.unpack_from("<I", d, off)[0]
            if self.is64:
                p_offset, p_vaddr = struct.unpack_from("<QQ", d, off + 8)
                p_filesz = struct.unpack_from("<Q", d, off + 32)[0]
            else:
                p_offset, p_vaddr = struct.unpack_from("<II", d, off + 4)
                p_filesz = struct.unpack_from("<I", d, off + 16)[0]
            if p_type == 1:      # PT_LOAD
                self.loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == 2:    # PT_DYNAMIC
                self.dynoff = p_offset

    def v2o(self, vaddr):
        for p_vaddr, p_offset, p_filesz in self.loads:
            if p_vaddr <= vaddr < p_vaddr + p_filesz:
                return p_offset + (vaddr - p_vaddr)
        raise ValueError(f"vaddr {vaddr:#x} is in no PT_LOAD")

    def dynamic(self):
        tags = {}
        fmt, step = ("<qQ", 16) if self.is64 else ("<iI", 8)
        cursor = self.dynoff
        while True:
            tag, val = struct.unpack_from(fmt, self.data, cursor)
            if tag == 0:
                return tags
            tags.setdefault(tag, val)
            cursor += step


DT_NULL, DT_HASH, DT_STRTAB, DT_SYMTAB, DT_SYMENT = 0, 4, 5, 6, 11
DT_GNU_HASH = 0x6FFFFEF5


def drop_gnu_hash(elf):
    """
    Remove DT_GNU_HASH from the dynamic array, so the linker falls back to DT_HASH.

    These libraries are linked `--hash-style=both`, and bionic prefers the GNU table whenever
    the tag is present. The GNU table cannot be rebuilt the way the SysV one can: its buckets
    require `.dynsym` to be SORTED by bucket index, and reordering `.dynsym` means renumbering
    every relocation that refers to a symbol by index. That is a rewrite, not a patch.

    Dropping the tag is the small change that gets the same result. The entry is overwritten
    with the last real entry and the last slot becomes DT_NULL, so the array simply ends one
    entry earlier and every other tag keeps its value. The bytes of the GNU table itself stay
    in the file, unreferenced -- harmless, and it keeps every section offset where it was.

    The cost is a marginally slower symbol lookup in a library that resolves a dozen names
    once, at load.
    """
    d = elf.data
    fmt, step = ("<qQ", 16) if elf.is64 else ("<iI", 8)
    entries = []
    cursor = elf.dynoff
    while True:
        tag, val = struct.unpack_from(fmt, d, cursor)
        entries.append((cursor, tag, val))
        if tag == DT_NULL:
            break
        cursor += step

    gnu = [i for i, (_, tag, _) in enumerate(entries) if tag == DT_GNU_HASH]
    if not gnu:
        return False
    last = len(entries) - 2          # the final real entry, just before DT_NULL
    index = gnu[0]
    if index != last:
        struct.pack_into(fmt, d, entries[index][0], entries[last][1], entries[last][2])
    struct.pack_into(fmt, d, entries[last][0], DT_NULL, 0)
    return True


def rebuild_sysv_hash(data):
    """Recompute DT_HASH from the symbol names as they now read. Returns (data, nsym)."""
    elf = Elf(data)
    if DT_HASH not in elf.dynamic():
        raise SystemExit("no DT_HASH: nothing indexes the symbols, so nothing can be fixed")
    drop_gnu_hash(elf)
    tags = elf.dynamic()
    if DT_GNU_HASH in tags:
        raise SystemExit("DT_GNU_HASH survived the drop")

    hash_off = elf.v2o(tags[DT_HASH])
    symtab_off = elf.v2o(tags[DT_SYMTAB])
    strtab_off = elf.v2o(tags[DT_STRTAB])
    syment = tags.get(DT_SYMENT, 24 if elf.is64 else 16)

    d = elf.data
    nbucket, nchain = struct.unpack_from("<II", d, hash_off)

    def sym_name(index):
        st_name = struct.unpack_from("<I", d, symtab_off + index * syment)[0]
        start = strtab_off + st_name
        return bytes(d[start:d.index(b"\x00", start)])

    STN_UNDEF = 0
    buckets = [STN_UNDEF] * nbucket
    chains = [STN_UNDEF] * nchain
    # Built back to front so the resulting chains come out in ascending index order, which is
    # what a linker emits and what makes the output diffable against the original.
    for index in range(nchain - 1, 0, -1):
        slot = elf_hash(sym_name(index)) % nbucket
        chains[index] = buckets[slot]
        buckets[slot] = index

    struct.pack_into(f"<{nbucket}I", d, hash_off + 8, *buckets)
    struct.pack_into(f"<{nchain}I", d, hash_off + 8 + nbucket * 4, *chains)
    return bytes(d), nchain


def hash_lookup(data, name: bytes):
    """Resolve a name the way the dynamic linker would, to prove the rebuild worked."""
    elf = Elf(data)
    tags = elf.dynamic()
    hash_off = elf.v2o(tags[DT_HASH])
    symtab_off = elf.v2o(tags[DT_SYMTAB])
    strtab_off = elf.v2o(tags[DT_STRTAB])
    syment = tags.get(DT_SYMENT, 24 if elf.is64 else 16)
    d = elf.data
    nbucket, nchain = struct.unpack_from("<II", d, hash_off)
    buckets_at = hash_off + 8
    chains_at = buckets_at + nbucket * 4

    index = struct.unpack_from("<I", d, buckets_at + (elf_hash(name) % nbucket) * 4)[0]
    while index != 0:
        st_name = struct.unpack_from("<I", d, symtab_off + index * syment)[0]
        start = strtab_off + st_name
        if bytes(d[start:d.index(b"\x00", start)]) == name:
            return True
        index = struct.unpack_from("<I", d, chains_at + index * 4)[0]
    return False


def rewrite_classes_jar(path, report):
    with zipfile.ZipFile(path) as src:
        items = [(i, src.read(i.filename)) for i in src.infolist()]

    patched = []
    for info, data in items:
        name = info.filename
        if name.endswith(".class"):
            data, n = substitute(data, CLASS_SUBS)
            report["class_subs"] += n
        # `go/Seq.class` and friends must also MOVE, or the class file would declare
        # pg/Seq while living at go/Seq.class and the loader would never find it.
        if name.startswith("go/"):
            name = "pg/" + name[3:]
            report["moved"] += 1
        patched.append((name, data))

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as out:
        for name, data in patched:
            out.writestr(name, data)


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    source = os.path.abspath(argv[1])
    if not source.endswith(".aar"):
        raise SystemExit("expected an .aar")
    check_lengths()

    target = source[: -len(".aar")] + "-ns.aar"
    work = tempfile.mkdtemp(prefix="psiphon-ns-")
    report = {"class_subs": 0, "moved": 0, "so": []}

    try:
        with zipfile.ZipFile(source) as z:
            z.extractall(work)

        rewrite_classes_jar(os.path.join(work, "classes.jar"), report)

        jni = os.path.join(work, "jni")
        for abi in sorted(os.listdir(jni)) if os.path.isdir(jni) else []:
            old_so = os.path.join(jni, abi, f"lib{OLD_LIB}.so")
            if not os.path.exists(old_so):
                continue
            data = open(old_so, "rb").read()
            if has_soname(data):
                raise SystemExit(
                    f"{abi}: this build declares DT_SONAME; the script must rename it too "
                    "before the Android linker can tell the two libraries apart"
                )
            data, n = substitute(data, SO_SUBS)
            # The names are right now; the index that finds them is not. See rebuild_sysv_hash.
            data, nsym = rebuild_sysv_hash(data)
            for probe in (b"Java_pg_Seq_init", b"Java_pg_Seq_setContext"):
                if not hash_lookup(data, probe):
                    raise SystemExit(f"{abi}: {probe.decode()} is not reachable through DT_HASH")
            if hash_lookup(data, b"Java_go_Seq_init"):
                raise SystemExit(f"{abi}: the old Java_go_Seq_init is still resolvable")
            with open(os.path.join(jni, abi, f"lib{NEW_LIB}.so"), "wb") as f:
                f.write(data)
            os.remove(old_so)
            report["so"].append((abi, n, nsym))

        # The consumer ProGuard rules keep the runtime by name, so they have to follow it.
        rules = os.path.join(work, "proguard.txt")
        if os.path.exists(rules):
            text = open(rules, encoding="utf-8").read()
            open(rules, "w", encoding="utf-8").write(
                text.replace("class go.", "class pg.")
            )

        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as out:
            for root, _, files in os.walk(work):
                for name in files:
                    full = os.path.join(root, name)
                    out.write(full, os.path.relpath(full, work).replace(os.sep, "/"))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    # --- verification, printed rather than assumed ---
    print(f"wrote {target}")
    print(f"  class-file substitutions : {report['class_subs']}")
    print(f"  go/ -> pg/ entries moved : {report['moved']}")
    for abi, n, nsym in report["so"]:
        print(f"  {abi:12} native subs  : {n}  (DT_HASH rebuilt over {nsym} symbols, lookup verified)")

    leftovers = []
    with zipfile.ZipFile(target) as z:
        names = z.namelist()
        if any(n.startswith("jni/") and n.endswith(f"lib{OLD_LIB}.so") for n in names):
            leftovers.append(f"lib{OLD_LIB}.so still present")
        with zipfile.ZipFile(__import__("io").BytesIO(z.read("classes.jar"))) as jar:
            if any(n.startswith("go/") for n in jar.namelist()):
                leftovers.append("go/ classes still present")
            for entry in jar.namelist():
                if entry.endswith(".class") and re.search(rb"go/(Seq|Universe|error)", jar.read(entry)):
                    leftovers.append(f"go/* reference left in {entry}")
    if leftovers:
        raise SystemExit("VERIFICATION FAILED:\n  " + "\n  ".join(leftovers))
    print("  verification             : clean")


if __name__ == "__main__":
    main(sys.argv)
