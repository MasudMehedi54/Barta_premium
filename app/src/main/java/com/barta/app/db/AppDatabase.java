package com.barta.app.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Version 3 adds call-log columns. Version 2 added reply/edit/delete/reaction/location columns and the scheduled table.
 * The messages table is only a cache of Firestore, so a destructive migration is safe:
 * the data is downloaded again the next time a chat opens.
 */
@Database(entities = {MessageEntity.class, ScheduledEntity.class}, version = 3, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    public abstract MessageDao messageDao();

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "barta.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
