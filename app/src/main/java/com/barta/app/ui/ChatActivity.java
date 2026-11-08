package com.barta.app.ui;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.barta.app.R;
import com.barta.app.adapter.MessageAdapter;
import com.barta.app.data.Rtdb;
import com.barta.app.data.RtdbLiveData;
import com.barta.app.data.StatusLiveData;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.ActivityChatBinding;
import com.barta.app.databinding.DialogContactBinding;
import com.barta.app.databinding.SheetMessageActionsBinding;
import com.barta.app.db.AppDatabase;
import com.barta.app.db.MessageEntity;
import com.barta.app.db.ScheduledEntity;
import com.barta.app.model.Chat;
import com.barta.app.model.Message;
import com.barta.app.model.User;
import com.barta.app.model.UserStatus;
import com.barta.app.util.AppExecutors;
import com.barta.app.util.ChatUtil;
import com.barta.app.util.ImageUtil;
import com.barta.app.util.LocationHelper;
import com.barta.app.util.MessageMapper;
import com.barta.app.util.MessageSender;
import com.barta.app.work.ScheduledMessageWorker;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-to-one and group chat screen.
 * Firestore = messages (live), Room = offline copy + search, Realtime Database = online / typing / seen,
 * WorkManager = scheduled messages.
 */
public class ChatActivity extends AppCompatActivity {
    private static final String TAG = "ChatActivity";
    public static final String EX_CHAT_ID = "chatId";
    public static final String EX_TITLE = "title";
    public static final String EX_GROUP = "group";
    public static final String EX_OTHER_UID = "otherUid";

    private static final String[] EMOJIS = {
            "👍", "❤️", "😂", "😮", "😢", "🙏"};
    private static final long TYPING_TIMEOUT_MS = 3000;

    /** The chat currently on screen (the notification service skips it). */
    public static volatile String openChatId = null;

    private ActivityChatBinding b;
    private FirebaseFirestore db;
    private String me, myName, chatId, otherUid, title;
    private boolean isGroup;
    private MessageAdapter adapter;
    private LinearLayoutManager layoutManager;
    private final List<Message> messages = new ArrayList<>();
    private ListenerRegistration messagesReg, chatReg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean visible;

    // Live state used to draw the header and input area
    private User meUser, otherUser;
    private Chat chat;
    private Map<String, UserStatus> statuses = new HashMap<>();
    private Map<String, Long> reads = new HashMap<>();
    private final Set<String> typingUids = new HashSet<>();
    private long lastMarkedRead = 0;

    // Reply / edit
    private Message replyTo, editing;

    // Typing indicator (Realtime Database)
    private boolean typingSent = false;
    private ValueEventListener typingListener;
    private final Runnable stopTyping = () -> setTyping(false);

    // Search inside the chat
    private List<String> matchIds = new ArrayList<>();
    private int matchIndex = -1;

    // Implicit intent (ACTION_GET_CONTENT) via the Activity Result API
    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);

    private final ActivityResultLauncher<String[]> locationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
                boolean granted = Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted) {
                    fetchAndSendLocation();
                } else {
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    public static Intent intentFor(Context ctx, Chat chat, String me) {
        Intent i = new Intent(ctx, ChatActivity.class);
        i.putExtra(EX_TITLE, chat.title(me));
        i.putExtra(EX_GROUP, chat.group);
        if (chat.group) {
            i.putExtra(EX_CHAT_ID, chat.id);
        } else {
            i.putExtra(EX_OTHER_UID, chat.otherUid(me));
        }
        return i;
    }

    public static Intent intentForUser(Context ctx, User user) {
        Intent i = new Intent(ctx, ChatActivity.class);
        i.putExtra(EX_TITLE, user.name);
        i.putExtra(EX_GROUP, false);
        i.putExtra(EX_OTHER_UID, user.uid);
        return i;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        db = FirebaseFirestore.getInstance();
        me = user.getUid();
        myName = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();

        Intent in = getIntent();
        title = in.getStringExtra(EX_TITLE);
        isGroup = in.getBooleanExtra(EX_GROUP, false);
        if (isGroup) {
            chatId = in.getStringExtra(EX_CHAT_ID);
        } else {
            otherUid = in.getStringExtra(EX_OTHER_UID);
            chatId = ChatUtil.directChatId(me, otherUid);
        }
        if (chatId == null) {
            finish();
            return;
        }

        // WhatsApp-style header: back arrow, avatar, name and a live subtitle line
        setSupportActionBar(b.toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        b.tvTitle.setText(title);
        b.avatar.setName(title);
        b.header.setOnClickListener(v -> showInfo());

        setupList();
        setupInput();
        setupSearch();
        observeLiveData();

        loadFromRoom();
        listenToMessages();
        listenToChatDoc();
        refreshSubtitle();
    }

    private void setupList() {
        adapter = new MessageAdapter(messages, me, isGroup, new MessageAdapter.Listener() {
            @Override
            public void onLongPress(Message m) {
                showActions(m);
            }

            @Override
            public void onQuoteClick(String replyToId) {
                jumpTo(replyToId);
            }

            @Override
            public void onLocationClick(Message m) {
                String label = me.equals(m.senderId) ? "My location" : m.senderName;
                LocationHelper.open(ChatActivity.this, m.lat, m.lng, label == null ? "Location" : label);
            }
        });
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        b.rvMessages.setLayoutManager(layoutManager);
        b.rvMessages.setAdapter(adapter);

        // Swipe a message to the right to reply (like WhatsApp)
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= messages.size() || messages.get(pos).deleted || isInputLocked()) {
                    return 0;
                }
                return super.getSwipeDirs(rv, vh);
            }

            @Override
            public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) {
                return 0.3f;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                adapter.notifyItemChanged(pos);   // slide the bubble back into place
                if (pos >= 0 && pos < messages.size()) startReply(messages.get(pos));
            }
        }).attachToRecyclerView(b.rvMessages);
    }

    private void setupInput() {
        b.btnSend.setOnClickListener(v -> {
            String text = b.etMessage.getText().toString().trim();
            if (editing != null) {
                saveEdit(text);
                return;
            }
            if (text.isEmpty()) return;
            b.etMessage.setText("");
            sendText(text);
        });
        // Long-press send = schedule this message for later (WorkManager)
        b.btnSend.setOnLongClickListener(v -> {
            pickScheduleTime();
            return true;
        });
        b.btnAttach.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, "Photo");
            menu.getMenu().add(0, 2, 1, "Location");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) imagePicker.launch("image/*");
                else askLocation();
                return true;
            });
            menu.show();
        });
        b.btnReplyClose.setOnClickListener(v -> clearReplyAndEdit());
        b.tvBlocked.setOnClickListener(v -> {
            if (meUser != null && meUser.hasBlocked(otherUid)) toggleBlock();
        });
        b.etMessage.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (editing != null) return;
                if (s.length() > 0) {
                    setTyping(true);
                    handler.removeCallbacks(stopTyping);
                    handler.postDelayed(stopTyping, TYPING_TIMEOUT_MS);
                } else {
                    setTyping(false);
                }
            }
        });
    }

    /** Users (names, photos, block/mute lists), online status and "seen" times, all live. */
    private void observeLiveData() {
        UsersLiveData.get().observe(this, users -> {
            if (users == null) return;
            meUser = users.get(me);
            if (!isGroup) {
                otherUser = users.get(otherUid);
                if (otherUser != null && otherUser.name != null) {
                    title = otherUser.name;
                    b.tvTitle.setText(title);
                }
            }
            refreshHeaderPhoto();
            refreshBlockedUi();
            refreshSubtitle();
            invalidateOptionsMenu();
        });
        StatusLiveData.get().observe(this, map -> {
            statuses = map == null ? new HashMap<>() : map;
            refreshSubtitle();
        });
        RtdbLiveData.longs(Rtdb.reads(chatId)).observe(this, map -> {
            reads = map == null ? new HashMap<>() : map;
            updateOthersRead();
        });
    }

    // ------------------------------------------------------------------ Firestore + Room

    /** Offline copy: show saved messages immediately, before the network answers. */
    private void loadFromRoom() {
        AppExecutors.IO.execute(() -> {
            List<MessageEntity> cached = AppDatabase.get(getApplicationContext())
                    .messageDao().getForChat(chatId);
            List<Message> list = new ArrayList<>();
            for (MessageEntity e : cached) list.add(MessageMapper.fromEntity(e));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (messages.isEmpty() && !list.isEmpty()) {
                    messages.addAll(list);
                    adapter.notifyDataSetChanged();
                    scrollToEnd();
                }
            });
        });
    }

    private void listenToMessages() {
        messagesReg = db.collection("chats").document(chatId).collection("messages")
                .orderBy("timestamp")
                // INCLUDE metadata changes so the clock icon turns into a tick once saved
                .addSnapshotListener(MetadataChanges.INCLUDE, (snap, e) -> {
                    if (e != null || snap == null) {
                        Log.w(TAG, "listen failed", e);
                        return;
                    }
                    // Brand-new chat opened while offline: keep what Room showed.
                    if (snap.isEmpty() && snap.getMetadata().isFromCache()) return;

                    boolean atBottom = !b.rvMessages.canScrollVertically(1);
                    int oldSize = messages.size();
                    List<Message> list = new ArrayList<>();
                    List<MessageEntity> entities = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Message m = d.toObject(Message.class);
                        if (m == null) continue;
                        m.id = d.getId();
                        m.pending = d.getMetadata().hasPendingWrites();
                        list.add(m);
                        entities.add(MessageMapper.toEntity(m, chatId));
                    }
                    messages.clear();
                    messages.addAll(list);
                    adapter.notifyDataSetChanged();
                    // Only jump down for new messages, not for reactions/edits on old ones
                    if (messages.size() > oldSize && (atBottom || oldSize == 0 || isMineLast())) {
                        scrollToEnd();
                    }
                    markRead();
                    AppExecutors.IO.execute(() ->
                            AppDatabase.get(getApplicationContext()).messageDao().insertAll(entities));
                });
    }

    /** Group name / members (and the direct chat document once it exists). */
    private void listenToChatDoc() {
        chatReg = db.collection("chats").document(chatId).addSnapshotListener((doc, e) -> {
            if (doc == null || !doc.exists()) return;
            Chat c = doc.toObject(Chat.class);
            if (c == null) return;
            c.id = doc.getId();
            chat = c;
            if (isGroup) {
                title = c.title(me);
                b.tvTitle.setText(title);
                b.avatar.setName(title);
            }
            refreshSubtitle();
            updateOthersRead();
        });
    }

    private boolean isMineLast() {
        return !messages.isEmpty() && me.equals(messages.get(messages.size() - 1).senderId);
    }

    private MessageSender.Target target() {
        return new MessageSender.Target(chatId, isGroup, me, myName, otherUid, title);
    }

    // ------------------------------------------------------------------ sending

    private boolean isInputLocked() {
        return !isGroup && ((meUser != null && meUser.hasBlocked(otherUid))
                || (otherUser != null && otherUser.hasBlocked(me)));
    }

    private void sendText(String text) {
        if (isInputLocked()) return;
        Map<String, Object> msg = MessageSender.newMessage(target(), text);
        attachReply(msg);
        send(msg, text);
    }

    private void attachReply(Map<String, Object> msg) {
        if (replyTo == null) return;
        msg.put("replyToId", replyTo.id);
        msg.put("replyToSender", replyTo.senderName);
        String quote = replyTo.preview();
        msg.put("replyToText", quote.length() > 120 ? quote.substring(0, 120) + "…" : quote);
        clearReplyAndEdit();
    }

    private void send(Map<String, Object> msg, String preview) {
        MessageSender.send(db, target(), msg, preview).addOnFailureListener(ex ->
                Toast.makeText(this, "Send failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void onImagePicked(Uri uri) {
        if (uri == null) return;
        AppExecutors.IO.execute(() -> {
            try {
                String b64 = ImageUtil.uriToBase64(getContentResolver(), uri);
                runOnUiThread(() -> {
                    if (isInputLocked()) return;
                    Map<String, Object> msg = MessageSender.newMessage(target(), "");
                    msg.put("imageBase64", b64);
                    attachReply(msg);
                    send(msg, "📷 Photo");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Could not send image: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ share location

    private void askLocation() {
        locationPermission.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private void fetchAndSendLocation() {
        Toast.makeText(this, "Getting your location…", Toast.LENGTH_SHORT).show();
        LocationHelper.getCurrent(this, (Location loc, String error) -> {
            if (isFinishing()) return;
            if (loc == null) {
                Toast.makeText(this, error == null ? "Location not available" : error,
                        Toast.LENGTH_LONG).show();
                return;
            }
            double lat = loc.getLatitude();
            double lng = loc.getLongitude();
            new AlertDialog.Builder(this)
                    .setTitle("Send your location?")
                    .setMessage(ChatUtil.coords(lat, lng)
                            + "\n(accuracy about " + Math.round(loc.getAccuracy()) + " m)")
                    .setPositiveButton("Send", (d, w) -> {
                        if (isInputLocked()) return;
                        Map<String, Object> msg = MessageSender.newMessage(target(), "");
                        msg.put("lat", lat);
                        msg.put("lng", lng);
                        attachReply(msg);
                        send(msg, "📍 Location");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    // ------------------------------------------------------------------ reply / edit / delete / react

    private void showActions(Message m) {
        if (m.deleted) return;
        boolean mine = me.equals(m.senderId);
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        SheetMessageActionsBinding s = SheetMessageActionsBinding.inflate(getLayoutInflater());
        sheet.setContentView(s.getRoot());

        String myReaction = m.reactions == null ? null : m.reactions.get(me);
        int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                getResources().getDisplayMetrics()));
        int margin = size / 12;
        for (String emoji : EMOJIS) {
            TextView tv = new TextView(this);
            tv.setText(emoji);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.emoji_bg);
            if (emoji.equals(myReaction)) {   // my current reaction looks "pressed"
                tv.setScaleX(1.2f);
                tv.setScaleY(1.2f);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, 0, margin, 0);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                react(m, emoji);
                sheet.dismiss();
            });
            s.emojiRow.addView(tv);
        }

        boolean locked = isInputLocked();
        s.actReply.setVisibility(locked ? View.GONE : View.VISIBLE);
        s.actCopy.setVisibility(m.hasText() && !m.isCall() ? View.VISIBLE : View.GONE);
        s.actEdit.setVisibility(mine && m.hasText() && !m.isImage() && !m.isLocation() && !m.isCall() && !locked
                ? View.VISIBLE : View.GONE);
        s.actDelete.setVisibility(mine ? View.VISIBLE : View.GONE);

        s.actReply.setOnClickListener(v -> {
            sheet.dismiss();
            startReply(m);
        });
        s.actCopy.setOnClickListener(v -> {
            sheet.dismiss();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        s.actEdit.setOnClickListener(v -> {
            sheet.dismiss();
            startEdit(m);
        });
        s.actDelete.setOnClickListener(v -> {
            sheet.dismiss();
            confirmDelete(m);
        });
        sheet.show();
    }

    private void startReply(Message m) {
        if (m.deleted || isInputLocked()) return;
        editing = null;
        replyTo = m;
        b.tvReplyTitle.setText(me.equals(m.senderId) ? "Replying to yourself" : "Replying to " + m.senderName);
        b.tvReplyText.setText(m.preview());
        b.replyBar.setVisibility(View.VISIBLE);
        showKeyboard();
    }

    private void startEdit(Message m) {
        replyTo = null;
        editing = m;
        setTyping(false);
        b.tvReplyTitle.setText("Edit message");
        b.tvReplyText.setText(m.text);
        b.replyBar.setVisibility(View.VISIBLE);
        b.etMessage.setText(m.text);
        b.etMessage.setSelection(b.etMessage.getText().length());
        showKeyboard();
    }

    private void clearReplyAndEdit() {
        boolean wasEditing = editing != null;
        replyTo = null;
        editing = null;
        b.replyBar.setVisibility(View.GONE);
        if (wasEditing) b.etMessage.setText("");
    }

    private boolean isLast(Message m) {
        return !messages.isEmpty() && m.id != null && m.id.equals(messages.get(messages.size() - 1).id);
    }

    private void saveEdit(String newText) {
        Message m = editing;
        if (m == null) return;
        if (newText.isEmpty()) {
            Toast.makeText(this, "Message can't be empty", Toast.LENGTH_SHORT).show();
            return;
        }
        clearReplyAndEdit();
        if (newText.equals(m.text)) return;

        DocumentReference chatRef = db.collection("chats").document(chatId);
        Map<String, Object> change = new HashMap<>();
        change.put("text", newText);
        change.put("edited", true);
        WriteBatch batch = db.batch();
        batch.update(chatRef.collection("messages").document(m.id), change);
        if (isLast(m)) batch.update(chatRef, "lastMessage", newText);
        batch.commit().addOnFailureListener(ex ->
                Toast.makeText(this, "Edit failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void confirmDelete(Message m) {
        new AlertDialog.Builder(this)
                .setMessage("Delete this message for everyone?")
                .setPositiveButton("Delete", (d, w) -> deleteForEveryone(m))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Keeps the document but wipes its content, so others see "This message was deleted". */
    private void deleteForEveryone(Message m) {
        DocumentReference chatRef = db.collection("chats").document(chatId);
        Map<String, Object> change = new HashMap<>();
        change.put("deleted", true);
        change.put("text", "");
        change.put("imageBase64", "");
        change.put("lat", FieldValue.delete());
        change.put("lng", FieldValue.delete());
        change.put("replyToId", FieldValue.delete());
        change.put("replyToSender", FieldValue.delete());
        change.put("replyToText", FieldValue.delete());
        change.put("reactions", FieldValue.delete());
        WriteBatch batch = db.batch();
        batch.update(chatRef.collection("messages").document(m.id), change);
        if (isLast(m)) batch.update(chatRef, "lastMessage", "🚫 This message was deleted");
        batch.commit().addOnFailureListener(ex ->
                Toast.makeText(this, "Delete failed: " + ex.getMessage(), Toast.LENGTH_LONG).show());
        if (editing != null && m.id.equals(editing.id)) clearReplyAndEdit();
    }

    /** reactions.{myUid} = emoji. Tapping the same emoji again removes it. */
    private void react(Message m, String emoji) {
        String current = m.reactions == null ? null : m.reactions.get(me);
        Object value = emoji.equals(current) ? FieldValue.delete() : emoji;
        db.collection("chats").document(chatId).collection("messages").document(m.id)
                .update("reactions." + me, value)
                .addOnFailureListener(ex ->
                        Toast.makeText(this, "Reaction failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------ scheduled messages

    private void pickScheduleTime() {
        String text = b.etMessage.getText().toString().trim();
        if (editing != null) return;
        if (text.isEmpty()) {
            Toast.makeText(this, "Type a message first, then long-press send to schedule it",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (isInputLocked()) return;
        Calendar c = Calendar.getInstance();
        DatePickerDialog dp = new DatePickerDialog(this, (view, y, mo, d) -> {
            Calendar when = Calendar.getInstance();
            when.set(y, mo, d);
            new TimePickerDialog(this, (tp, h, min) -> {
                when.set(Calendar.HOUR_OF_DAY, h);
                when.set(Calendar.MINUTE, min);
                when.set(Calendar.SECOND, 0);
                long sendAt = when.getTimeInMillis();
                if (sendAt <= System.currentTimeMillis() + 30_000) {
                    Toast.makeText(this, "Pick a time in the future", Toast.LENGTH_SHORT).show();
                    return;
                }
                ScheduledMessageWorker.schedule(this, target(), text, sendAt);
                b.etMessage.setText("");
                Toast.makeText(this, "Scheduled for " + ChatUtil.scheduleLabel(sendAt),
                        Toast.LENGTH_LONG).show();
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(c.getTimeInMillis() - 1000);
        dp.show();
    }

    private void showScheduled() {
        AppExecutors.IO.execute(() -> {
            List<ScheduledEntity> list = AppDatabase.get(getApplicationContext())
                    .messageDao().getScheduled(chatId);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (list.isEmpty()) {
                    Toast.makeText(this, "No scheduled messages. Long-press send to schedule one.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    ScheduledEntity s = list.get(i);
                    labels[i] = ChatUtil.scheduleLabel(s.sendAt) + "\n" + s.text;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Scheduled messages (tap to cancel)")
                        .setItems(labels, (d, which) -> {
                            ScheduledEntity s = list.get(which);
                            new AlertDialog.Builder(this)
                                    .setMessage("Cancel this scheduled message?\n\n" + s.text)
                                    .setPositiveButton("Cancel message", (d2, w2) -> {
                                        ScheduledMessageWorker.cancel(this, s.workId);
                                        Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("Keep", null)
                                    .show();
                        })
                        .setPositiveButton("Close", null)
                        .show();
            });
        });
    }

    // ------------------------------------------------------------------ search inside the chat

    private void setupSearch() {
        b.etSearch.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                runSearch(s.toString().trim());
            }
        });
        b.btnSearchUp.setOnClickListener(v -> {
            if (matchIndex > 0) {
                matchIndex--;
                showMatch();
            }
        });
        b.btnSearchDown.setOnClickListener(v -> {
            if (matchIndex < matchIds.size() - 1) {
                matchIndex++;
                showMatch();
            }
        });
        b.btnSearchClose.setOnClickListener(v -> closeSearch());
    }

    private void openSearch() {
        b.searchBar.setVisibility(View.VISIBLE);
        b.etSearch.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(b.etSearch, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        b.etSearch.setText("");
        b.searchBar.setVisibility(View.GONE);
        adapter.setHighlight(null);
        hideKeyboard();
    }

    /** Queries Room (offline cache) for messages containing the text. */
    private void runSearch(String q) {
        if (q.isEmpty()) {
            matchIds = new ArrayList<>();
            matchIndex = -1;
            b.tvSearchCount.setText("");
            adapter.setHighlight(null);
            return;
        }
        AppExecutors.IO.execute(() -> {
            List<String> ids = AppDatabase.get(getApplicationContext()).messageDao().search(chatId, q);
            runOnUiThread(() -> {
                if (!q.equals(b.etSearch.getText().toString().trim())) return; // user kept typing
                matchIds = ids;
                matchIndex = ids.size() - 1;   // start from the newest match
                adapter.setHighlight(q);
                showMatch();
            });
        });
    }

    private void showMatch() {
        if (matchIds.isEmpty()) {
            b.tvSearchCount.setText("0");
            return;
        }
        b.tvSearchCount.setText((matchIndex + 1) + "/" + matchIds.size());
        jumpTo(matchIds.get(matchIndex));
    }

    /** Scrolls to a message and flashes it (used by search and by tapping a reply quote). */
    private void jumpTo(String id) {
        int pos = adapter.positionOf(id);
        if (pos < 0) {
            Toast.makeText(this, "Original message not found", Toast.LENGTH_SHORT).show();
            return;
        }
        layoutManager.scrollToPositionWithOffset(pos, b.rvMessages.getHeight() / 3);
        adapter.setFlashId(id);
        handler.postDelayed(() -> adapter.setFlashId(null), 1500);
    }

    // ------------------------------------------------------------------ Realtime Database: typing + seen

    private void setTyping(boolean on) {
        if (on == typingSent || chatId == null) return;
        if (on && isInputLocked()) return;
        typingSent = on;
        DatabaseReference ref = Rtdb.typing(chatId).child(me);
        if (on) {
            ref.onDisconnect().removeValue();   // never stay "typing..." if the app dies
            ref.setValue(true);
        } else {
            ref.removeValue();
            ref.onDisconnect().cancel();
        }
    }

    private void startTypingListener() {
        typingListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                typingUids.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    if (c.getKey() != null && !c.getKey().equals(me)) typingUids.add(c.getKey());
                }
                refreshSubtitle();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        };
        Rtdb.typing(chatId).addValueEventListener(typingListener);
    }

    private void stopTypingListener() {
        if (typingListener != null) Rtdb.typing(chatId).removeEventListener(typingListener);
        typingListener = null;
        typingUids.clear();
    }

    /** Saves "I have read up to the newest message" in two places (chat + my unread list). */
    private void markRead() {
        if (!visible || messages.isEmpty()) return;
        long newest = messages.get(messages.size() - 1).timestamp;
        if (newest <= lastMarkedRead) return;
        lastMarkedRead = newest;
        Map<String, Object> update = new HashMap<>();
        update.put("reads/" + chatId + "/" + me, newest);
        update.put("userReads/" + me + "/" + chatId, newest);
        Rtdb.root().updateChildren(update);
    }

    /** Direct chat: the other person's read time. Group: seen only when EVERYONE has read it. */
    private void updateOthersRead() {
        long value;
        if (!isGroup) {
            Long r = reads.get(otherUid);
            value = r == null ? 0 : r;
        } else if (chat != null && chat.members != null && chat.members.size() > 1) {
            value = Long.MAX_VALUE;
            for (String uid : chat.members) {
                if (uid.equals(me)) continue;
                Long r = reads.get(uid);
                value = Math.min(value, r == null ? 0 : r);
            }
        } else {
            value = 0;
        }
        adapter.setOthersReadUpTo(value);
    }

    // ------------------------------------------------------------------ header, block, mute

    private void refreshHeaderPhoto() {
        if (isGroup) return;
        boolean hidden = otherUser != null && otherUser.hasBlocked(me);
        String photo = otherUser == null || hidden ? null : otherUser.photoBase64;
        b.avatar.setUser(title, photo);
    }

    private void refreshSubtitle() {
        String text = null;
        if (!typingUids.isEmpty() && !isInputLocked()) {
            if (!isGroup) {
                text = "typing…";
            } else {
                List<String> names = new ArrayList<>();
                for (String uid : typingUids) names.add(nameOf(uid));
                text = TextUtils.join(", ", names) + (names.size() == 1 ? " is typing…" : " are typing…");
            }
        } else if (!isGroup) {
            if (!isInputLocked()) text = ChatUtil.presence(statuses.get(otherUid));
            if (text == null && otherUser != null) text = "tap here for contact info";
        } else if (chat != null && chat.memberNames != null) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, String> e : chat.memberNames.entrySet()) {
                if (!e.getKey().equals(me)) names.add(e.getValue());
            }
            Collections.sort(names);
            names.add("You");
            text = TextUtils.join(", ", names);
        }
        b.avatar.setOnline(!isGroup && !isInputLocked() && statuses.get(otherUid) != null
                && statuses.get(otherUid).online);
        if (text == null) {
            b.tvSubtitle.setVisibility(View.GONE);
        } else {
            b.tvSubtitle.setText(text);
            b.tvSubtitle.setVisibility(View.VISIBLE);
        }
    }

    private String nameOf(String uid) {
        if (chat != null && chat.memberNames != null && chat.memberNames.get(uid) != null) {
            return chat.memberNames.get(uid);
        }
        return "Someone";
    }

    private void refreshBlockedUi() {
        if (isGroup) return;
        boolean iBlocked = meUser != null && meUser.hasBlocked(otherUid);
        boolean theyBlocked = otherUser != null && otherUser.hasBlocked(me);
        if (iBlocked) {
            b.tvBlocked.setText("You blocked this contact. Tap to unblock.");
        } else if (theyBlocked) {
            b.tvBlocked.setText("You can't send messages to this contact.");
        }
        boolean locked = iBlocked || theyBlocked;
        b.tvBlocked.setVisibility(locked ? View.VISIBLE : View.GONE);
        b.inputArea.setVisibility(locked ? View.GONE : View.VISIBLE);
        if (locked) {
            clearReplyAndEdit();
            setTyping(false);
        }
    }

    private void toggleBlock() {
        if (isGroup || meUser == null) return;
        boolean blocked = meUser.hasBlocked(otherUid);
        String name = title == null ? "this contact" : title;
        new AlertDialog.Builder(this)
                .setMessage(blocked ? "Unblock " + name + "?"
                        : "Block " + name + "? They will not be able to message you or see when you are online.")
                .setPositiveButton(blocked ? "Unblock" : "Block", (d, w) ->
                        db.collection("users").document(me).update("blocked",
                                blocked ? FieldValue.arrayRemove(otherUid) : FieldValue.arrayUnion(otherUid)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleMute() {
        if (meUser == null) return;
        boolean muted = meUser.hasMuted(chatId);
        db.collection("users").document(me).update("muted",
                        muted ? FieldValue.arrayRemove(chatId) : FieldValue.arrayUnion(chatId))
                .addOnSuccessListener(v -> Toast.makeText(this,
                        muted ? "Notifications on" : "Notifications muted", Toast.LENGTH_SHORT).show());
    }

    private void startCall(boolean video) {
        if (isGroup || isInputLocked()) return;
        if (CallActivity.active) {
            Toast.makeText(this, "You are already in a call", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(CallActivity.outgoing(this, otherUid, title, video));
    }

    private void showInfo() {
        DialogContactBinding d = DialogContactBinding.inflate(LayoutInflater.from(this));
        if (isGroup) {
            d.avatar.setName(title);
            d.tvName.setText(title);
            int count = chat == null || chat.members == null ? 0 : chat.members.size();
            d.tvEmail.setText("Group · " + count + " members");
            d.tvPresence.setVisibility(View.GONE);
            d.tvAbout.setText(b.tvSubtitle.getText());
            d.tvAboutLabel.setText("Members");
        } else {
            boolean hidden = isInputLocked();
            d.avatar.setUser(title, otherUser == null || hidden ? null : otherUser.photoBase64);
            d.tvName.setText(title);
            d.tvEmail.setText(otherUser == null ? "" : otherUser.email);
            String presence = hidden ? null : ChatUtil.presence(statuses.get(otherUid));
            d.tvPresence.setText(presence == null ? "" : presence);
            String about = otherUser == null ? null : otherUser.about;
            d.tvAbout.setText(about == null || about.isEmpty() ? "Hey there! I am using Barta." : about);
        }
        new AlertDialog.Builder(this).setView(d.getRoot()).setPositiveButton("Close", null).show();
    }

    // ------------------------------------------------------------------ menu + lifecycle

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chat, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_info).setTitle(isGroup ? "Group info" : "Contact info");
        boolean muted = meUser != null && meUser.hasMuted(chatId);
        menu.findItem(R.id.action_mute).setTitle(muted ? "Unmute notifications" : "Mute notifications");
        // Calls: 1-to-1 only, not when blocked
        boolean canCall = !isGroup && !isInputLocked();
        menu.findItem(R.id.action_voice_call).setVisible(canCall);
        menu.findItem(R.id.action_video_call).setVisible(canCall);
        MenuItem block = menu.findItem(R.id.action_block);
        block.setVisible(!isGroup);
        block.setTitle(meUser != null && meUser.hasBlocked(otherUid) ? "Unblock" : "Block");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_voice_call) {
            startCall(false);
            return true;
        } else if (id == R.id.action_video_call) {
            startCall(true);
            return true;
        } else if (id == R.id.action_search) {
            openSearch();
            return true;
        } else if (id == R.id.action_info) {
            showInfo();
            return true;
        } else if (id == R.id.action_scheduled) {
            showScheduled();
            return true;
        } else if (id == R.id.action_mute) {
            toggleMute();
            return true;
        } else if (id == R.id.action_block) {
            toggleBlock();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        openChatId = chatId;
        if (chatId != null) {
            NotificationManagerCompat.from(this).cancel(chatId.hashCode());
            startTypingListener();
            markRead();
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        openChatId = null;
        handler.removeCallbacks(stopTyping);
        setTyping(false);
        stopTypingListener();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (messagesReg != null) messagesReg.remove();
        if (chatReg != null) chatReg.remove();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void scrollToEnd() {
        if (!messages.isEmpty()) b.rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void showKeyboard() {
        b.etMessage.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(b.etMessage, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(b.getRoot().getWindowToken(), 0);
    }

    /** TextWatcher with empty defaults, so we only override what we need. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
}
