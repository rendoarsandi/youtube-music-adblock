Experimental ad-blocking build for YouTube Music **9.40.51 arm64**.

Download `YT-Music-Ad-Test-9.40.51.apk` below. Install the standard, non-Huawei APK from the [official ReVanced GmsCore release](https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.2.250932) first, then install and open **YT Music (Ad test)**. GmsCore may display the name microG. Sign in yourself if needed.

The modified app uses a separate package and can be installed alongside the original app. It contains the official app's existing compiled implementation/resources plus the experimental client-side patch and required service compatibility changes.

**What passed:** six-DEX assembly comparison covering 38,180 classes, APK signature validation, ZIP CRC checks, resource/package metadata validation, native-library/ZIP alignment, and comparison of 291 native-library/asset members.

**What remains unverified:** successful login, sustained playback, actual ad blocking, casting and Android Auto. Cast-context initialization returns early; no ReVanced settings or startup GmsCore checker is included.

This is a prerelease, not an official Google, YouTube or ReVanced release. Modification may conflict with service terms; compatibility and absence of account restrictions are not guaranteed. See the [README](https://github.com/rendoarsandi/youtube-music-adblock#experimental-status-and-risks) for details.

APK SHA-256:

```text
f2a1e7895451eededdf08353c7eaeec9f422eb46e9cc9bd9c4904cc38fb58778
```

If testing fails, report the exact error and whether it happened during installation, sign-in or playback. Do not include credentials or authentication codes.
