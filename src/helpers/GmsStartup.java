package app.local.youtube.adblock;

import java.lang.reflect.*;

/** Startup dependency check. No account queries or update-check networking. */
public final class GmsStartup {
    private static final String PACKAGE = "app.revanced.android.gms";
    public static void check(Object activity) {
        try {
            Class<?> context = Class.forName("android.content.Context");
            Object manager = context.getMethod("getPackageManager").invoke(activity);
            Object info;
            try {
                info = Class.forName("android.content.pm.PackageManager").getMethod("getPackageInfo", String.class, int.class).invoke(manager, PACKAGE, 0);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() != null && failure.getCause().getClass().getName().equals("android.content.pm.PackageManager$NameNotFoundException")) {
                    explainAndOpen(activity, "Install ReVanced GmsCore (microG), open it once, then reopen this app.",
                        "android.intent.action.VIEW", "https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.2.250932");
                }
                return;
            }
            Object app = Class.forName("android.content.pm.PackageInfo").getField("applicationInfo").get(info);
            if (app != null && !Class.forName("android.content.pm.ApplicationInfo").getField("enabled").getBoolean(app)) {
                explainAndOpen(activity, "Enable ReVanced GmsCore (microG), open it once, then reopen this app.",
                    "android.settings.APPLICATION_DETAILS_SETTINGS", "package:" + PACKAGE);
                return;
            }
            // Acquiring and closing the provider starts the dependency without reading values/accounts.
            try {
                Object resolver = context.getMethod("getContentResolver").invoke(activity);
                Class<?> uri = Class.forName("android.net.Uri");
                Object path = uri.getMethod("parse", String.class).invoke(null, "content://app.revanced.android.gsf.gservices/prefix");
                Object client = Class.forName("android.content.ContentResolver").getMethod("acquireContentProviderClient", uri).invoke(resolver, path);
                if (client != null) Class.forName("android.content.ContentProviderClient").getMethod("close").invoke(client);
                else toast(activity, "Open ReVanced GmsCore (microG) once, allow it to run, then retry.");
            } catch (Throwable ignored) {
                toast(activity, "If startup fails, open ReVanced GmsCore (microG) once and retry.");
            }
        } catch (Throwable ignored) { /* Unknown check failures must not become another startup crash. */ }
    }
    private static void toast(Object activity, String text) throws Exception {
        Class<?> type = Class.forName("android.widget.Toast");
        Object toast = type.getMethod("makeText", Class.forName("android.content.Context"), CharSequence.class, int.class).invoke(null, activity, text, 1);
        type.getMethod("show").invoke(toast);
    }
    private static void explainAndOpen(Object activity, String message, String action, String address) {
        try { toast(activity, message); } catch (Throwable ignored) { }
        try {
            Class<?> uri = Class.forName("android.net.Uri"), intent = Class.forName("android.content.Intent");
            Object target = intent.getConstructor(String.class, uri).newInstance(action, uri.getMethod("parse", String.class).invoke(null, address));
            Class.forName("android.content.Context").getMethod("startActivity", intent).invoke(activity, target);
        } catch (Throwable ignored) { }
        // Upstream uses this after opening guidance to avoid continuing with a missing dependency.
        System.exit(0);
    }
}
