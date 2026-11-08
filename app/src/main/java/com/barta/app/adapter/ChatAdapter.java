package com.barta.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.barta.app.R;
import com.barta.app.databinding.ItemChatBinding;
import com.barta.app.model.Chat;
import com.barta.app.model.User;
import com.barta.app.model.UserStatus;
import com.barta.app.util.ChatUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Chat list (conversation overview) with photos, online dots, unread dots and mute icons. */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    public interface OnChatClick {
        void onClick(Chat chat);
    }

    private final List<Chat> chats;
    private final String me;
    private final OnChatClick listener;

    private Map<String, User> users = new HashMap<>();
    private Map<String, UserStatus> statuses = new HashMap<>();
    private Map<String, Long> myReads = new HashMap<>();

    public ChatAdapter(List<Chat> chats, String me, OnChatClick listener) {
        this.chats = chats;
        this.me = me;
        this.listener = listener;
    }

    public void setUsers(Map<String, User> users) {
        this.users = users == null ? new HashMap<>() : users;
        notifyDataSetChanged();
    }

    public void setStatuses(Map<String, UserStatus> statuses) {
        this.statuses = statuses == null ? new HashMap<>() : statuses;
        notifyDataSetChanged();
    }

    public void setMyReads(Map<String, Long> reads) {
        this.myReads = reads == null ? new HashMap<>() : reads;
        notifyDataSetChanged();
    }

    /** Direct chats show the other person's current name (they may have changed it). */
    public String titleOf(Chat c) {
        if (!c.group) {
            User other = users.get(c.otherUid(me));
            if (other != null && other.name != null) return other.name;
        }
        return c.title(me);
    }

    public boolean isUnread(Chat c) {
        if (me.equals(c.lastSenderId)) return false;
        Long read = myReads.get(c.id);
        return read == null || read < c.updatedAt;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemChatBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Chat c = chats.get(position);
        String title = titleOf(c);
        User meUser = users.get(me);

        if (c.group) {
            h.b.avatar.setUser(title, null);
            h.b.avatar.setOnline(false);
        } else {
            String other = c.otherUid(me);
            User u = users.get(other);
            boolean blocked = (u != null && u.hasBlocked(me)) || (meUser != null && meUser.hasBlocked(other));
            h.b.avatar.setUser(title, u == null || blocked ? null : u.photoBase64);
            UserStatus s = statuses.get(other);
            h.b.avatar.setOnline(!blocked && s != null && s.online);
        }
        h.b.tvTitle.setText(title);

        boolean mine = me.equals(c.lastSenderId);
        // WhatsApp style: a tick for my own last message, "Name: " for others in groups
        h.b.ivTick.setVisibility(mine ? View.VISIBLE : View.GONE);
        String prefix = "";
        String last = c.lastMessage == null ? "" : c.lastMessage;
        if (!mine && c.group && c.memberNames != null) {
            String sender = c.memberNames.get(c.lastSenderId);
            if (sender != null && !last.startsWith(sender)) prefix = sender + ": ";
        }
        h.b.tvLast.setText(prefix + last);
        h.b.tvTime.setText(ChatUtil.listTime(c.updatedAt));

        boolean unread = isUnread(c);
        h.b.ivUnread.setVisibility(unread ? View.VISIBLE : View.GONE);
        h.b.tvTime.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                unread ? R.color.barta_primary : R.color.text_muted));
        h.b.tvLast.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                unread ? R.color.text_primary : R.color.text_muted));
        h.b.ivMuted.setVisibility(meUser != null && meUser.hasMuted(c.id) ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> listener.onClick(c));
    }

    @Override
    public int getItemCount() {
        return chats.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemChatBinding b;

        VH(ItemChatBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
