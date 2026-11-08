package com.barta.app.model;

import java.util.List;

/**
 * A registered Barta user (Firestore: users/{uid}).
 * photoBase64 is a small (256 px) JPEG stored as text, because Firebase Storage needs the paid plan.
 * blocked = uids this user blocked, muted = chat ids this user muted.
 */
public class User {
    public String uid;
    public String name;
    public String email;
    public String about;
    public String photoBase64;
    public List<String> blocked;
    public List<String> muted;

    public User() { }   // required by Firestore

    public User(String uid, String name, String email) {
        this.uid = uid;
        this.name = name;
        this.email = email;
    }

    public boolean hasBlocked(String otherUid) {
        return blocked != null && otherUid != null && blocked.contains(otherUid);
    }

    public boolean hasMuted(String chatId) {
        return muted != null && chatId != null && muted.contains(chatId);
    }
}
