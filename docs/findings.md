# Static findings for YouTube Music 9.40.51

Target: `com.google.android.apps.youtube.music`, version code `94051240`, arm64. Base APK SHA-256: `891aaeee53805d45e6030b387800f96d696b43ae0c05ca1b7f17884fc2c80f82`.

The installed set contained the base APK and English, Indonesian and xxhdpi splits. Inventory found six DEX files, 38,180 classes, 24 native libraries, 22 activities, 27 services, 25 receivers and five providers.

REA's portable artifact inventory succeeded. Its Android JADX provider reported that subprocess ownership is unsupported on Android/arm64. Follow-up declarations and bytecode references were inspected locally with `aapt`, a focused DEX reader, dexlib2 and baksmali. These are static observations, not runtime traces.

## Candidate ad hook

The upstream identifying string and `move-result-object`, `invoke-virtual`, `iget-object` sequence matched once in `classes2.dex`, `Lacbd;->by(Lacey;)V`. The call at DEX offset `0x350fc2` targets `Lacec;->d(Z)V`.

The target stores its boolean argument into `Lacec;->d:Z`, invokes `Lacec;->b()V`, and returns. The experiment inserts `const/4 p1, 0x0` before those existing operations. This is an inferred ad-suppression hook based on the upstream patch; actual ad suppression has not been observed.

## Non-root adaptation

Exact methods used:

| Hook | Method |
| --- | --- |
| Availability | `Lkku;->b(Landroid/content/Context;I)I` |
| Service check | `Lkku;->d(Landroid/content/Context;I)V` |
| Package identity | `Lqci;->a()Z` |
| Cast context | `Lxhv;->a(Llnh;)V` |
| File provider | `Laty;->d(Landroid/content/Context;Ljava/lang/String;)Leff;` |
| Ad state | `Lacec;->d(Z)V` |

The compatibility builder targets `app.revanced.android.apps.youtube.music`, routes selected service constants to `app.revanced.android.gms`, and adds the metadata expected by GmsCore. The upstream original-signature value was corroborated with the installed APK's v3.0 signer; the installed APK also carries a rotated v3.1 signer.

## Resource repair

APKEditor retained sparse resource entry IDs during the split merge, but shortened 12 type-spec arrays incorrectly. For example, icon ID `0x7f110029` remained in the type data while the associated spec declared three entries instead of 42. The repair restores per-ID flags and counts by combining the originals. The final resource table passed `aapt` package/icon resolution. Other APK members are unchanged by that repair.

## Coverage

All six assembled DEX files matched their intended transformed definitions across 38,180 classes. APK signature, ZIP CRC, alignment and manifest identity checks passed. Native libraries and non-profile assets were compared with the merged original.

Unknowns: login, sustained playback, actual ad suppression, casting, Android Auto, reflection/dynamic loading, downloaded configuration, native implementation details and server-side enforcement. No account-risk estimate or compliance guarantee is made. Read the README's experimental-status section before testing.
