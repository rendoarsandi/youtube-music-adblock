# YouTube Music Adblock

Experimental client-side ad-blocking patch and non-root build tools for one exact **YouTube Music 9.40.51 arm64** APK set.

The code forces a candidate ad-state input to false and adapts the app to use ReVanced GmsCore under a separate package name. **The user reported successful launch after installing and opening GmsCore once. Login, sustained playback and actual ad suppression remain unverified.** This is not an official Google, YouTube or ReVanced release.

## Download and install

The APK is attached to the [experimental prerelease](https://github.com/rendoarsandi/youtube-music-adblock/releases/tag/v0.1.1-startup-check), rather than stored in Git. It is approximately 61.7 MB (58.8 MiB).

1. Install the standard, non-Huawei APK from the [official ReVanced GmsCore v0.3.13.2.250932 release](https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.2.250932). **Open its microG screen once before launching this app.** Allow it to run in the background; Google sign-in is separate.
2. Install `YT-Music-Ad-Test-9.40.51.apk` from this project's prerelease.
3. Open **YT Music (Ad test)**. Sign in yourself through GmsCore if needed. GmsCore's screen may be labeled **microG**.
4. Check song startup, playback for several minutes, and whether ads appear. Report the exact error if installation or playback fails.

The test app uses `app.revanced.android.apps.youtube.music`; it can be installed alongside the official app. ReVanced GmsCore runs alongside the phone's Google Play Services. The build tools do not install, uninstall or launch apps.

Release APK SHA-256:

```text
4a0a6531ae7892a70036f308e3f84f4db0ec463a7512fb4fdbe29bf6cd649ea8
```

If the app immediately closes, first open **ReVanced GmsCore / microG** once and retry. The user observed this setup step resolving startup for both the Music and YouTube tests. That observation does not verify playback or ad blocking.

## Experimental status and risks

- The checked upstream patches list versions through 8.40.54. The adaptation here targets the exact 9.40.51 build identified in [the findings](docs/findings.md); other versions and APK sets are rejected by checksum.
- Static assembly and package validation do not prove functioning ad blocking or service compatibility. App crashes, failed login, playback interruptions or server-side changes are possible.
- Cast-context initialization returns early for compatibility. Casting and Android Auto are unverified. No ReVanced settings screen is included. A startup GmsCore check detects a missing/disabled dependency and opens installation/settings guidance. It briefly acquires and closes the public service provider to start GmsCore, without reading values/accounts. This does not guarantee permissions, background readiness or sign-in.
- The APK retains Google's compiled code and assets. This is a modified official application, not a replacement written from scratch.
- [YouTube's terms](https://www.youtube.com/static?template=terms) restrict software modification and reverse engineering, subject to stated exceptions. Compliance, undetectability and absence of account restrictions are not guaranteed.
- Google may restrict one service or [disable an entire Google Account](https://support.google.com/accounts/answer/40695?hl=en). A separate account is not a guaranteed shield for other accounts. [YouTube's termination policy](https://support.google.com/youtube/answer/2802168?hl=en) also prohibits bypassing channel termination with another channel.

Do not post passwords, authentication codes, tokens or personal account data in issues.

## What the code changes

| Area | Change |
| --- | --- |
| Ads | Inserts `const/4 p1, 0x0` into the candidate `Lacec;->d(Z)V` setter, preserving its field write and callback. |
| Service compatibility | Redirects selected Google-service package/authority constants to ReVanced GmsCore and adapts availability checks. |
| Package identity | Changes the install package, provider authorities and required GmsCore metadata. |
| File sharing | Adds the upstream-style file-provider compatibility guard. |
| Resources | Merges the four installed APKs and restores sparse type-spec counts and flags from the original set. |

The upstream fingerprint matched once. All seven startup/compatibility/ad hooks matched once. Assembly round-trip comparison covered 38,181 classes, preserving all 38,180 original classes. Annotation ordering and equivalent default static values are normalized; array element order is retained. APK signatures, ZIP CRCs, package metadata and alignment passed validation. The build also compared 291 native-library/asset members with the merged original. See [evidence](evidence/validation.json).

## Build locally

Requirements: Python 3.11+, JDK 17+, `aapt`, `zipalign` with `-P 16` support, and `apksigner`/`keytool` for signed builds, plus a D8 compiler JAR for the startup helper. The original build and the portable rebuild were checked in Android/Termux; other host configurations are not verified.

Supply your own installed APKs in a private input directory:

```text
base.apk
split_config.en.apk
split_config.in.apk
split_config.xxhdpi.apk
```

Their exact SHA-256 values are pinned in `build.py`. Do not put APKs or signing keys in the Git repository.

```bash
python build.py --input-dir /absolute/path/to/your-apks --d8-jar /path/to/d8.jar
```

The script downloads two pinned official tool JARs if absent and verifies their SHA-256 digests. Build outputs, dependencies and generated private signing keys stay under the ignored `.local-build/` directory. Preserve your private key if you want to sign compatible updates; a new key cannot update an existing installation signed with a different one.

On Termux, D8 is normally at `$PREFIX/share/java/d8.jar`.

To build without a signing key:

```bash
python build.py --input-dir /absolute/path/to/your-apks --d8-jar /path/to/d8.jar --unsigned
```

To reuse your own signing key:

```bash
python build.py --input-dir /absolute/path/to/your-apks --d8-jar /path/to/d8.jar \
  --keystore /private/path/signing.p12 \
  --password-file /private/path/password.txt \
  --key-alias local-experiment
```

The included Kotlin file is an additional patch-source example. The standalone build uses `src/NonRootMusic.java`; the Kotlin file is not compiled by `build.py`.

## Source layout

- `src/NonRootMusic.java`: guarded exact-version DEX and manifest adaptation.
- `src/helpers/GmsStartup.java`: startup dependency check and setup guidance.
- `src/HideAds94051Patch.kt`: experimental ReVanced patch-source example.
- `build.py`: portable local build and validation entry point.
- `tools/fix_resource_specs.py`: original-resource provenance repair.
- `tools/inspect_dex.py`: read-only DEX fingerprint/reference inspection; pass your private APK directory as its argument.
- `docs/findings.md`: observations, inferences and unresolved runtime questions.
- `evidence/`: bounded static results and published APK checksum.

## Attribution and license

Patch/build code is licensed under [GPL-3.0](LICENSE). Compatibility logic and the ad fingerprint are adapted from public ReVanced patch sources. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This source-code license does not license Google's application code, branding or assets. External tools and GmsCore retain their respective licenses. GmsCore is linked from its official release and is not republished here.
