package com.barta.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<MessageEntity> messages);

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    List<MessageEntity> getForChat(String chatId);

    /** Search inside one chat. Uses the local cache, so it also works offline. */
    @Query("SELECT id FROM messages WHERE chatId = :chatId AND deleted = 0 "
            + "AND text LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    List<String> search(String chatId, String query);

    // ---- scheduled messages ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertScheduled(ScheduledEntity s);

    @Query("SELECT * FROM scheduled WHERE chatId = :chatId ORDER BY sendAt ASC")
    List<ScheduledEntity> getScheduled(String chatId);

    @Query("DELETE FROM scheduled WHERE workId = :workId")
    void deleteScheduled(String workId);
}
