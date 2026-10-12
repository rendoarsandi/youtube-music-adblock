# Attribution

The experimental ad fingerprint, GmsCore package/authority routing, compatibility checks and file-provider guard are based on the public ReVanced patch sources under GPL-3.0:

- [Music ad patch and fingerprint](https://gitlab.com/ReVanced/revanced-patches/-/tree/main/patches/src/main/kotlin/app/revanced/patches/music/ad/video)
- [Shared GmsCore adaptation](https://gitlab.com/ReVanced/revanced-patches/-/tree/main/patches/src/main/kotlin/app/revanced/patches/shared/misc/gms)
- [Music GmsCore adaptation](https://gitlab.com/ReVanced/revanced-patches/-/tree/main/patches/src/main/kotlin/app/revanced/patches/music/misc/gms)
- [Music file-provider adaptation](https://gitlab.com/ReVanced/revanced-patches/-/tree/main/patches/src/main/kotlin/app/revanced/patches/music/misc/fileprovider)

Local modifications: exact-version signature/fingerprint guards; standalone Java/dexlib2 implementation; fixed false ad-state input; portable build orchestration; sparse resource type-spec repair; static assembly/package comparisons; no extension settings; a local startup dependency checker is now included.

The build uses official [ReVanced CLI 6.0.0](https://github.com/ReVanced/revanced-cli/releases/tag/v6.0.0), including its dexlib2/baksmali libraries, and [APKEditor 1.4.9](https://github.com/REAndroid/APKEditor/releases/tag/V1.4.9), including ARSCLib. Their binaries are downloaded locally and are not committed to this repository. Preserve their respective upstream licenses when redistributing those tools.

[ReVanced GmsCore](https://github.com/ReVanced/GmsCore) is based on microG and provides the account/service compatibility dependency. Download it from its official releases. It is not bundled with this project's APK or Git history.

The published modified APK contains Google's existing compiled YouTube Music implementation and resources. Those contents are not covered by this repository's source license. No Google original APK, app assets or decompiled app classes are committed to Git.

The local `GmsStartup` helper follows upstream GmsCore installation checking, public-provider acquisition and guidance followed by graceful exit. It uses Java reflection to avoid packaging framework stubs, omits account queries, update-network requests and battery permission dialogs. Source reference: [GmsCoreSupport.java](https://gitlab.com/ReVanced/revanced-patches/-/blob/015fe10a23ea5b919a3fda6e7da2c176517ae75d/extensions/shared/library/src/main/java/app/revanced/extension/shared/GmsCoreSupport.java).
