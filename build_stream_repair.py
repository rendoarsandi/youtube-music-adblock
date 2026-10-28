#!/usr/bin/env python3
"""Build the maintained stream repair for the original merged Music 9.40.51 APK."""
import argparse
import json
import struct
from pathlib import Path
import subprocess
import zipfile

import build
from tools import fix_resource_specs as specs

build.DEPENDENCIES.update({
    "morphe-desktop-1.19.1-all.jar": (
        "https://github.com/MorpheApp/morphe-desktop/releases/download/v1.19.1/morphe-desktop-1.19.1-all.jar",
        "a51472252cd63b2eac635dbb5a154ff918bfc15268da89679feb3d760230c573"),
    "patches-4.4.0-dev.3.mpp": (
        "https://github.com/anddea/revanced-patches/releases/download/v4.4.0-dev.3/patches-4.4.0-dev.3.mpp",
        "04c02b35bd48e8160fbc30519113659242073c3958dee954f60b22bc582eae57"),
})
PATCHES = ["Spoof video streams", "GmsCore support", "Hide ads", "Remove background playback restrictions"]


def repair_resources(source, target):
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(target, "w") as after:
        resources, repairs = specs.repair(before.read("resources.arsc"))
        for info in before.infolist():
            after.writestr(info, resources if info.filename == "resources.arsc" else before.read(info.filename))
    return repairs


def collect_added_entries(data):
    """Derive added-entry config flags from ResTable_type, keyed by actual ID.

    Layout and CONFIG_* values follow AOSP ResTable_config::diff:
    https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/ResourceTypes.cpp
    Existing original type-spec/public flags remain in specs.SPECS.
    """
    for package_pos, kind, header, size in specs.chunks(data, struct.unpack_from("<H", data, 2)[0], len(data)):
        if kind != 0x200:
            continue
        package = struct.unpack_from("<I", data, package_pos + 8)[0]
        for pos, kind, header, size in specs.chunks(data, package_pos + header, package_pos + size):
            if kind != 0x201:
                continue
            type_id, encoding = data[pos + 8:pos + 10]
            count = struct.unpack_from("<I", data, pos + 12)[0]
            config = data[pos + 20:pos + header].ljust(64, b"\0")
            mask = 0
            for offset, length, bits in [(4, 2, 1), (6, 2, 2), (8, 4, 4), (12, 1, 0x80),
                    (13, 1, 8), (14, 2, 0x100), (16, 1, 0x10), (17, 1, 0x40),
                    (20, 4, 0x200), (24, 4, 0x400), (29, 1, 0x1000), (30, 2, 0x2000),
                    (32, 4, 0x200), (36, 12, 4), (53, 8, 4)]:
                if any(config[offset:offset + length]):
                    mask |= bits
            for offset, field_mask, bits in [(18, 15, 0x20), (28, 0xC0, 0x4000),
                    (28, 0x3F, 0x800), (48, 3, 0x8000), (49, 15, 0x10000)]:
                if config[offset] & field_mask:
                    mask |= bits
            if any(config[61:]):
                raise ValueError("Unsupported extended resource configuration")
            if encoding & 1:
                ids = [struct.unpack_from("<H", data, pos + header + i * 4)[0] for i in range(count)]
            elif encoding & 2:
                ids = [i for i in range(count) if struct.unpack_from("<H", data, pos + header + i * 2)[0] != 0xFFFF]
            else:
                ids = [i for i in range(count) if struct.unpack_from("<I", data, pos + header + i * 4)[0] != 0xFFFFFFFF]
            flags = specs.SPECS[(package, type_id)]
            if ids:
                flags.extend([0] * max(0, max(ids) + 1 - len(flags)))
                for entry_id in ids:
                    flags[entry_id] |= mask


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-apk", required=True, type=Path)
    parser.add_argument("--input-dir", required=True, type=Path, help="Original APK splits, used to preserve sparse resource IDs")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--tool-cache", type=Path, default=build.ROOT / ".local-build/tools")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--password-file", type=Path)
    parser.add_argument("--key-alias", default="local-experiment")
    parser.add_argument("--patched-apk", type=Path, help="Reuse a completed patch run with its sibling patch-result.json")
    args = parser.parse_args()
    original = args.original_apk.resolve()
    # APKEditor's intermediate sparse type specs need repairing before delivery.
    # The package/version line remains readable in this intermediate artifact.
    identity = subprocess.run(["aapt", "dump", "badging", str(original)], capture_output=True, text=True).stdout
    if "name='com.google.android.apps.youtube.music'" not in identity or "versionName='9.40.51'" not in identity:
        raise ValueError("Requires the unmodified merged Music 9.40.51 APK")
    for name, digest in build.INPUT_DIGESTS.items():
        if build.sha256(args.input_dir / name) != digest:
            raise ValueError("Original split checksum mismatch: " + name)
    with zipfile.ZipFile(args.input_dir / "base.apk") as base, zipfile.ZipFile(original) as merged:
        dex_names = [name for name in base.namelist() if name.endswith(".dex")]
        if set(dex_names) != {name for name in merged.namelist() if name.endswith(".dex")}:
            raise ValueError("Merged input has an unexpected DEX inventory")
        if any(base.read(name) != merged.read(name) for name in dex_names):
            raise ValueError("Merged input code differs from the pinned original base APK")
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    for name in build.INPUT_DIGESTS:
        with zipfile.ZipFile(args.input_dir / name) as archive:
            specs.collect(archive.read("resources.arsc"))
    fixed_original = out / "original-resources-fixed.apk"
    repair_resources(original, fixed_original)
    build.run(["aapt", "dump", "badging", fixed_original], out / "input-badging.txt")
    args.tool_cache.mkdir(parents=True, exist_ok=True)
    editor = build.dependency("APKEditor-1.4.9.jar", args.tool_cache)
    bundle = build.dependency("patches-4.4.0-dev.3.mpp", args.tool_cache)
    cli = build.dependency("morphe-desktop-1.19.1-all.jar", args.tool_cache)
    patched = args.patched_apk.resolve() if args.patched_apk else out / "music-patched-unsigned.apk"
    report_path = patched.parent / "patch-result.json"
    if not args.patched_apk:
        command = ["java", "-Xmx1024m", "-jar", cli, "patch", "--exclusive", "--unsigned", "--bytecode-mode=FULL", "-p", bundle,
                   "-e", PATCHES[0], "-e", PATCHES[1], "-O", "packageNameYouTubeMusic=" + build.PACKAGE,
                   "-O", "gmsCoreVendorGroupId=app.revanced", "-e", PATCHES[2], "-e", PATCHES[3], fixed_original,
                   "-o", patched, "-r", report_path, "-t", out / "scratch"]
        build.run(command, out / "patch.log")
    report = json.loads(report_path.read_text())
    if (not report.get("success") or report.get("failedPatches")
            or report.get("packageName") != "com.google.android.apps.youtube.music"
            or report.get("packageVersion") != "9.40.51"
            or set(report.get("appliedPatches", [])) != set(PATCHES)):
        raise RuntimeError("Maintained patch run did not complete all requested patches")
    (out / "patch-result.json").write_text(json.dumps(report, indent=2) + "\n")
    build.run(["javac", "-cp", editor, "-d", out, build.ROOT / "tools/FinalizeMusicApk.java"])
    packaged = out / "packaged.apk"
    build.run(["java", "-Xmx512m", "-cp", str(out) + ":" + str(editor), "FinalizeMusicApk", patched, packaged])
    with zipfile.ZipFile(patched) as before, zipfile.ZipFile(packaged) as after:
        for name in before.namelist():
            if name.endswith(".dex") and before.read(name) != after.read(name):
                raise RuntimeError("Packaging changed compiled playback code")
    # Union original per-ID configuration flags with newly added patch entries.
    # This preserves both sparse launcher-icon IDs and extension resource IDs.
    for source in [patched, packaged]:
        with zipfile.ZipFile(source) as archive:
            specs.collect(archive.read("resources.arsc"))
            collect_added_entries(archive.read("resources.arsc"))
    fixed = out / "resources-fixed.apk"
    repairs = repair_resources(packaged, fixed)
    (out / "resource-repair.json").write_text(json.dumps(repairs, indent=2) + "\n")
    aligned = out / "aligned.apk"
    build.run(["zipalign", "-P", "16", "-f", "4", fixed, aligned])
    key, password, alias = build.signing_key(args, out)
    apk = out / "YouTube-Music-Adblock-v0.1.4.apk"
    build.run(["apksigner", "sign", "--ks", key, "--ks-key-alias", alias, "--ks-pass", "file:" + str(password), "--out", apk, aligned])
    build.run(["apksigner", "verify", "--verbose", "--print-certs", apk], out / "signature.txt")
    build.run(["zipalign", "-c", "-P", "16", "4", apk])
    badging = build.run(["aapt", "dump", "badging", apk], out / "badging.txt")
    if "name='" + build.PACKAGE + "'" not in badging or "versionCode='94051242'" not in badging or build.LABEL not in badging:
        raise RuntimeError("Unexpected final package identity")
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(apk) as after:
        if after.testzip():
            raise RuntimeError("APK archive CRC failure")
        for name in before.namelist():
            if name.startswith(("lib/", "assets/")) and not name.startswith("assets/dexopt/") and before.read(name) != after.read(name):
                raise RuntimeError("Unexpected original native library or asset change: " + name)
    metadata = {"package": build.PACKAGE, "label": build.LABEL, "version": "9.40.51", "patch_version": "v0.1.4",
                "patch_bundle": "anddea/v4.4.0-dev.3", "patches": PATCHES, "apk_sha256": build.sha256(apk),
                "apk_bytes": apk.stat().st_size, "live_playback_verified": False}
    (out / "validation.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (out / "SHA256SUMS.txt").write_text(build.sha256(apk) + "  " + apk.name + "\n")
    print(apk)


if __name__ == "__main__":
    main()
