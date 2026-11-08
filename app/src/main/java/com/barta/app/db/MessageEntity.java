package com.barta.app.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Offline copy of a message. reactionsJson is the reactions map saved as a JSON string. */
@Entity(tableName = "messages", indices = {@Index("chatId")})
public class MessageEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String chatId;
    public String senderId;
    public String senderName;
    public String text;
    public String imageBase64;
    public long timestamp;
    public Double lat;
    public Double lng;
    public String replyToId;
    public String replyToSender;
    public String replyToText;
    public boolean edited;
    public boolean deleted;
    public String reactionsJson;
    public String callType;
    public String callStatus;
    public long callDuration;
}
