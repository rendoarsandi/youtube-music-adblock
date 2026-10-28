import java.lang.reflect.*;

/** Android resource loading and verifier checks; no accounts, playback or network. */
public final class VerifyStreamPackage {
    public static void main(String[] args) {
        try { run(args); } catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        Class<?> assetType = Class.forName("android.content.res.AssetManager");
        Object assets = assetType.getConstructor().newInstance();
        int cookie = (Integer) assetType.getMethod("addAssetPath", String.class).invoke(assets, args[0]);
        if (cookie == 0) throw new AssertionError("Android rejected APK assets");
        Class<?> parserType = Class.forName("android.content.res.XmlResourceParser");
        Object xml = assetType.getMethod("openXmlResourceParser", int.class, String.class).invoke(assets, cookie, "AndroidManifest.xml");
        Method next = parserType.getMethod("next"), getName = parserType.getMethod("getName");
        int icon = 0;
        while ((Integer) next.invoke(xml) != 1) {
            if ("application".equals(getName.invoke(xml))) {
                icon = (Integer) parserType.getMethod("getAttributeResourceValue", String.class, String.class, int.class)
                    .invoke(xml, "http://schemas.android.com/apk/res/android", "icon", 0);
                break;
            }
        }
        parserType.getMethod("close").invoke(xml);
        if (icon == 0) throw new AssertionError("No application icon reference in compiled manifest");
        Class<?> metricsType = Class.forName("android.util.DisplayMetrics"), configType = Class.forName("android.content.res.Configuration");
        Object metrics = metricsType.getConstructor().newInstance();metricsType.getMethod("setToDefaults").invoke(metrics);
        Class<?> resourceType = Class.forName("android.content.res.Resources");
        Object resources = resourceType.getConstructor(assetType, metricsType, configType)
            .newInstance(assets, metrics, configType.getConstructor().newInstance());
        // Resolve its actual compiled reference through Android, not our table parser.
        String name = (String) resourceType.getMethod("getResourceName", int.class).invoke(resources, icon);
        if (name.isEmpty()) throw new AssertionError("Launcher icon is unresolved");
        Method identify = resourceType.getMethod("getIdentifier", String.class, String.class, String.class);
        int setting = 0;
        for (String pkg : new String[]{"app.revanced.android.apps.youtube.music", "com.google.android.apps.youtube.music"}) {
            setting = (Integer) identify.invoke(resources, "morphe_spoof_video_streams_title", "string", pkg);
            if (setting != 0) break;
        }
        if (setting == 0) throw new AssertionError("Stream repair setting resource is missing");
        String title = (String) resourceType.getMethod("getString", int.class).invoke(resources, setting);
        if (title.isEmpty()) throw new AssertionError("Stream repair setting does not resolve");
        System.out.println("PASS: Android resolves the real launcher icon and added stream settings");
        ClassLoader loader = (ClassLoader) Class.forName("dalvik.system.DexClassLoader")
            .getConstructor(String.class, String.class, String.class, ClassLoader.class)
            .newInstance(args[0], args[1], null, VerifyStreamPackage.class.getClassLoader());
        for (String type : new String[]{"app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch",
                "app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest",
                "app.morphe.extension.music.patches.spoof.SpoofVideoStreamsPatch"})
            Class.forName(type, false, loader).getDeclaredMethods();
        System.out.println("PASS: ART accepts maintained stream helpers against the actual APK dependencies");
    }
}
