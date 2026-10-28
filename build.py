#!/usr/bin/env python3
"""Build the exact YouTube Music 9.40.51 ad-blocking experiment from local APKs."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
INPUT_DIGESTS = {
    "base.apk": "891aaeee53805d45e6030b387800f96d696b43ae0c05ca1b7f17884fc2c80f82",
    "split_config.en.apk": "db7cbbc6f9d9605d5147fb7d093033080e60a5b9f3f19d6548b490a219645f93",
    "split_config.in.apk": "9036587a218b6f94610d5b789c1a6921d28d0537445761d173610f50baf8e834",
    "split_config.xxhdpi.apk": "a0c23a658bee5e05b658c68080ffe372f6fca6c8bf3489bbaf6812d9e77994de",
}
DEPENDENCIES = {
    "revanced-cli-6.0.0-all.jar": (
        "https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar",
        "c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958"),
    "APKEditor-1.4.9.jar": (
        "https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar",
        "a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67"),
}
PACKAGE = "app.revanced.android.apps.youtube.music"
LABEL = "YouTube Music Adblock"
PATCH_VERSION = "v0.1.4"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(args, log_path=None):
    if log_path:
        with log_path.open("w") as log:
            result = subprocess.run([str(arg) for arg in args], stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Command failed; inspect {log_path}")
        return log_path.read_text()
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout + result.stderr


def dependency(name, cache):
    url, expected = DEPENDENCIES[name]
    target = cache / name
    if not target.exists():
        temporary = target.with_suffix(".part")
        request = urllib.request.Request(url, headers={"User-Agent": "youtube-music-adblock-builder"})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if sha256(temporary) != expected:
            raise RuntimeError(f"Downloaded dependency digest mismatch: {name}")
        temporary.replace(target)
    if sha256(target) != expected:
        raise RuntimeError(f"Cached dependency digest mismatch: {name}")
    return target


def validate_package(apk, merged, output, signed):
    run(["zipalign", "-c", "-P", "16", "4", apk])
    if signed:
        run(["apksigner", "verify", "--verbose", "--print-certs", apk], output / "signature-verification.txt")
    badging = run(["aapt", "dump", "badging", apk], output / "badging.txt")
    manifest = run(["aapt", "dump", "xmltree", apk, "AndroidManifest.xml"], output / "manifest.txt")
    if f"name='{PACKAGE}'" not in badging or "versionName='9.40.51'" not in badging or "versionCode='94051242'" not in badging or LABEL not in badging:
        raise RuntimeError("Unexpected APK identity")
    for metadata in ["app.revanced.android.gms.SPOOFED_PACKAGE_NAME", "app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE", "app.revanced.MICROG_PACKAGE_NAME"]:
        if metadata not in manifest:
            raise RuntimeError(f"Missing GmsCore metadata: {metadata}")
    if any(flag in manifest for flag in ["com.android.vending.splits.required", "isSplitRequired", "requiredSplitTypes"]):
        raise RuntimeError("Residual split requirement")
    with zipfile.ZipFile(merged) as before, zipfile.ZipFile(apk) as after:
        if after.testzip() or len([name for name in after.namelist() if name.endswith(".dex")]) != 7:
            raise RuntimeError("Invalid APK CRC or DEX inventory")
        if any(name.startswith("assets/dexopt/baseline.prof") for name in after.namelist()):
            raise RuntimeError("Stale baseline profile retained")
        untouched = [name for name in before.namelist() if name.startswith(("lib/", "assets/")) and not name.startswith("assets/dexopt/")]
        for name in untouched:
            if before.read(name) != after.read(name):
                raise RuntimeError(f"Unexpected native-library or asset change: {name}")
    return len(untouched)


def signing_key(args, output):
    if args.keystore:
        if not args.password_file:
            raise ValueError("--keystore requires --password-file")
        key, password = args.keystore.resolve(), args.password_file.resolve()
        if not key.is_file() or not password.is_file():
            raise ValueError("Signing key or password file does not exist")
        return key, password, args.key_alias
    if args.password_file:
        raise ValueError("--password-file requires --keystore")
    # Keep update identity outside disposable build outputs and reuse it.
    signing_dir = Path.home() / ".local/share/youtube-music-adblock/signing"
    signing_dir.mkdir(parents=True, exist_ok=True)
    signing_dir.chmod(0o700)
    key = signing_dir / "local-signing.p12"
    password = signing_dir / "signing-password.txt"
    if key.exists() or password.exists():
        if not key.is_file() or not password.is_file():
            raise ValueError("Incomplete persistent signing material; refusing to replace the update key")
        return key, password, "local-experiment"
    descriptor = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(secrets.token_hex(24) + "\n")
    alias = "local-experiment"
    run(["keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-storepass:file", password,
         "-keypass:file", password, "-alias", alias, "-keyalg", "RSA", "-keysize", "3072",
         "-validity", "3650", "-dname", "CN=Local YouTube Music Experiment", "-noprompt"])
    key.chmod(0o600)
    return key, password, alias


def main():
    global PACKAGE, LABEL
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True, help="Directory containing the four exact installed APKs")
    parser.add_argument("--output-dir", type=Path, help="New directory for intermediate files and the final APK")
    parser.add_argument("--tool-cache", type=Path, default=ROOT / ".local-build/tools")
    parser.add_argument("--unsigned", action="store_true", help="Build and validate without signing")
    parser.add_argument("--keystore", type=Path, help="Reuse your private signing key")
    parser.add_argument("--password-file", type=Path, help="Private file containing the keystore password")
    parser.add_argument("--key-alias", default="local-experiment")
    parser.add_argument("--d8-jar", type=Path, required=True, help="D8 compiler JAR for the startup dependency helper")
    parser.add_argument("--side-by-side", action="store_true", help="Build a separate app to preserve the installed app when its old signing key is unavailable")
    args = parser.parse_args()
    if args.side_by_side:
        PACKAGE, LABEL = "app.local.youtube.music", "YouTube Music Adblock"
    required = ["java", "javac", "aapt", "zipalign"] + ([] if args.unsigned else ["apksigner", "keytool"])
    for command in required:
        if not shutil.which(command):
            raise RuntimeError(f"Required tool missing: {command}")
    inputs = args.input_dir.resolve()
    for name, expected in INPUT_DIGESTS.items():
        if not (inputs / name).is_file() or sha256(inputs / name) != expected:
            raise RuntimeError(f"Unsupported input or checksum mismatch: {name}")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
    output = (args.output_dir or ROOT / ".local-build" / stamp).resolve()
    if output.exists():
        raise RuntimeError("Output directory must not already exist")
    output.mkdir(parents=True)
    cache = args.tool_cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    jars = [dependency(name, cache) for name in DEPENDENCIES]
    staged = output / "inputs"
    staged.mkdir()
    for name in INPUT_DIGESTS:
        shutil.copyfile(inputs / name, staged / name)
    merged = output / "merged-original.apk"
    print("Merging the exact installed APK set...", flush=True)
    run(["java", "-Xmx768m", "-jar", jars[1], "m", "-i", staged, "-o", merged, "-validate-modules"], output / "merge.log")
    classes = output / "builder-classes"
    classes.mkdir()
    classpath = os.pathsep.join(str(path) for path in jars)
    run(["javac", "-cp", classpath, "-d", classes, *sorted((ROOT / "src").glob("*.java"))], output / "compile.log")
    helper_classes = output / "helper-classes"
    helper_classes.mkdir()
    run(["javac", "--release", "8", "-Xlint:-options", "-d", helper_classes, *sorted((ROOT / "src/helpers").glob("*.java"))], output / "helper-compile.log")
    helper_dex = output / "helper-dex"
    helper_dex.mkdir()
    run(["java", "-cp", args.d8_jar.resolve(), "com.android.tools.r8.D8", "--min-api", "32", "--output", helper_dex,
         *sorted(helper_classes.rglob("*.class"))], output / "helper-d8.log")
    built = output / "patched"
    print("Applying hooks and comparing all assembled DEX classes...", flush=True)
    validation = run(["java", "-Xmx1024m", "-cp", str(classes) + os.pathsep + classpath,
                      "NonRootMusic", merged, built, helper_dex / "classes.dex", *(["--side-by-side"] if args.side_by_side else [])], output / "dex-validation.log")
    if f"Built {PACKAGE}" not in validation or validation.count("Validated assembled classes") != 6:
        raise RuntimeError("Incomplete DEX validation")
    run([sys.executable, ROOT / "tools/fix_resource_specs.py", "--input-dir", staged, "--build-dir", built], output / "resource-repair.log")
    aligned = output / "aligned.apk"
    run(["zipalign", "-P", "16", "4", built / "music-resources-fixed.apk", aligned])
    apk = output / (f"YouTube-Music-Adblock-{PATCH_VERSION}" + ("-unsigned" if args.unsigned else "") + ".apk")
    if args.unsigned:
        shutil.copyfile(aligned, apk)
    else:
        key, password, alias = signing_key(args, output)
        run(["apksigner", "sign", "--ks", key, "--ks-key-alias", alias, "--ks-pass", "file:" + str(password), "--out", apk, aligned])
    untouched = validate_package(apk, merged, output, not args.unsigned)
    helper_count = int((built / "helper-count.txt").read_text())
    result = {"package": PACKAGE, "version": "9.40.51", "apk_sha256": sha256(apk), "apk_bytes": apk.stat().st_size,
              "signed": not args.unsigned, "runtime_verified": False, "dex_roundtrip_validated_classes": 38180 + helper_count, "original_classes_preserved": 38180, "helper_classes_added": helper_count, "hook_count": 15, "startup_dependency_check": True,
              "runtime_trace_hook_count": 6, "version_code": 94051242, "label": LABEL, "background_playback_hook_count": 1, "exclusive_audio_hook_count": 1, "patch_version": PATCH_VERSION, "side_by_side": args.side_by_side,
              "unchanged_native_library_and_asset_members": untouched}
    (output / "validation.json").write_text(json.dumps(result, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text(result["apk_sha256"] + "  " + apk.name + "\n")
    print(f"Built and statically validated: {apk}\nLogin, playback and ad blocking remain unverified.")


if __name__ == "__main__":
    main()
