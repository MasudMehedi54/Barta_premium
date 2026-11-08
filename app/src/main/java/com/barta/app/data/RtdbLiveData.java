package com.barta.app.data;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a Realtime Database node into LiveData<Map<key, T>>.
 * The listener is attached only while a screen is visible (onActive) and removed after
 * (onInactive), which keeps us far below the free plan's 100 simultaneous connections/downloads.
 */
public class RtdbLiveData<T> extends LiveData<Map<String, T>> {

    public interface Parser<T> {
        T parse(DataSnapshot child);
    }

    private final Query query;
    private final Parser<T> parser;
    private final ValueEventListener listener = new ValueEventListener() {
        @Override
        public void onDataChange(@NonNull DataSnapshot snap) {
            Map<String, T> map = new HashMap<>();
            for (DataSnapshot child : snap.getChildren()) {
                T value = parser.parse(child);
                if (value != null && child.getKey() != null) map.put(child.getKey(), value);
            }
            setValue(map);
        }

        @Override
        public void onCancelled(@NonNull DatabaseError error) { }
    };

    public RtdbLiveData(Query query, Parser<T> parser) {
        this.query = query;
        this.parser = parser;
    }

    @Override
    protected void onActive() {
        query.addValueEventListener(listener);
    }

    @Override
    protected void onInactive() {
        query.removeEventListener(listener);
    }

    /** A map of uid/chatId -> millis (used for reads and userReads). */
    public static RtdbLiveData<Long> longs(Query query) {
        return new RtdbLiveData<>(query, c -> c.getValue(Long.class));
    }
}
