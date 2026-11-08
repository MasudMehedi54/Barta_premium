package com.barta.app.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.barta.app.R;
import com.barta.app.call.CallSignaling;
import com.barta.app.call.IncomingCallNotifier;
import com.barta.app.model.Chat;
import com.barta.app.model.User;
import com.barta.app.receiver.NetworkReceiver;
import com.barta.app.ui.CallActivity;
import com.barta.app.ui.ChatActivity;
import com.barta.app.ui.MainActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * Foreground Service. Push notifications (FCM) need a paid server to send them, so Barta
 * keeps a free Firestore listener alive in this service and posts LOCAL notifications.
 */
public class MessageListenerService extends Service {
    // New id (v2): a channel's importance cannot be changed after creation, and the old
    // channel was IMPORTANCE_MIN, which hides the notification from the status bar.
    private static final String CH_SERVICE = "barta_status";
    private static final String CH_MESSAGES = "barta_messages";
    private static final int FOREGROUND_ID = 1;

    private ListenerRegistration registration;
    private ListenerRegistration meRegistration;
    /** My own user document: muted chats and blocked people get no notification. */
    private volatile User meUser;
    private final Map<String, Long> lastNotified = new HashMap<>();
    private long startTime;

    // Live state shown in the foreground-service notification (tray)
    private volatile boolean online = true;
    private volatile String lastLine = null;
    private ConnectivityManager.NetworkCallback networkCallback;

    // Incoming calls (Realtime Database: calls/{myUid})
    private DatabaseReference callInbox;
    private ValueEventListener callListener;
    private String handledCallId;

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, MessageListenerService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MessageListenerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startTime = System.currentTimeMillis();
        createChannels();
        online = NetworkReceiver.isOnline(this);
        ServiceCompat.startForeground(this, FOREGROUND_ID, buildStatusNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        watchConnectivity();
    }

    /** Ongoing tray notification: connection status + last received message. Tap opens the app. */
    private Notification buildStatusNotification() {
        String title = online ? "Barta \u00B7 Connected" : "Barta \u00B7 Offline";
        String text = lastLine != null ? lastLine
                : (online ? "Listening for new messages" : "Waiting for network\u2026");
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.barta_primary))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /** Re-posts the foreground notification with the same id so the tray entry updates in place. */
    @SuppressLint("MissingPermission")
    private void updateStatusNotification() {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!nm.areNotificationsEnabled()) return;
        nm.notify(FOREGROUND_ID, buildStatusNotification());
    }

    /** Keeps the tray notification in sync when the connection drops or comes back. */
    private void watchConnectivity() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                online = true;
                updateStatusNotification();
            }

            @Override
            public void onLost(Network network) {
                online = false;
                updateStatusNotification();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (registration == null) listen(uid);
        if (callListener == null) listenForCalls(uid);
        return START_NOT_STICKY;
    }

    private void listen(String uid) {
        meRegistration = FirebaseFirestore.getInstance().collection("users").document(uid)
                .addSnapshotListener((doc, e) -> {
                    if (doc != null) meUser = doc.toObject(User.class);
                });
        registration = FirebaseFirestore.getInstance()
                .collection("chats")
                .whereArrayContains("members", uid)
                .addSnapshotListener((snap, e) -> {
                    if (snap == null) return;
                    for (DocumentChange dc : snap.getDocumentChanges()) {
                        if (dc.getType() == DocumentChange.Type.REMOVED) continue;
                        Chat c = dc.getDocument().toObject(Chat.class);
                        c.id = dc.getDocument().getId();
                        if (c.updatedAt <= startTime) continue;           // old message
                        if (uid.equals(c.lastSenderId)) continue;         // my own message
                        if (c.id.equals(ChatActivity.openChatId)) continue; // already on screen
                        User mine = meUser;
                        if (mine != null && mine.hasMuted(c.id)) continue;           // muted chat
                        if (mine != null && mine.hasBlocked(c.lastSenderId)) continue; // blocked person
                        Long prev = lastNotified.get(c.id);
                        if (prev != null && prev >= c.updatedAt) continue;
                        lastNotified.put(c.id, c.updatedAt);
                        showMessageNotification(c, uid);
                        updateStatusNotification();
                    }
                });
    }

    /** Someone writes calls/{me} when they call me. Ring, or answer "busy". */
    private void listenForCalls(String uid) {
        IncomingCallNotifier.createChannel(this);
        callInbox = CallSignaling.inbox(uid);
        callListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!snap.exists()) {               // caller hung up / call answered elsewhere
                    IncomingCallNotifier.cancel(MessageListenerService.this);
                    return;
                }
                String callId = snap.child("callId").getValue(String.class);
                String callerId = snap.child("callerId").getValue(String.class);
                String callerName = snap.child("callerName").getValue(String.class);
                Boolean video = snap.child("video").getValue(Boolean.class);
                Long createdAt = snap.child("createdAt").getValue(Long.class);
                if (callId == null || callerId == null || callId.equals(handledCallId)) return;
                handledCallId = callId;

                // Leftover from a call that never got cleaned up
                if (createdAt != null && Math.abs(System.currentTimeMillis() - createdAt) > 90_000) {
                    snap.getRef().removeValue();
                    return;
                }
                User mine = meUser;
                if (mine != null && mine.hasBlocked(callerId)) {   // blocked people can't ring me
                    CallSignaling.setStatus(callId, CallSignaling.DECLINED);
                    snap.getRef().removeValue();
                    return;
                }
                if (CallActivity.active) {                          // already in a call
                    CallSignaling.setStatus(callId, CallSignaling.BUSY);
                    snap.getRef().removeValue();
                    return;
                }
                IncomingCallNotifier.show(MessageListenerService.this, callId, callerId, callerName,
                        video != null && video);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        };
        callInbox.addValueEventListener(callListener);
    }

    @SuppressLint("MissingPermission")
    private void showMessageNotification(Chat c, String me) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        if (!nm.areNotificationsEnabled()) return;

        String sender = c.memberNames == null ? null : c.memberNames.get(c.lastSenderId);
        String title = c.group ? c.title(me) : (sender == null ? "New message" : sender);
        String body = c.group && sender != null ? sender + ": " + c.lastMessage : c.lastMessage;

        lastLine = body == null ? null : "Last: " + body;
        Intent open = ChatActivity.intentFor(this, c, me);
        PendingIntent pi = PendingIntent.getActivity(this, c.id.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.barta_primary))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(c.id.hashCode(), n);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(
                CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override
    public void onDestroy() {
        if (registration != null) registration.remove();
        if (meRegistration != null) meRegistration.remove();
        if (callInbox != null && callListener != null) callInbox.removeEventListener(callListener);
        if (networkCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
