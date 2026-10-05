#!/usr/bin/env python3
"""Independent verifier for a KernelSU-patched boot / init_boot image.

This tool deliberately shares NO code with _tools/patch-init-boot.py: it has its
own boot-header parser, its own LZ4-legacy decoder, its own newc walker and its
own ELF64 .symtab lookup. A green run is therefore real evidence about the
product instead of a self-report by the producer.

Checks
  1. Android boot image header (magic, version, ramdisk region)
  2. ramdisk decode -> newc archive -> the kernelsu.ko member
  3. ko identity: ELF64 aarch64, project cert SHA-256 literal x1,
     official (c371061b...) and AOSP debug (f9af24bd...) hashes absent,
     the aarch64 'cmp w21, #imm' immediate == the project cert DER length
  4. provenance: KSU_MANAGER_PACKAGE compiled in ("path A", from source) or not
     ("path B", byte-patched); --require-source-build turns a path-B asset into
     a failure instead of a warning
  5. optional: the ksu_manager_appid seed (--expect-appid N)

Exit status: 0 = every requested check passed, 1 = at least one failed,
2 = usage / unreadable input.
"""
import argparse
import hashlib
import json
import os
import struct
import sys

BOOT_MAGIC = b"ANDROID!"
LZ4_LEGACY_MAGIC = bytes.fromhex("02214c18")
OFFICIAL_HASH = "c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6"
DEBUG_HASH = "f9af24bdd3c0a4947c06eb3a3bd40c25f08e19fd1a07393ec07c6ace40e63d0e"
CMP_MASK = 0xFFC003FF          # matches 'cmp w21, #imm' (SUBS WZR, W21, #imm)
CMP_SHAPE = 0x710002BF

results = []


def check(ok, label, evidence):
    results.append(bool(ok))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, (" -- " + evidence) if evidence else ""))
    return ok


def warn(label, evidence):
    print("[WARN] %s -- %s" % (label, evidence))


def parse_boot(img):
    if img[:8] != BOOT_MAGIC:
        raise ValueError("not an Android boot image (magic %r)" % img[:8])
    kernel_size = struct.unpack_from("<I", img, 8)[0]
    ramdisk_size = struct.unpack_from("<I", img, 12)[0]
    header_version = struct.unpack_from("<I", img, 40)[0]
    page = 4096 if header_version >= 3 else struct.unpack_from("<I", img, 36)[0]
    return kernel_size, ramdisk_size, header_version, page


def lz4_decode(src: bytes) -> bytes:
    out = bytearray()
    ip = 0
    n = len(src)
    while ip < n:
        token = src[ip]
        ip += 1
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[ip]
                ip += 1
                lit += b
                if b != 255:
                    break
        out += src[ip:ip + lit]
        ip += lit
        if ip >= n:
            break
        offset = src[ip] | (src[ip + 1] << 8)
        ip += 2
        mlen = token & 0xF
        if mlen == 15:
            while True:
                b = src[ip]
                ip += 1
                mlen += b
                if b != 255:
                    break
        mlen += 4
        st = len(out) - offset
        if st < 0:
            raise ValueError("corrupt LZ4 block: bad match offset")
        for i in range(mlen):
            out.append(out[st + i])
    return bytes(out)


def decode_ramdisk(rd: bytes) -> bytes:
    if rd[:4] == LZ4_LEGACY_MAGIC:
        size = struct.unpack_from("<I", rd, 4)[0]
        return lz4_decode(rd[8:8 + size])
    if rd[:6] in (b"070701", b"070702"):
        return rd
    raise ValueError("unsupported ramdisk compression (magic %r)" % rd[:4])


def cpio_walk(data: bytes):
    pos = 0
    out = []
    while pos + 110 <= len(data) and data[pos:pos + 6] in (b"070701", b"070702"):
        namesize = int(data[pos + 94:pos + 102], 16)
        filesize = int(data[pos + 54:pos + 62], 16)
        name = data[pos + 110:pos + 110 + namesize - 1].decode(errors="replace")
        hdr = (110 + namesize + 3) & ~3
        out.append(dict(name=name, size=filesize, data_off=pos + hdr))
        pos = pos + hdr + ((filesize + 3) & ~3)
    return out


def elf_symbol(elf: bytes, want: str):
    e_shoff = struct.unpack_from("<Q", elf, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", elf, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", elf, 0x3C)[0]
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, typ, _flags, _addr, soff, size, link, _info, _align, entsize = struct.unpack_from(
            "<IIQQQQIIQQ", elf, off)
        sections.append(dict(type=typ, off=soff, size=size, link=link, entsize=entsize))
    for sec in sections:
        if sec["type"] != 2:
            continue
        strtab = sections[sec["link"]]
        entsize = sec["entsize"] or 24
        for k in range(sec["size"] // entsize):
            eo = sec["off"] + k * entsize
            st_name, _info, _other, st_shndx, st_value, _size = struct.unpack_from("<IBBHQQ", elf, eo)
            end = elf.index(b"\0", strtab["off"] + st_name)
            if elf[strtab["off"] + st_name:end].decode(errors="replace") == want:
                return sections[st_shndx]["off"] + st_value
    return None


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="Independently verify an init_boot image's KernelSU ko")
    ap.add_argument("image", help="boot / init_boot image to verify")
    ap.add_argument("--ko-name", default="kernelsu.ko", help="cpio member name of the module")
    ap.add_argument("--expect-cert-sha256", default=None, help="project cert SHA-256 (default: _tools/project-signer.json)")
    ap.add_argument("--expect-cert-size", type=int, default=None, help="project cert DER length (default: project-signer.json)")
    ap.add_argument("--expect-package", default=None, help="KSU_MANAGER_PACKAGE (default: manager/gradle.properties)")
    ap.add_argument("--expect-appid", type=int, default=None, help="expected ksu_manager_appid seed, e.g. 10627")
    ap.add_argument("--require-source-build", action="store_true",
                    help="fail (not just warn) when the package name is not compiled into the ko")
    ap.add_argument("--dump-ko", default=None, help="write the extracted ko here for inspection")
    args = ap.parse_args()

    sha = args.expect_cert_sha256
    size = args.expect_cert_size
    pkg = args.expect_package
    signer = os.path.join(here, "project-signer.json")
    if os.path.isfile(signer):
        # project-signer.json is written by PowerShell, which may prefix a UTF-8 BOM
        with open(signer, "r", encoding="utf-8-sig") as fh:
            facts = json.load(fh)
        sha = sha or facts.get("certSha256")
        size = size or facts.get("certDerSize")
    if not pkg:
        props = os.path.join(here, os.pardir, "manager", "gradle.properties")
        if os.path.isfile(props):
            for line in open(props, "r", encoding="utf-8"):
                if line.strip().startswith("KSU_PACKAGE_NAME="):
                    pkg = line.split("=", 1)[1].strip()
    if not sha or not size:
        print("FATAL: project cert SHA-256 / DER length unknown (pass --expect-cert-* or keep _tools/project-signer.json)")
        return 2

    try:
        img = open(args.image, "rb").read()
    except OSError as exc:
        print("FATAL: %s" % exc)
        return 2
    print("image       : %s (%d bytes, sha256 %s)" % (args.image, len(img), hashlib.sha256(img).hexdigest()))
    try:
        kernel_size, ramdisk_size, header_version, page = parse_boot(img)
        off = page + (((kernel_size + page - 1) // page) * page if kernel_size else 0)
        cpio = decode_ramdisk(img[off:off + ramdisk_size])
    except (ValueError, struct.error) as exc:
        print("FATAL: %s" % exc)
        return 2
    print("header      : v%d page=%d kernel_size=%d ramdisk_size=%d ramdisk@0x%x" % (
        header_version, page, kernel_size, ramdisk_size, off))
    print("cpio        : %d bytes sha256 %s" % (len(cpio), hashlib.sha256(cpio).hexdigest()))
    members = cpio_walk(cpio)
    names = [m["name"] for m in members]
    print("members     : %d %s" % (len(names), names[:12]))
    hits = [m for m in members if m["name"].split("/")[-1] == args.ko_name]
    if not hits:
        check(False, "cpio contains %r" % args.ko_name, "members=%s" % names)
        return 1
    m = hits[0]
    ko = cpio[m["data_off"]:m["data_off"] + m["size"]]
    print("ko          : %s size=%d sha256=%s" % (m["name"], len(ko), hashlib.sha256(ko).hexdigest()))
    if args.dump_ko:
        open(args.dump_ko, "wb").write(ko)
        print("            dumped -> %s" % args.dump_ko)

    check(ko[:4] == b"\x7fELF" and struct.unpack_from("<H", ko, 18)[0] == 183,
          "ko is an ELF64 aarch64 object", "magic=%s e_machine=%d" % (ko[:4].hex(), struct.unpack_from("<H", ko, 18)[0]))
    n_sha = ko.count(sha.encode())
    check(n_sha == 1, "ko carries the project cert SHA-256 exactly once", "%s x%d" % (sha, n_sha))
    check(ko.count(OFFICIAL_HASH.encode()) == 0, "official upstream cert hash absent", OFFICIAL_HASH)
    check(ko.count(DEBUG_HASH.encode()) == 0, "AOSP debug cert hash absent", DEBUG_HASH)

    imms = []
    for i in range(0, len(ko) - 4, 4):
        w = struct.unpack_from("<I", ko, i)[0]
        if (w & CMP_MASK) == CMP_SHAPE:
            imms.append(((w >> 10) & 0xFFF, i))
    got = [v for v, _ in imms]
    check(size in got, "a 'cmp w21, #imm' checks the project cert DER length",
          "want=%d found=%s" % (size, [hex(v) for v in got]))

    n_pkg = ko.count(pkg.encode()) if pkg else 0
    if n_pkg:
        check(True, "provenance=path A (from source: %s compiled in)" % pkg, "pkg-string x%d" % n_pkg)
    else:
        label = "provenance=path B (byte-patched: cert only, no compiled package name)"
        if args.require_source_build:
            check(False, label, "pkg-string x0, package=%r" % pkg)
        else:
            warn(label, "pkg-string x0, package=%r" % pkg)

    if args.expect_appid is not None:
        at = elf_symbol(ko, "ksu_manager_appid")
        if at is None:
            check(False, "ksu_manager_appid symbol present", "symbol not found")
        else:
            val = struct.unpack_from("<I", ko, at)[0]
            check(val == args.expect_appid, "manager appid seed",
                  "@0x%x = %d (want %d)" % (at, val, args.expect_appid))

    ok = all(results)
    print("IMAGE VERIFY: %s (%d check(s), %d failed)" % ("PASSED" if ok else "FAILED", len(results), results.count(False)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
