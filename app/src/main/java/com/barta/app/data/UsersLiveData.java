package com.barta.app.data;

import androidx.lifecycle.LiveData;

import com.barta.app.model.User;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All users (Firestore users/), shared by every screen: names, photos, "about",
 * and the block / mute lists. One listener for the whole app instead of one per screen.
 */
public class UsersLiveData extends LiveData<Map<String, User>> {
    private static UsersLiveData instance;
    private ListenerRegistration registration;

    public static synchronized UsersLiveData get() {
        if (instance == null) instance = new UsersLiveData();
        return instance;
    }

    @Override
    protected void onActive() {
        registration = FirebaseFirestore.getInstance().collection("users")
                .addSnapshotListener((snap, e) -> {
                    if (snap == null) return;
                    Map<String, User> map = new LinkedHashMap<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        User u = d.toObject(User.class);
                        if (u == null) continue;
                        u.uid = d.getId();
                        map.put(u.uid, u);
                    }
                    setValue(map);
                });
    }

    @Override
    protected void onInactive() {
        if (registration != null) registration.remove();
        registration = null;
    }

    /** Forget the old account's data after sign out. Call on the main thread. */
    public static synchronized void reset() {
        if (instance != null) instance.setValue(null);
    }
}
