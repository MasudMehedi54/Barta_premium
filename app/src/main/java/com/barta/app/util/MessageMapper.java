package com.barta.app.util;

import com.barta.app.db.MessageEntity;
import com.barta.app.model.Message;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Converts between the Firestore model (Message) and the Room cache row (MessageEntity). */
public final class MessageMapper {
    private MessageMapper() { }

    public static MessageEntity toEntity(Message m, String chatId) {
        MessageEntity e = new MessageEntity();
        e.id = m.id;
        e.chatId = chatId;
        e.senderId = m.senderId;
        e.senderName = m.senderName;
        e.text = m.text;
        e.imageBase64 = m.imageBase64;
        e.timestamp = m.timestamp;
        e.lat = m.lat;
        e.lng = m.lng;
        e.replyToId = m.replyToId;
        e.replyToSender = m.replyToSender;
        e.replyToText = m.replyToText;
        e.edited = m.edited;
        e.deleted = m.deleted;
        e.callType = m.callType;
        e.callStatus = m.callStatus;
        e.callDuration = m.callDuration;
        e.reactionsJson = m.reactions == null || m.reactions.isEmpty()
                ? null : new JSONObject(m.reactions).toString();
        return e;
    }

    public static Message fromEntity(MessageEntity e) {
        Message m = new Message();
        m.id = e.id;
        m.senderId = e.senderId;
        m.senderName = e.senderName;
        m.text = e.text;
        m.imageBase64 = e.imageBase64;
        m.timestamp = e.timestamp;
        m.lat = e.lat;
        m.lng = e.lng;
        m.replyToId = e.replyToId;
        m.replyToSender = e.replyToSender;
        m.replyToText = e.replyToText;
        m.edited = e.edited;
        m.deleted = e.deleted;
        m.callType = e.callType;
        m.callStatus = e.callStatus;
        m.callDuration = e.callDuration;
        if (e.reactionsJson != null) {
            try {
                JSONObject o = new JSONObject(e.reactionsJson);
                Map<String, String> map = new HashMap<>();
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    map.put(k, o.getString(k));
                }
                m.reactions = map;
            } catch (JSONException ignored) { }
        }
        return m;
    }
}
