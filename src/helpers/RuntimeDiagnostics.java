package app.local.youtube.adblock;

import java.io.OutputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, device-local playback trace. Never receives account identifiers,
 * media identifiers, credentials, URLs, headers, or network response bodies.
 * Network observations are numeric playback HTTP/Cronet outcomes only. */
public final class RuntimeDiagnostics {
    private static final String BUILD = "0.1.4";
    private static final ArrayDeque<String> events = new ArrayDeque<String>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicBoolean queued = new AtomicBoolean();
    private static final long since = System.currentTimeMillis();
    private static Object context, callbacks, outputUri;
    private static ScheduledExecutorService worker;
    private static int activities, accountChanges, entitlementChanges, audioFocus;
    private static String background = "unobserved";
    private static int writes;
    private static int streamResponses, streamFailures, lastHttpStatus = -1;
    private static String lastNetworkError = "unobserved";

    private RuntimeDiagnostics() {}

    private static synchronized void event(String text) {
        if (events.size() >= 60) events.removeFirst();
        events.addLast((System.currentTimeMillis() - since) + "ms " + text);
        if (worker != null && !worker.isShutdown() && queued.compareAndSet(false, true)) {
            try { worker.schedule(() -> { queued.set(false); flush(); }, 100, TimeUnit.MILLISECONDS); }
            catch (Throwable ignored) { queued.set(false); }
        }
    }
    public static void accountChanged(Object ignored) {
        try { synchronized (RuntimeDiagnostics.class) { accountChanges++; } event("account_changed"); }
        catch (Throwable ignoredFailure) {}
    }
    public static void entitlementChanged(Object ignored) {
        try { synchronized (RuntimeDiagnostics.class) { entitlementChanges++; } event("entitlement_refreshed"); }
        catch (Throwable ignoredFailure) {}
    }
    public static void audioFocusChanged(int change) {
        try { synchronized (RuntimeDiagnostics.class) { audioFocus = change; } event("audio_focus=" + change); }
        catch (Throwable ignoredFailure) {}
    }
    public static void streamResponse(Object info) {
        try {
            int status = ((Number) Class.forName("org.chromium.net.UrlResponseInfo")
                .getMethod("getHttpStatusCode").invoke(info)).intValue();
            boolean changed;
            synchronized (RuntimeDiagnostics.class) {
                streamResponses++; changed = lastHttpStatus != status; lastHttpStatus = status;
            }
            if (changed) event("stream_http=" + status);
        } catch (Throwable ignored) {}
    }
    public static void streamFailed(Object info, Object error) {
        try {
            if (info != null) streamResponse(info);
            String codes = "cronet_code=unavailable internal_code=unavailable";
            try {
                Class<?> type = Class.forName("org.chromium.net.NetworkException");
                if (type.isInstance(error)) codes = "cronet_code=" + type.getMethod("getErrorCode").invoke(error)
                    + " internal_code=" + type.getMethod("getCronetInternalErrorCode").invoke(error);
            } catch (Throwable ignored) {}
            synchronized (RuntimeDiagnostics.class) { streamFailures++; lastNetworkError = codes; }
            event("stream_failed " + codes);
        } catch (Throwable ignored) {}
    }
    public static void backgroundCheck(Object modality, Object response, boolean stopRequired) {
        try {
            String enabled = "unavailable", metadata = "unavailable", live = "unavailable";
            try { enabled = String.valueOf(Class.forName("abjc").getField("h").getBoolean(modality)); }
            catch (Throwable ignored) {}
            if (response == null) { metadata = "false"; live = "false"; }
            else {
                try {
                    Class<?> type = Class.forName("wei");
                    metadata = String.valueOf(type.getMethod("t").invoke(response) != null);
                    live = String.valueOf(type.getMethod("V").invoke(response));
                } catch (Throwable ignored) {}
            }
            String next = "stop_required=" + stopRequired + " modality_enabled=" + enabled
                + " response_present=" + (response != null) + " metadata_present=" + metadata + " live=" + live;
            synchronized (RuntimeDiagnostics.class) {
                if (next.equals(background)) return;
                background = next;
            }
            event("background_check " + next);
        } catch (Throwable ignored) {}
    }
    public static void install(Object activity) {
        if (!started.compareAndSet(false, true)) return;
        try {
            Class<?> contextType = Class.forName("android.content.Context");
            context = contextType.getMethod("getApplicationContext").invoke(activity);
            if (context == null) return;
            worker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "MusicPlaybackReport"); thread.setDaemon(true); return thread;
            });
            Class<?> listener = Class.forName("android.app.Application$ActivityLifecycleCallbacks");
            callbacks = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return args != null && proxy == args[0];
                if (name.equals("toString")) return "MusicAdblockLifecycle";
                if (name.equals("onActivityStarted")) {
                    synchronized (RuntimeDiagnostics.class) { activities++; }
                    event("activity_started");
                } else if (name.equals("onActivityStopped")) {
                    synchronized (RuntimeDiagnostics.class) { activities = Math.max(0, activities - 1); }
                    event("activity_stopped");
                }
                return null;
            });
            Class.forName("android.app.Application").getMethod("registerActivityLifecycleCallbacks", listener).invoke(context, callbacks);
            event("report_started");
            worker.scheduleWithFixedDelay(() -> flush(), 1, 5, TimeUnit.SECONDS);
            worker.schedule(() -> { event("report_window_finished"); flush(); worker.shutdown(); }, 10, TimeUnit.MINUTES);
        } catch (Throwable ignored) {}
    }
    private static String powerFlag(String service, String type, String method) {
        try {
            Object manager = Class.forName("android.content.Context").getMethod("getSystemService", String.class).invoke(context, service);
            return String.valueOf(Class.forName(type).getMethod(method).invoke(manager));
        } catch (Throwable ignored) { return "unavailable"; }
    }
    private static String batteryExemption() {
        try {
            Class<?> type = Class.forName("android.content.Context");
            Object manager = type.getMethod("getSystemService", String.class).invoke(context, "power");
            String pkg = (String) type.getMethod("getPackageName").invoke(context);
            return String.valueOf(Class.forName("android.os.PowerManager")
                .getMethod("isIgnoringBatteryOptimizations", String.class).invoke(manager, pkg));
        } catch (Throwable ignored) { return "unavailable"; }
    }
    static synchronized String report() {
        StringBuilder text = new StringBuilder("app=YouTube Music Adblock\nbuild=" + BUILD
            + "\naccount_changes=" + accountChanges + "\nentitlement_changes=" + entitlementChanges
            + "\nstarted_activities=" + activities + "\nlast_audio_focus=" + audioFocus
            + "\nlast_background_check=" + background
            + "\nstream_responses=" + streamResponses + "\nstream_failures=" + streamFailures
            + "\nlast_stream_http=" + lastHttpStatus + "\nlast_network_error=" + lastNetworkError + "\n");
        text.append("power_save=").append(powerFlag("power", "android.os.PowerManager", "isPowerSaveMode")).append('\n');
        text.append("screen_interactive=").append(powerFlag("power", "android.os.PowerManager", "isInteractive")).append('\n');
        text.append("screen_locked=").append(powerFlag("keyguard", "android.app.KeyguardManager", "isKeyguardLocked")).append('\n');
        text.append("background_restricted=").append(powerFlag("activity", "android.app.ActivityManager", "isBackgroundRestricted")).append('\n');
        text.append("battery_optimization_exempt=").append(batteryExemption()).append('\n');
        text.append("network_outcomes_collected=true\nnetwork_headers_urls_bodies_collected=false\naccount_or_media_identifiers_collected=false\nevents:\n");
        for (String event : events) text.append(event).append('\n');
        return text.toString();
    }
    private static void flush() {
        if (context == null || writes++ >= 125) return;
        try {
            byte[] bytes = report().getBytes(StandardCharsets.UTF_8);
            Class<?> contextType = Class.forName("android.content.Context");
            java.io.File files = (java.io.File) contextType.getMethod("getFilesDir").invoke(context);
            java.nio.file.Files.write(new java.io.File(files, "music-adblock-runtime.txt").toPath(), bytes);
            Object resolver = contextType.getMethod("getContentResolver").invoke(context);
            Class<?> resolverType = Class.forName("android.content.ContentResolver");
            Class<?> uri = Class.forName("android.net.Uri"), valuesType = Class.forName("android.content.ContentValues");
            if (outputUri == null) {
                Object values = valuesType.getConstructor().newInstance();
                Method put = valuesType.getMethod("put", String.class, String.class);
                put.invoke(values, "_display_name", "Music-Adblock-0.1.4-" + since + ".txt");
                put.invoke(values, "mime_type", "text/plain");
                put.invoke(values, "relative_path", "Download/YouTube-Music-Adblock-Diagnostics/");
                Object collection = Class.forName("android.provider.MediaStore$Downloads").getField("EXTERNAL_CONTENT_URI").get(null);
                outputUri = resolverType.getMethod("insert", uri, valuesType).invoke(resolver, collection, values);
            }
            if (outputUri != null) {
                try (OutputStream stream = (OutputStream) resolverType.getMethod("openOutputStream", uri, String.class).invoke(resolver, outputUri, "wt")) {
                    if (stream != null) stream.write(bytes);
                }
            }
        } catch (Throwable ignored) {}
    }
}
