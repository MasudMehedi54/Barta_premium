package com.barta.app.model;

import com.google.firebase.firestore.Exclude;

import java.util.Map;

/**
 * A chat message (Firestore: chats/{chatId}/messages/{id}).
 * Kinds: text, photo (imageBase64), location (lat/lng). Replies copy a short quote of the original.
 */
public class Message {
    public String id;
    public String senderId;
    public String senderName;
    public String text;
    public String imageBase64;
    public long timestamp;

    // Share location (null when not a location message)
    public Double lat;
    public Double lng;

    // Reply: a copy of the quoted message, so the quote still shows offline
    public String replyToId;
    public String replyToSender;
    public String replyToText;

    // Edit / delete for everyone
    public boolean edited;
    public boolean deleted;

    // Reactions: uid -> emoji
    public Map<String, String> reactions;

    // Call log line written by the caller after a call (v3)
    public String callType;      // "audio" or "video" (null = not a call)
    public String callStatus;    // "completed", "missed", "declined", "busy"
    public long callDuration;    // seconds

    /** True while the message is only on this phone (not yet saved on the server). Not stored. */
    @Exclude
    public boolean pending;

    public Message() { }

    @Exclude
    public boolean isCall() {
        return callType != null && !callType.isEmpty();
    }

    /** "📞 Voice call · 2:31", "📹 Missed video call", "📞 Voice call · No answer". */
    @Exclude
    public String callLabel(boolean mine) {
        boolean videoCall = "video".equals(callType);
        String icon = videoCall ? "\uD83D\uDCF9 " : "\uD83D\uDCDE ";
        String kind = videoCall ? "Video call" : "Voice call";
        if ("completed".equals(callStatus)) {
            return icon + kind + " \u00B7 " + String.format(java.util.Locale.US, "%d:%02d",
                    callDuration / 60, callDuration % 60);
        }
        if (!mine) return icon + "Missed " + kind.toLowerCase(java.util.Locale.ROOT);
        if ("declined".equals(callStatus)) return icon + kind + " \u00B7 Declined";
        if ("busy".equals(callStatus)) return icon + kind + " \u00B7 Busy";
        return icon + kind + " \u00B7 No answer";
    }

    @Exclude
    public boolean isLocation() {
        return lat != null && lng != null;
    }

    @Exclude
    public boolean isImage() {
        return imageBase64 != null && !imageBase64.isEmpty();
    }

    @Exclude
    public boolean hasText() {
        return text != null && !text.isEmpty();
    }

    /** One-line summary used for the chat list, notifications and reply quotes. */
    @Exclude
    public String preview() {
        if (deleted) return "🚫 This message was deleted";
        if (isCall()) return "video".equals(callType) ? "\uD83D\uDCF9 Video call" : "\uD83D\uDCDE Voice call";
        if (isLocation()) return "📍 Location";
        if (isImage()) return hasText() ? "📷 " + text : "📷 Photo";
        return text == null ? "" : text;
    }
}
