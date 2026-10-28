import com.reandroid.apk.ApkModule;
import java.io.File;

/** Local packaging only; preserves upstream extension credits and all DEX code. */
public final class FinalizeMusicApk {
    public static void main(String[] args) throws Exception {
        try (ApkModule module = ApkModule.loadApkFile(new File(args[0]))) {
            var manifest = module.getAndroidManifest();
            if (!"app.revanced.android.apps.youtube.music".equals(manifest.getPackageName())
                    || !"9.40.51".equals(manifest.getVersionName()))
                throw new IllegalStateException("Unexpected patched Music identity");
            manifest.setVersionCode(94051242);
            manifest.setApplicationLabel("YouTube Music Adblock");
            manifest.getMainActivity().getOrCreateAndroidAttribute("label", 0x01010001)
                .setValueAsString("YouTube Music Adblock");
            module.setApkSignatureBlock(null);
            module.removeInputSource("assets/dexopt/baseline.prof");
            module.removeInputSource("assets/dexopt/baseline.profm");
            module.refreshManifest();
            module.writeApk(new File(args[1]));
        }
    }
}
