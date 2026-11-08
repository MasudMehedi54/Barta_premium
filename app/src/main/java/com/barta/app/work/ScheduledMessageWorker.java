package com.barta.app.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.barta.app.db.AppDatabase;
import com.barta.app.db.ScheduledEntity;
import com.barta.app.util.AppExecutors;
import com.barta.app.util.MessageSender;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled messages with WorkManager (background-operations topic).
 * WorkManager runs on the phone, survives app restarts and reboots, and waits for internet.
 * Note: Android may delay the job by a few minutes to save battery (Doze); it is not to-the-second.
 */
public class ScheduledMessageWorker extends Worker {
    private static final String TAG = "ScheduledWorker";
    public static final String TAG_ALL = "scheduled";

    private static final String K_CHAT = "chatId";
    private static final String K_GROUP = "group";
    private static final String K_OTHER_UID = "otherUid";
    private static final String K_OTHER_NAME = "otherName";
    private static final String K_TEXT = "text";

    public ScheduledMessageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Schedules the job and saves a copy in Room so the chat can list / cancel it. */
    public static void schedule(Context ctx, MessageSender.Target t, String text, long sendAt) {
        long delay = Math.max(0, sendAt - System.currentTimeMillis());
        Data data = new Data.Builder()
                .putString(K_CHAT, t.chatId)
                .putBoolean(K_GROUP, t.group)
                .putString(K_OTHER_UID, t.otherUid)
                .putString(K_OTHER_NAME, t.otherName)
                .putString(K_TEXT, text)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ScheduledMessageWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(data)
                .addTag(TAG_ALL)
                .build();

        ScheduledEntity row = new ScheduledEntity();
        row.workId = req.getId().toString();
        row.chatId = t.chatId;
        row.text = text;
        row.sendAt = sendAt;
        Context app = ctx.getApplicationContext();
        AppExecutors.IO.execute(() -> AppDatabase.get(app).messageDao().insertScheduled(row));
        WorkManager.getInstance(app).enqueue(req);
    }

    public static void cancel(Context ctx, String workId) {
        Context app = ctx.getApplicationContext();
        WorkManager.getInstance(app).cancelWorkById(UUID.fromString(workId));
        AppExecutors.IO.execute(() -> AppDatabase.get(app).messageDao().deleteScheduled(workId));
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String workId = getId().toString();
        if (user == null) {   // signed out meanwhile
            AppDatabase.get(getApplicationContext()).messageDao().deleteScheduled(workId);
            return Result.failure();
        }
        String myName = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
        Data in = getInputData();
        MessageSender.Target t = new MessageSender.Target(
                in.getString(K_CHAT), in.getBoolean(K_GROUP, false), user.getUid(), myName,
                in.getString(K_OTHER_UID), in.getString(K_OTHER_NAME));
        String text = in.getString(K_TEXT);
        if (t.chatId == null || text == null) return Result.failure();

        try {
            Map<String, Object> msg = MessageSender.newMessage(t, text);
            Tasks.await(MessageSender.send(FirebaseFirestore.getInstance(), t, msg, text),
                    30, TimeUnit.SECONDS);
            AppDatabase.get(getApplicationContext()).messageDao().deleteScheduled(workId);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "scheduled send failed", e);
            if (getRunAttemptCount() < 3) return Result.retry();
            AppDatabase.get(getApplicationContext()).messageDao().deleteScheduled(workId);
            return Result.failure();
        }
    }
}
