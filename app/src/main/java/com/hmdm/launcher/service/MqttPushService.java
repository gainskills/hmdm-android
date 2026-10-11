package com.hmdm.launcher.service;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.WorkManager;
import com.hmdm.launcher.*;
import com.hmdm.launcher.helper.*;
import com.hmdm.launcher.json.*;
import com.hmdm.launcher.mqtt.*;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.util.*;
import com.hmdm.launcher.worker.PushNotificationProcessor;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.SocketFactory;
import javax.net.ssl.*;

/** Sole owner of the MQTT transport. No MQTT lifecycle work runs on the UI thread. */
public class MqttPushService extends Service {
    public static final String CHANNEL_ID = "com.hmdm.launcher.push";
    private static volatile MqttPushService instance;
    private static long lastStartWarning;
    private static final String EXPLICIT_STOP = "mqttExplicitlyStopped";
    // Shared across sticky service recreation: a replacement waits for its predecessor to retire.
    private static final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong epoch = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Long> connections = new ArrayDeque<>();
    private volatile boolean destroyed;
    private MqttAsyncClient client;
    private long clientEpoch;
    private volatile RetiringSocketFactory sockets;
    private ScheduledFuture<?> retry;
    private ConnectivityManager connectivity;
    private volatile Network network;
    private String endpoint, deviceId;
    private volatile String identity;
    private volatile int keepalive;
    private int failures;
    private boolean locked, wasReady, recoveryPending;
    private String lastError;
    private long lastErrorTime;

    public static boolean isMqtt(ServerConfig config) {
        return config != null && (ServerConfig.PUSH_OPTIONS_MQTT_ALARM.equals(config.getPushOptions())
                || ServerConfig.PUSH_OPTIONS_MQTT_WORKER.equals(config.getPushOptions()));
    }

    /** Existing services receive commands in process; only eligible callers start an FGS. */
    public static void update(Context context) {
        if (isExplicitlyStopped(context)) return;
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (!isMqtt(config)) {
            stop(context);
            if (config == null || !ServerConfig.PUSH_OPTIONS_POLLING.equals(config.getPushOptions()))
                context.stopService(new Intent(context, PushLongPollingService.class));
            return;
        }
        context.stopService(new Intent(context, PushLongPollingService.class));
        MqttPushService running = instance;
        if (running != null && !running.destroyed) {
            running.invalidateChangedSettings(context, config);
            running.submit(running::refresh);
            return;
        }
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        if (Build.VERSION.SDK_INT >= 31 && !Utils.isDeviceOwner(context)
                && process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            warnStart(context);
            return; // Existing periodic HTTP configuration worker remains available.
        }
        try { context.startForegroundService(new Intent(context, MqttPushService.class)); }
        catch (RuntimeException e) { warnStart(context); }
    }

    private void invalidateChangedSettings(Context context, ServerConfig config) {
        SettingsHelper settings = SettingsHelper.getInstance(context);
        boolean changed;
        try {
            String resolved = MqttEndpoint.resolve(config.getMqttUrl(), settings.getBaseUrl(), BuildConfig.MQTT_PORT, BuildConfig.MQTT_OVER_TLS);
            changed = !(resolved + "\n" + settings.getDeviceId()).equals(identity) || configuredKeepalive(config) != keepalive;
        } catch (IllegalArgumentException e) { changed = true; }
        if (changed) {
            epoch.incrementAndGet();
            RetiringSocketFactory retiring = sockets;
            if (retiring != null) retiring.retire();
        }
    }

    private static int configuredKeepalive(ServerConfig config) {
        if (config.getKeepaliveTime() != null && config.getKeepaliveTime() > 65535)
            throw new IllegalArgumentException("MQTT keepalive exceeds protocol limit");
        return ServerConfig.PUSH_OPTIONS_MQTT_WORKER.equals(config.getPushOptions()) ? 900
                : config.getKeepaliveTime() != null && config.getKeepaliveTime() >= 30 ? config.getKeepaliveTime() : 300;
    }

    private static synchronized void warnStart(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (lastStartWarning == 0 || now - lastStartWarning > 300000) {
            lastStartWarning = now;
            RemoteLogger.log(context, Const.LOG_WARN,
                    "MQTT service start deferred: open launcher to resume push; periodic HTTP configuration sync remains active");
        }
    }

    public static boolean isExplicitlyStopped(Context context) {
        return context.getApplicationContext().getSharedPreferences(Const.PREFERENCES, MODE_PRIVATE).getBoolean(EXPLICIT_STOP, false);
    }

    public static void resume(Context context) {
        if (isExplicitlyStopped(context)) context.getApplicationContext().getSharedPreferences(Const.PREFERENCES, MODE_PRIVATE)
                .edit().putBoolean(EXPLICIT_STOP, false).apply();
        update(context);
    }

    public static void stopExplicitly(Context context) {
        context.getApplicationContext().getSharedPreferences(Const.PREFERENCES, MODE_PRIVATE)
                .edit().putBoolean(EXPLICIT_STOP, true).apply();
        stop(context);
    }

    public static void stop(Context context) {
        MqttPushService running = instance;
        if (running != null) running.epoch.incrementAndGet();
        context.stopService(new Intent(context, MqttPushService.class));
    }

    public static void healthCheck() {
        MqttPushService running = instance;
        if (running != null) running.submit(running::refresh);
    }

    public static void resetLoopProtection() {
        MqttPushService running = instance;
        if (running != null) running.submit(() -> {
            running.locked = false;
            running.connections.clear();
            running.refresh();
        });
    }

    private void submit(Runnable task) {
        if (!destroyed) {
            try { lifecycle.execute(task); } catch (RejectedExecutionException ignored) {}
        }
    }

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) { stopExplicitly(c); }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network available) {
            submit(() -> {
                if (!available.equals(network)) {
                    network = available;
                    recoveryPending |= wasReady;
                    retire();
                    refresh();
                }
            });
        }
        public void onLost(Network lost) {
            if (lost.equals(network)) {
                RetiringSocketFactory retiring = sockets;
                if (retiring != null) retiring.retire();
            }
            submit(() -> {
                if (lost.equals(network)) {
                    network = null;
                    recoveryPending |= wasReady;
                    retire();
                }
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Push notifications", NotificationManager.IMPORTANCE_LOW));
        Utils.startStableForegroundService(this, 113, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(ProUtils.getAppName(this)).setContentText(getString(R.string.mqtt_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service).setOngoing(true).build());
        LocalBroadcastManager.getInstance(this).registerReceiver(stopReceiver, new IntentFilter(Const.ACTION_SERVICE_STOP));
        WorkManager work = WorkManager.getInstance(this);
        work.cancelUniqueWork("com.hmdm.launcher.WORK_TAG_MQTT");
        work.cancelUniqueWork("com.hmdm.launcher.WORK_TAG_MQTT_RECONNECT");
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        network = connectivity.getActiveNetwork();
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        submit(this::refresh); // Includes null sticky restart intent; never consumes endpoint snapshots.
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true;
        if (instance == this) instance = null;
        epoch.incrementAndGet();
        RetiringSocketFactory retiring = sockets;
        if (retiring != null) retiring.retire();
        connectivity.unregisterNetworkCallback(networkCallback);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stopReceiver);
        lifecycle.execute(this::retire);

        super.onDestroy();
    }

    private void refresh() {
        if (destroyed) return;
        SettingsHelper settings = SettingsHelper.getInstance(this);
        ServerConfig config = settings.getConfig();
        if (isExplicitlyStopped(this) || !isMqtt(config)) { retire(); stopSelf(); return; }
        try {
            String nextEndpoint = MqttEndpoint.resolve(config.getMqttUrl(), settings.getBaseUrl(), BuildConfig.MQTT_PORT, BuildConfig.MQTT_OVER_TLS);
            String nextId = settings.getDeviceId();
            if (nextId == null || nextId.trim().isEmpty() || nextId.contains("#") || nextId.contains("+"))
                throw new IllegalArgumentException("Invalid MQTT device ID/topic");
            int nextKeepalive = configuredKeepalive(config);
            String nextIdentity = nextEndpoint + "\n" + nextId;
            boolean identityChanged = !nextIdentity.equals(identity);
            boolean changed = identityChanged || nextKeepalive != keepalive;
            // An update may have closed this transport even when settings revert A -> B -> A.
            if (changed || (client != null && clientEpoch != epoch.get())) {
                retire();
                if (identityChanged) {
                    locked = false;
                    connections.clear();
                }
                failures = 0;
                identity = nextIdentity;
                endpoint = nextEndpoint;
                deviceId = nextId;
                keepalive = nextKeepalive;
            }
            if (client != null && !client.isConnected()) {
                retire();
            }
            if (!locked && network != null && client == null && retry == null) connect();
        } catch (IllegalArgumentException e) {
            retire();
            identity = null;
            diagnostic("STAGE_CONFIG", "invalid endpoint, device ID or keepalive; administrator correction required");
        }
    }

    private void connect() {
        if (destroyed || locked || network == null) return;
        final long activeEpoch = epoch.incrementAndGet();
        String stage = "STAGE_CONNECT";
        try {
            sockets = new RetiringSocketFactory();
            stage = "STAGE_PERSISTENCE";
            SettingsHelper settings = SettingsHelper.getInstance(this);
            String compatibilityEndpoint = MqttEndpoint.resolve(null, settings.getBaseUrl(), BuildConfig.MQTT_PORT, BuildConfig.MQTT_OVER_TLS);
            java.io.File legacyRoot = null;
            String legacyEndpoint = compatibilityEndpoint;
            if (compatibilityEndpoint.equals(endpoint)) {
                legacyRoot = getExternalFilesDir("MqttConnection");
                if (legacyRoot == null) legacyRoot = getDir("MqttConnection", MODE_PRIVATE);
                legacyEndpoint = (BuildConfig.MQTT_OVER_TLS ? "ssl://" : "tcp://")
                        + new java.net.URL(settings.getBaseUrl()).getHost() + ":" + BuildConfig.MQTT_PORT;
            }
            java.io.File persistenceDirectory = MqttSessionPersistence.directory(getDir("mqtt_persistence", MODE_PRIVATE),
                    legacyRoot, legacyEndpoint, endpoint, deviceId);
            clientEpoch = activeEpoch;
            client = new MqttAsyncClient(endpoint, deviceId, new MqttDefaultFilePersistence(persistenceDirectory.getAbsolutePath()));
            stage = "STAGE_CONNECT";
            final String expectedTopic = deviceId;
            client.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) {
                    submit(() -> {
                        if (activeEpoch != epoch.get()) return;
                        recoveryPending |= wasReady;
                        diagnostic(classify(cause, "STAGE_CONNECT"), "connection lost");
                        retire();
                        scheduleRetry();
                    });
                }
                public void deliveryComplete(IMqttDeliveryToken token) {}
                public void messageArrived(String topic, MqttMessage message) {
                    if (!expectedTopic.equals(topic)) {
                        submit(() -> {
                            if (activeEpoch == epoch.get()) diagnostic("STAGE_MESSAGE", "unexpected push topic rejected");
                        });
                        return;
                    }
                    byte[] raw = message.getPayload();
                    byte[] payload = raw != null ? raw.clone() : new byte[0];
                    main.post(() -> {
                        if (destroyed || activeEpoch != epoch.get()) return;
                        try {
                            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                            PushNotificationProcessor.process(new PushMessageJson(json.getString("messageType"), json.optJSONObject("payload")), MqttPushService.this);
                        } catch (Exception e) { diagnostic("STAGE_MESSAGE", "invalid push payload"); }
                    });
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(false);
            options.setCleanSession(false);
            options.setConnectionTimeout(20);
            options.setKeepAliveInterval(keepalive);
            options.setUserName("hmdm");
            options.setPassword(CryptoHelper.getSHA1String("hmdm" + BuildConfig.REQUEST_SIGNATURE).toCharArray());
            options.setHttpsHostnameVerificationEnabled(true);
            SocketFactory factory = SocketFactory.getDefault();
            if (endpoint.startsWith("ssl://")) {
                factory = SSLSocketFactory.getDefault();
                if (BuildConfig.DEBUG && BuildConfig.TRUST_ANY_CERTIFICATE) {
                    SSLContext tls = SSLContext.getInstance("TLS");
                    tls.init(null, new TrustManager[]{new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    }}, null);
                    factory = tls.getSocketFactory();
                    options.setHttpsHostnameVerificationEnabled(false);
                }
            }
            options.setSocketFactory(sockets.wrap(factory));
            client.connect(options).waitForCompletion(30000);
            if (destroyed || activeEpoch != epoch.get()) { retire(); return; }
            long now = SystemClock.elapsedRealtime();
            while (!connections.isEmpty() && connections.peekFirst() < now - 60000) connections.removeFirst();
            connections.addLast(now);
            if (connections.size() > 15) {
                locked = true;
                diagnostic("STAGE_LOOP", "more than 15 connections/minute; check duplicate device IDs, then explicitly sync");
                retire();
                return;
            }
            stage = "STAGE_SUBSCRIPTION";
            IMqttToken subscription = client.subscribe(deviceId, 2);
            subscription.waitForCompletion(20000);
            int[] granted = subscription.getGrantedQos();
            if (granted == null || granted.length != 1 || granted[0] == 128) throw new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED);
            if (destroyed || activeEpoch != epoch.get()) { retire(); return; }
            boolean refreshConfig = recoveryPending;
            recoveryPending = false;
            wasReady = true;
            failures = 0;
            lastError = null;
            RemoteLogger.log(this, Const.LOG_INFO, "MQTT ready: " + endpoint);
            if (refreshConfig) main.post(() -> { if (!destroyed) ConfigUpdater.forceConfigUpdate(this); });
        } catch (Exception e) {
            recoveryPending = true;
            diagnostic(classify(e, stage), "connection failed; retry with backoff");
            retire();
            scheduleRetry();
        }
    }

    private void retire() {
        epoch.incrementAndGet();
        if (retry != null) { retry.cancel(false); retry = null; }
        if (sockets != null) { sockets.retire(); sockets = null; }
        if (client != null) {
            MqttAsyncClient old = client;
            client = null;
            old.setCallback(null);
            try { old.disconnectForcibly(0, 1000, false); } catch (MqttException ignored) {}
            try { old.close(true); } catch (MqttException ignored) {}
        }
    }

    private void scheduleRetry() {
        if (destroyed || locked || network == null || retry != null) return;
        long base = Math.min(300000L, 5000L << Math.min(failures++, 6));
        long delay = Math.min(300000L, (long) (base * (0.8 + Math.random() * 0.4)));
        retry = lifecycle.schedule(() -> { retry = null; refresh(); }, delay, TimeUnit.MILLISECONDS);
    }

    private static String classify(Throwable cause, String stage) {
        if ("STAGE_SUBSCRIPTION".equals(stage)) return stage;
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException) return "STAGE_DNS";
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) return "STAGE_TLS";
            if (t instanceof MqttException) {
                int reason = ((MqttException)t).getReasonCode();
                if (reason == 4 || reason == 5) return "STAGE_AUTH";
            }
        }
        return stage;
    }

    private synchronized void diagnostic(String stage, String detail) {
        long now = SystemClock.elapsedRealtime();
        String key = stage + detail;
        if (!key.equals(lastError) || now - lastErrorTime >= 300000) {
            lastError = key;
            lastErrorTime = now;
            RemoteLogger.log(this, Const.LOG_WARN, "MQTT " + stage + " "
                    + ("STAGE_CONFIG".equals(stage) ? "" : endpoint + " ") + detail);
        }
    }
}
