package com.barta.app.model;

import java.util.List;
import java.util.Map;

/** A one-to-one or group conversation (Firestore: chats/{chatId}). */
public class Chat {
    public String id;                       // filled manually from the document id
    public List<String> members;
    public Map<String, String> memberNames; // uid -> display name
    public boolean group;
    public String groupName;
    public String lastMessage;
    public String lastSenderId;
    public long updatedAt;

    public Chat() { }

    public String otherUid(String me) {
        if (members != null) {
            for (String m : members) {
                if (!m.equals(me)) return m;
            }
        }
        return "";
    }

    public String title(String me) {
        if (group) return groupName == null ? "Group" : groupName;
        String name = memberNames == null ? null : memberNames.get(otherUid(me));
        return name == null ? "Chat" : name;
    }
}
