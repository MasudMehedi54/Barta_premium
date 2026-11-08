package com.barta.app.util;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes a message and updates the chat's "last message" in ONE batch.
 * Used by ChatActivity (send now) and ScheduledMessageWorker (send later).
 */
public final class MessageSender {

    /** Who/where the message goes. otherUid/otherName are only used for direct chats. */
    public static class Target {
        public final String chatId;
        public final boolean group;
        public final String me;
        public final String myName;
        public final String otherUid;
        public final String otherName;

        public Target(String chatId, boolean group, String me, String myName,
                      String otherUid, String otherName) {
            this.chatId = chatId;
            this.group = group;
            this.me = me;
            this.myName = myName;
            this.otherUid = otherUid;
            this.otherName = otherName;
        }
    }

    private MessageSender() { }

    /** A new message map with sender, time and empty text/image. Add extra fields, then send(). */
    public static Map<String, Object> newMessage(Target t, String text) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("senderId", t.me);
        msg.put("senderName", t.myName);
        msg.put("text", text == null ? "" : text);
        msg.put("imageBase64", "");
        msg.put("timestamp", System.currentTimeMillis());
        msg.put("edited", false);
        msg.put("deleted", false);
        return msg;
    }

    public static Task<Void> send(FirebaseFirestore db, Target t, Map<String, Object> msg, String preview) {
        DocumentReference chatRef = db.collection("chats").document(t.chatId);
        DocumentReference msgRef = chatRef.collection("messages").document();

        Map<String, Object> meta = new HashMap<>();
        meta.put("lastMessage", preview);
        meta.put("lastSenderId", t.me);
        meta.put("updatedAt", msg.get("timestamp"));
        if (!t.group) { // direct chats are created by their first message
            Map<String, String> names = new HashMap<>();
            names.put(t.me, t.myName);
            names.put(t.otherUid, t.otherName);
            meta.put("members", Arrays.asList(t.me, t.otherUid));
            meta.put("memberNames", names);
            meta.put("group", false);
        }

        WriteBatch batch = db.batch();
        batch.set(chatRef, meta, SetOptions.merge());
        batch.set(msgRef, msg);
        return batch.commit();
    }
}
