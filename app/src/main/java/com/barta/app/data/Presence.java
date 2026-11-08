package com.barta.app.data;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Online / last seen, the classic Realtime Database presence pattern:
 * 1. listen to the special path ".info/connected"
 * 2. when connected, register onDisconnect() -> offline (the SERVER runs it if we vanish)
 * 3. then write online = true
 * BartaApp calls goOnline() when the app comes to the foreground and goOffline() when it leaves.
 */
public final class Presence {
    private static final String TAG = "Presence";
    private static ValueEventListener connectedListener;
    private static DatabaseReference connectedRef;
    private static String activeUid;

    private Presence() { }

    private static Map<String, Object> state(boolean online) {
        Map<String, Object> m = new HashMap<>();
        m.put("online", online);
        m.put("lastSeen", ServerValue.TIMESTAMP);
        return m;
    }

    public static synchronized void goOnline() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || connectedListener != null) return;
        activeUid = uid;
        DatabaseReference me = Rtdb.status(uid);
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                Boolean connected = snap.getValue(Boolean.class);
                if (connected == null || !connected) return;
                me.onDisconnect().setValue(state(false))
                        .addOnSuccessListener(v -> me.setValue(state(true)));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "presence cancelled: " + error.getMessage());
            }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    /** App went to the background: show "last seen" now instead of waiting for a disconnect. */
    public static synchronized void goOffline() {
        if (connectedListener != null && connectedRef != null) {
            connectedRef.removeEventListener(connectedListener);
        }
        connectedListener = null;
        connectedRef = null;
        if (activeUid != null) Rtdb.status(activeUid).setValue(state(false));
        activeUid = null;
    }

    /** Call BEFORE FirebaseAuth.signOut(), because the rules need the user to be signed in. */
    public static void signOut() {
        goOffline();
    }
}
