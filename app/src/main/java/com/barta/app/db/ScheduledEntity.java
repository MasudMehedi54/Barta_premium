package com.barta.app.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A message waiting to be sent by WorkManager. WorkManager cannot show us the text of a
 * pending job, so we keep a copy here to list and cancel scheduled messages.
 */
@Entity(tableName = "scheduled", indices = {@Index("chatId")})
public class ScheduledEntity {
    @PrimaryKey
    @NonNull
    public String workId = "";   // WorkManager request UUID
    public String chatId;
    public String text;
    public long sendAt;
}
