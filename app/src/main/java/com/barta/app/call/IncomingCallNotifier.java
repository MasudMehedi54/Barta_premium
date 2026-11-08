package com.barta.app.call;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.barta.app.R;
import com.barta.app.receiver.CallActionReceiver;
import com.barta.app.ui.CallActivity;

/**
 * Shows an incoming call. If Barta is on screen, the call screen opens directly.
 * Otherwise a ringing "full-screen" notification is posted (Answer / Decline buttons),
 * because Android does not let a background app open a screen by itself.
 */
public final class IncomingCallNotifier {
    public static final String CHANNEL = "barta_calls";
    public static final int NOTIFICATION_ID = 4242;

    private IncomingCallNotifier() { }

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 1000, 800, 1000});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @SuppressLint("MissingPermission")
    public static void show(Context ctx, String callId, String callerId, String callerName, boolean video) {
        Intent ring = CallActivity.incoming(ctx, callId, callerId, callerName, video, false);

        boolean appVisible = ProcessLifecycleOwner.get().getLifecycle().getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
        if (appVisible) {
            ctx.startActivity(ring);
            return;
        }

        createChannel(ctx);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent fullScreen = PendingIntent.getActivity(ctx, 1, ring, flags);
        PendingIntent answer = PendingIntent.getActivity(ctx, 2,
                CallActivity.incoming(ctx, callId, callerId, callerName, video, true), flags);
        PendingIntent decline = PendingIntent.getBroadcast(ctx, 3,
                CallActionReceiver.decline(ctx, callId, callerId), flags);

        String kind = video ? "Incoming video call" : "Incoming voice call";
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(ctx, R.color.barta_primary))
                .setContentTitle(callerName == null ? "Barta" : callerName)
                .setContentText(kind)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .addAction(R.drawable.ic_call_end, "Decline", decline)
                .addAction(R.drawable.ic_call, "Answer", answer)
                .setTimeoutAfter(CallConfig.RING_TIMEOUT_MS + 5_000)
                .build();
        n.flags |= Notification.FLAG_INSISTENT;   // keep ringing until answered / cancelled
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (nm.areNotificationsEnabled()) nm.notify(NOTIFICATION_ID, n);
    }

    public static void cancel(Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID);
    }
}
