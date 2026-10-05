#!/usr/bin/env python3
"""Patch an Android boot/init_boot image so KernelSU crowns the right manager app.

Why this tool exists
--------------------
The prebuilt kernelsu.ko bundled in this fork (userspace/ksud/bin/<arch>/*_kernelsu.ko)
is compiled WITHOUT -DKSU_MANAGER_PACKAGE, so kernel/manager/apk_sign.c
is_manager_apk() only compares the APK signing certificate (DER size + sha256).
The manager APK shipped by this project is signed with the PUBLIC AOSP debug key,
so ANY app signed with that same key can be crowned first by the /data/app walk
in track_throne() -> search_manager() -> is_manager_apk(). When that happens
ksu_manager_appid points at the other app and the KernelSU manager app shows
"未安装" with no root.

Seeding ksu_manager_appid (kernel/manager/throne_tracker.c:15) inside the ko
.data section makes the driver crown the intended manager at load time and the
appid is preserved forever: track_throne() only searches when the crowned uid
is absent from /data/system/packages.list.

Runtime module reload is NOT an option: rmmod kernelsu panics this kernel.
The fix must live in the flashed image, which is what this tool produces.

Usage
-----
  python patch-init-boot.py --base init_boot_a.img --out init_boot_a_fixed.img \
      --manager-appid 10626
  python patch-init-boot.py --base in.img --out out.img --dump-ko ko.bin
  python patch-init-boot.py --base in.img --out out.img \
      --signer-sha256 <64 hex> --signer-size 744

What it does
------------
  1. parses the boot image header (header v4 layout used by init_boot)
  2. decompresses the LZ4-legacy ramdisk into a cpio archive
  3. locates the kernelsu.ko cpio member
  4. applies the requested byte patches to that ko
     * --manager-appid : ELF .symtab lookup of ksu_manager_appid, 4 bytes LE
     * --signer-sha256 / --signer-size : the two compile-time constants used by
       check_v2_signature() (64 hex chars + the cmp imm on aarch64)
  5. rewrites the ramdisk with an all-literals LZ4 legacy block (no LZ4 encoder
     dependency; the stream layout is byte-identical to what lz4 -l emits, the
     only difference is the compression ratio)
  6. updates ramdisk_size and pads the image to the original file size
  7. re-reads the produced image and verifies that the ONLY differences in the
     decoded cpio are the intended bytes
"""
import argparse
import hashlib
import struct
import sys

BOOT_MAGIC = b"ANDROID!"
LZ4_LEGACY_MAGIC = bytes.fromhex("02214c18")
# aarch64: cmp w21, #imm  (0x7100001f | imm12<<10 | rn<<5 | rd) -- the two
# constants used by check_v2_signature() size checks in apk_sign.c
CMP_PATTERN = bytes.fromhex("bfee0c71")  # cmp w21, #0x33b (827)
CMP_REPLACE = bytes.fromhex("bfa20b71")  # cmp w21, #0x2e8 (744)
CMP_OFF_DEFAULT = 0xA864
HASH_OFF_DEFAULT = 0x1B92A


def lz4_block_decode(src: bytes) -> bytes:
    """Self-terminating raw LZ4 block decoder (no length prefix)."""
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


def lz4_block_encode_literals(data: bytes) -> bytes:
    """Encode data as ONE valid raw LZ4 block consisting only of literals.

    LZ4 block rules honoured: the block ends right after the last literal run,
    the last 5 bytes are literals, and there is no trailing match, so every
    conforming decoder (including the Linux kernel's) accepts the stream.
    """
    n = len(data)
    out = bytearray()
    token = 0xF0 if n >= 15 else (n << 4)
    out.append(token)
    if n >= 15:
        rem = n - 15
        while rem >= 255:
            out.append(255)
            rem -= 255
        out.append(rem)
    out += data
    return bytes(out)


def parse_boot(img: bytes):
    if img[:8] != BOOT_MAGIC:
        raise ValueError("not an Android boot image (magic %r)" % img[:8])
    kernel_size = struct.unpack_from("<I", img, 8)[0]
    ramdisk_size = struct.unpack_from("<I", img, 12)[0]
    header_size = struct.unpack_from("<I", img, 20)[0]
    header_version = struct.unpack_from("<I", img, 40)[0]
    page = 4096 if header_version >= 3 else struct.unpack_from("<I", img, 36)[0]
    return dict(kernel_size=kernel_size, ramdisk_size=ramdisk_size,
                header_size=header_size, header_version=header_version, page=page)


def ramdisk_region(img: bytes, meta: dict):
    """Return (offset, bytes) of the ramdisk. init_boot has kernel_size == 0."""
    off = meta["page"]
    if meta["kernel_size"]:
        off += (meta["kernel_size"] + meta["page"] - 1) // meta["page"] * meta["page"]
    return off, img[off:off + meta["ramdisk_size"]]


def decode_ramdisk(rd: bytes) -> bytes:
    if rd[:4] == LZ4_LEGACY_MAGIC:
        return lz4_block_decode(rd[8:])
    if rd[:6] in (b"070701", b"070702"):
        return rd
    raise ValueError("unsupported ramdisk compression (magic %r)" % rd[:4])


def encode_ramdisk(cpio: bytes) -> bytes:
    blk = lz4_block_encode_literals(cpio)
    return LZ4_LEGACY_MAGIC + struct.pack("<I", len(blk)) + blk


def cpio_members(data: bytes):
    pos = 0
    members = []
    while pos + 110 <= len(data) and data[pos:pos + 6] in (b"070701", b"070702"):
        def f(o):
            return int(data[pos + o:pos + o + 8], 16)
        namesize = f(94)
        filesize = f(54)
        name = data[pos + 110:pos + 110 + namesize - 1].decode(errors="replace")
        hdr = (110 + namesize + 3) & ~3
        members.append(dict(name=name, size=filesize, data_off=pos + hdr,
                            hdr_off=pos, hdr_len=hdr))
        pos = pos + hdr + ((filesize + 3) & ~3)
    return members


def find_symbol(elf: bytes, want: str):
    """Locate a symbol in an ELF64 object, returning (name, section_idx, value, size)."""
    e_shoff = struct.unpack_from("<Q", elf, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", elf, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", elf, 0x3C)[0]
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, typ, flags, addr, soff, size, link, info, align, entsize = struct.unpack_from("<IIQQQQIIQQ", elf, off)
        sections.append(dict(name_off=name, type=typ, off=soff, size=size, link=link, entsize=entsize))
    for si, sec in enumerate(sections):
        if sec["type"] != 2:  # SHT_SYMTAB
            continue
        strtab = sections[sec["link"]]
        entsize = sec["entsize"] or 24
        count = sec["size"] // entsize
        for k in range(count):
            eo = sec["off"] + k * entsize
            st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", elf, eo)
            end = elf.index(b"\0", strtab["off"] + st_name)
            nm = elf[strtab["off"] + st_name:end].decode(errors="replace")
            if nm == want:
                return dict(section_idx=st_shndx, value=st_value, size=st_size,
                            target_sec=sections[st_shndx])
    return None


def patch_manager_appid(ko: bytearray, appid: int) -> bool:
    """Seed ksu_manager_appid in a ko's .data, so the driver crowns that uid at load time."""
    sym = find_symbol(bytes(ko), "ksu_manager_appid")
    if not sym:
        print("FATAL: symbol ksu_manager_appid not found (is this a KernelSU ko?)")
        return False
    target = sym["target_sec"]["off"] + sym["value"]
    if not (0 <= target <= len(ko) - 4):
        print("FATAL: computed file offset 0x%x outside the ko" % target)
        return False
    before = struct.unpack_from("<I", ko, target)[0]
    struct.pack_into("<I", ko, target, appid)
    print("manager-appid: ksu_manager_appid @section=%d value=0x%x -> file offset 0x%x: 0x%08x -> %d" % (
        sym["section_idx"], sym["value"], target, before, appid))
    return True


def patch_signer(ko: bytearray, sha256hex, size) -> bool:
    """Patch the built-in v2 signer cert expectation (64-hex literal + cmp w21,#imm size)."""
    import re
    h = sha256hex
    if h:
        if len(h) != 64 or not re.fullmatch(r"[0-9a-f]{64}", h.lower()):
            print("FATAL: --signer-sha256 must be 64 lowercase hex chars")
            return False
        hits = [m.start() for m in re.finditer(rb"[0-9a-f]{64}", bytes(ko))]
        old = [bytes(ko)[i:i + 64].decode() for i in hits]
        if h.lower() in old:
            print("signer-hash : already %s at 0x%x (no change)" % (h.lower(), hits[old.index(h.lower())]))
        elif len(hits) == 1:
            print("signer-hash : %s -> %s at file offset 0x%x" % (old[0], h.lower(), hits[0]))
            ko[hits[0]:hits[0] + 64] = h.lower().encode()
        else:
            print("FATAL: expected exactly one 64-hex literal in the ko, found %d: %s" % (
                len(hits), [(hex(i), v[:8]) for i, v in zip(hits, old)]))
            return False
    if size is not None:
        pos = bytes(ko).find(CMP_PATTERN)
        if pos < 0:
            pos = CMP_OFF_DEFAULT
            if bytes(ko)[pos:pos + 4] == CMP_REPLACE:
                print("signer-size : already patched at 0x%x" % pos)
                pos = -1
            else:
                print("FATAL: cmp size pattern not found at the documented offset 0x%x" % pos)
                return False
        if pos >= 0:
            if not (0 < size < 4096):
                print("FATAL: --signer-size %d out of range" % size)
                return False
            word = 0x71000000 | (size << 10) | (21 << 5) | 21
            enc = struct.pack("<I", word)
            print("signer-size : cmp w21,#0x%x at file offset 0x%x: %s -> %s" % (
                size, pos, bytes(ko)[pos:pos + 4].hex(), enc.hex()))
            ko[pos:pos + 4] = enc
    return True


def patch_ko_only(args) -> int:
    """Mode --ko-in/--ko-out: seed the appid / signer constants in a bare ko (no boot image).

    Use it to re-seed a bundled LKM asset, e.g.
    userspace/ksud/bin/aarch64/android15-6.6_kernelsu.ko, so a future libksud.so rebuild
    does not ship an unseeded module again.
    """
    data = bytearray(open(args.ko_in, "rb").read())
    before = bytes(data)
    print("ko-input    : %s (%d bytes, sha256 %s)" % (args.ko_in, len(data), hashlib.sha256(before).hexdigest()))
    if args.manager_appid is not None and not patch_manager_appid(data, args.manager_appid):
        return 2
    if (args.signer_sha256 or args.signer_size is not None) and not patch_signer(data, args.signer_sha256, args.signer_size):
        return 2
    diff = [i for i in range(len(before)) if before[i] != data[i]]
    print("ko diff     : %d byte(s) %s" % (len(diff), [hex(i) for i in diff[:16]]))
    if args.dry_run or not diff:
        print("ko-output   : skipped (dry-run or no change)")
        return 0
    if not args.ko_out:
        print("FATAL: --ko-out is required when patching a bare ko")
        return 2
    open(args.ko_out, "wb").write(bytes(data))
    print("ko-output   : %s (%d bytes, sha256 %s)" % (args.ko_out, len(data), hashlib.sha256(bytes(data)).hexdigest()))
    return 0


def main():
    ap = argparse.ArgumentParser(description="Patch a boot image or a bare ko so KernelSU crowns the intended manager")
    ap.add_argument("--base", default=None, help="input boot/init_boot image (e.g. the currently flashed one)")
    ap.add_argument("--out", default=None, help="output image path")
    ap.add_argument("--manager-appid", type=int, default=None,
                    help="uid of the manager app (uid %% 100000), e.g. 10626 for me.weishu.kernelsu.hide")
    ap.add_argument("--signer-sha256", default=None, help="64 hex chars: sha256 of the manager APK v2 signer cert DER")
    ap.add_argument("--signer-size", type=int, default=None, help="DER length of that cert (e.g. 744)")
    ap.add_argument("--ko-name", default="kernelsu.ko", help="cpio member name of the kernel module")
    ap.add_argument("--replace-ko", default=None, help="replace the ko in the ramdisk with this file")
    ap.add_argument("--dump-ko", default=None, help="write the (pre-patch) ko out for inspection")
    ap.add_argument("--ko-in", default=None, help="patch this bare ko instead of a boot image (with --ko-out)")
    ap.add_argument("--ko-out", default=None, help="output path for --ko-in mode")
    ap.add_argument("--dry-run", action="store_true", help="verify only, do not write the output image")
    args = ap.parse_args()

    if args.ko_in:
        return patch_ko_only(args)
    if not args.base or not args.out:
        print("FATAL: --base and --out are required (or use --ko-in with --ko-out)")
        return 2

    img = open(args.base, "rb").read()
    meta = parse_boot(img)
    off, rd = ramdisk_region(img, meta)
    print("base        : %s (%d bytes, sha256 %s)" % (args.base, len(img), hashlib.sha256(img).hexdigest()))
    print("header      : v%d header_size=%d kernel_size=%d ramdisk_size=%d page=%d" % (
        meta["header_version"], meta["header_size"], meta["kernel_size"], meta["ramdisk_size"], meta["page"]))
    cpio = decode_ramdisk(rd)
    print("ramdisk     : %d bytes compressed at 0x%x -> %d bytes cpio (%s)" % (
        len(rd), off, len(cpio), hashlib.sha256(cpio).hexdigest()))

    members = cpio_members(cpio)
    ko_members = [m for m in members if m["name"].split("/")[-1] == args.ko_name]
    if not ko_members:
        print("FATAL: no cpio member named %r (members: %s)" % (args.ko_name, [m["name"] for m in members]))
        return 2
    ko_mem = ko_members[0]
    ko = bytearray(cpio[ko_mem["data_off"]:ko_mem["data_off"] + ko_mem["size"]])
    print("ko          : %s @cpio+0x%x size=%d sha256=%s" % (
        ko_mem["name"], ko_mem["data_off"], len(ko), hashlib.sha256(bytes(ko)).hexdigest()))
    if args.dump_ko:
        open(args.dump_ko, "wb").write(bytes(ko))
        print("            dumped -> %s" % args.dump_ko)

    original_ko = bytes(ko)
    if args.replace_ko:
        new_ko = open(args.replace_ko, "rb").read()
        if len(new_ko) != len(ko):
            print("FATAL: replacement ko size %d != %d" % (len(new_ko), len(ko)))
            return 2
        ko = bytearray(new_ko)
        print("replace-ko  : %s (%d bytes)" % (args.replace_ko, len(new_ko)))

    if args.manager_appid is not None and not patch_manager_appid(ko, args.manager_appid):
        return 2

    if (args.signer_sha256 or args.signer_size is not None) and not             patch_signer(ko, args.signer_sha256, args.signer_size):
        return 2
    new_cpio = bytearray(cpio)
    new_cpio[ko_mem["data_off"]:ko_mem["data_off"] + ko_mem["size"]] = ko
    new_cpio = bytes(new_cpio)
    diff = [k for k in range(len(cpio)) if new_cpio[k] != cpio[k]]
    print("cpio diff   : %d byte(s) %s" % (len(diff), [hex(d) for d in diff[:16]]))
    if not args.dry_run and not diff:
        print("WARN: nothing to patch; not writing output")

    new_rd = encode_ramdisk(new_cpio)
    new_img = bytearray(img[:meta["page"]])
    struct.pack_into("<I", new_img, 12, len(new_rd))
    new_img += new_rd
    if len(new_img) > len(img):
        print("FATAL: patched image (%d) exceeds the partition image size (%d)" % (len(new_img), len(img)))
        return 2
    new_img += b"\0" * (len(img) - len(new_img))
    new_img = bytes(new_img)

    if not args.dry_run:
        open(args.out, "wb").write(new_img)
        print("output      : %s (%d bytes, sha256 %s)" % (args.out, len(new_img), hashlib.sha256(new_img).hexdigest()))

    # ---- verification -----------------------------------------------------
    chk = parse_boot(new_img)
    _, chk_rd = ramdisk_region(new_img, chk)
    chk_cpio = decode_ramdisk(chk_rd)
    vdiff = [k for k in range(len(chk_cpio)) if chk_cpio[k] != cpio[k]]
    chk_ko = chk_cpio[ko_mem["data_off"]:ko_mem["data_off"] + ko_mem["size"]]
    print("verify      : ramdisk_size=%d cpio=%d total diff=%d" % (chk["ramdisk_size"], len(chk_cpio), len(vdiff)))
    print("verify      : ko sha256=%s (unchanged ko: %s)" % (
        hashlib.sha256(chk_ko).hexdigest(), chk_ko == original_ko))
    hdr_diff = [k for k in range(meta["page"]) if new_img[k] != img[k]]
    print("verify      : header byte diffs %s" % [hex(k) for k in hdr_diff])
    ok = (len(vdiff) == len(diff) and len(chk_cpio) == len(cpio)
          and hdr_diff and set(hdr_diff) <= {12, 13, 14, 15})
    print("verify      : %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
