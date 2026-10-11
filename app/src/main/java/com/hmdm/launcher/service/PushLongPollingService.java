package com.hmdm.launcher.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.PushMessage;
import com.hmdm.launcher.json.PushResponse;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;
import com.hmdm.launcher.worker.PushNotificationProcessor;



import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import retrofit2.Response;

public class PushLongPollingService extends Service {

    private volatile boolean enabled = true;
    private volatile boolean threadActive = false;
    private Thread pollingThread;
    // If we get an exception, we have to delay, otherwise there would be a looping
    private final long DELAY_AFTER_EXCEPTION_MS = 60000;
    // Delay between polling requests to avoid looping if the server would respond instantly
    private final long DELAY_AFTER_REQUEST_MS = 5000;
    public static String CHANNEL_ID = MqttPushService.CHANNEL_ID;
    // A flag preventing multiple notifications for the foreground service
    boolean started = false;
    // Notification ID for the foreground service
    private static final int NOTIFICATION_ID = 113;
    private volatile retrofit2.Call<PushResponse> activeCall;
    private volatile String activeIdentity;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive( Context context, Intent intent ) {
            if (intent != null && intent.getAction() != null &&
                    intent.getAction().equals(Const.ACTION_SERVICE_STOP)) {
                enabled = false;
                stopSelf();
            }
        }
    };

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance( this ).unregisterReceiver(receiver);
        Log.i(Const.LOG_TAG, "PushLongPollingService: service stopped");
        enabled = false;
        if (activeCall != null) activeCall.cancel();
        if (pollingThread != null) pollingThread.interrupt();
        started = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        enabled = true;
        SettingsHelper currentSettings = SettingsHelper.getInstance(this);
        if (currentSettings.getConfig() == null || !com.hmdm.launcher.json.ServerConfig.PUSH_OPTIONS_POLLING.equals(currentSettings.getConfig().getPushOptions())) {
            enabled = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (activeIdentity != null && !activeIdentity.equals(pollIdentity(currentSettings)) && activeCall != null) activeCall.cancel();

        if (BuildConfig.MQTT_SERVICE_FOREGROUND && !started) {
            startAsForeground();
            started = true;
        }

        Log.i(Const.LOG_TAG, "PushLongPolling: service started. ");

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        LocalBroadcastManager.getInstance( this ).registerReceiver( receiver, intentFilter );

        if (!threadActive) {
            threadActive = true;
            pollingThread = new Thread(pollingRunnable);
            pollingThread.start();
        }

        return Service.START_STICKY;
    }

    private String pollIdentity(SettingsHelper settings) {
        return settings.getBaseUrl() + "\n" + settings.getSecondaryBaseUrl() + "\n"
                + settings.getServerProject() + "\n" + settings.getDeviceId();
    }

    private Runnable pollingRunnable = () -> {
        SettingsHelper settings = SettingsHelper.getInstance(this);
        ServerService primary = null, secondary = null;
        String cachedIdentity = null;
        try {
            while (enabled) {
                String identity = pollIdentity(settings);
                activeIdentity = identity;
                String project = settings.getServerProject();
                String id = settings.getDeviceId();
                try {
                    String path = project + "/rest/notification/polling/" + URLEncoder.encode(id, "utf8");
                    String signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + path);
                    if (!identity.equals(cachedIdentity)) {
                        primary = ServerServiceKeeper.createServerService(settings.getBaseUrl(), Const.LONG_POLLING_READ_TIMEOUT);
                        secondary = ServerServiceKeeper.createServerService(settings.getSecondaryBaseUrl(), Const.LONG_POLLING_READ_TIMEOUT);
                        cachedIdentity = identity;
                    }
                    activeCall = primary.queryPushLongPolling(project, id, signature);
                    Response<PushResponse> response;
                    try { response = activeCall.execute(); }
                    catch (Exception primaryFailure) {
                        if (!enabled || !identity.equals(pollIdentity(settings))) continue;
                        activeCall = secondary.queryPushLongPolling(project, id, signature);
                        response = activeCall.execute();
                    }
                    if (!enabled || !identity.equals(pollIdentity(settings))) continue;
                    if (response.isSuccessful() && response.body() != null
                            && Const.STATUS_OK.equals(response.body().getStatus()) && response.body().getData() != null) {
                        boolean configUpdated = false;
                        for (PushMessage message : response.body().getData()) {
                            if (PushMessage.TYPE_CONFIG_UPDATED.equals(message.getMessageType())) {
                                if (configUpdated) continue;
                                configUpdated = true;
                            }
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                if (enabled && identity.equals(pollIdentity(settings))) PushNotificationProcessor.process(message, this);
                            });
                        }
                    } else if (response.code() >= 400 && response.code() < 500) {
                        RemoteLogger.log(this, Const.LOG_WARN, "HTTP push rejected: status " + response.code());
                        Thread.sleep(DELAY_AFTER_EXCEPTION_MS);
                    }
                    Thread.sleep(DELAY_AFTER_REQUEST_MS);
                } catch (InterruptedException e) {
                    if (!enabled) break;
                } catch (Exception e) {
                    if (!enabled) break;
                    if (!identity.equals(pollIdentity(settings))) continue;
                    RemoteLogger.log(this, Const.LOG_WARN, "HTTP push request failed");
                    try { Thread.sleep(DELAY_AFTER_EXCEPTION_MS); }
                    catch (InterruptedException interrupted) { if (!enabled) break; }
                }
            }
        } finally {
            activeCall = null;
            threadActive = false;
        }
    };


    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Notification Channel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder( this );
        }
        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText(getString(R.string.mqtt_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service).build();

        Utils.startStableForegroundService(this, NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
