package com.barta.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.barta.app.R;
import com.barta.app.ui.CallActivity;

/**
 * Runs only DURING a call. Android cuts the microphone/camera of apps in the background,
 * so this foreground service (type microphone / camera) keeps the call alive when you press
 * Home, and shows "Ongoing call - tap to return".
 */
public class CallService extends Service {
    private static final String CHANNEL = "barta_ongoing_call";
    private static final int ID = 4343;
    private static final String EX_NAME = "name";
    private static final String EX_VIDEO = "video";

    /** Start only after RECORD_AUDIO (and CAMERA for video) were granted. */
    public static void start(Context ctx, String name, boolean videoWithCamera) {
        Intent i = new Intent(ctx, CallService.class)
                .putExtra(EX_NAME, name)
                .putExtra(EX_VIDEO, videoWithCamera);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CallService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String name = intent == null ? null : intent.getStringExtra(EX_NAME);
        boolean video = intent != null && intent.getBooleanExtra(EX_VIDEO, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Ongoing call", NotificationManager.IMPORTANCE_LOW));
        }
        Intent back = new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 5, back,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.barta_primary))
                .setContentTitle(name == null ? "Barta call" : name)
                .setContentText((video ? "Ongoing video call" : "Ongoing voice call") + " · tap to return")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setContentIntent(pi)
                .build();

        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (video) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        try {
            ServiceCompat.startForeground(this, ID, n, type);
        } catch (RuntimeException e) {
            // e.g. permission missing: the call still works while the call screen is open
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
