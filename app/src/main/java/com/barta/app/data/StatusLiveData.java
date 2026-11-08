package com.barta.app.data;

import com.barta.app.model.UserStatus;

/** Everyone's online / last seen (Realtime Database: status/). One shared instance. */
public final class StatusLiveData {
    private static RtdbLiveData<UserStatus> instance;

    private StatusLiveData() { }

    public static synchronized RtdbLiveData<UserStatus> get() {
        if (instance == null) {
            instance = new RtdbLiveData<>(Rtdb.root().child("status"),
                    child -> child.getValue(UserStatus.class));
        }
        return instance;
    }
}
