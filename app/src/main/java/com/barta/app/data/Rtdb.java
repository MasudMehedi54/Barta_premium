package com.barta.app.data;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/**
 * Firebase Realtime Database (free on the Spark plan) is used for small, fast-changing data:
 *
 *   status/{uid}               { online, lastSeen }        online / last seen
 *   typing/{chatId}/{uid}      true                         "typing..." indicator
 *   reads/{chatId}/{uid}       millis                       seen ticks inside a chat
 *   userReads/{uid}/{chatId}   millis                       unread dots in the chat list
 *
 * It has onDisconnect(): the server itself flips you to offline when the phone loses
 * connection or the app is killed. Firestore cannot do that, which is why both are used.
 */
public final class Rtdb {
    private Rtdb() { }

    public static DatabaseReference root() {
        return FirebaseDatabase.getInstance().getReference();
    }

    public static DatabaseReference status(String uid) {
        return root().child("status").child(uid);
    }

    public static DatabaseReference typing(String chatId) {
        return root().child("typing").child(chatId);
    }

    public static DatabaseReference reads(String chatId) {
        return root().child("reads").child(chatId);
    }

    public static DatabaseReference userReads(String uid) {
        return root().child("userReads").child(uid);
    }
}
