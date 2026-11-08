package com.barta.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.barta.app.R;
import com.barta.app.databinding.ItemUserBinding;
import com.barta.app.model.User;
import com.barta.app.model.UserStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shows users with photo, "about" and online dot; supports search, contact filter and check boxes. */
public class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {

    public interface OnUserClick {
        void onClick(User user);
    }

    private final List<User> all = new ArrayList<>();
    private final List<User> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final boolean selectable;
    private final OnUserClick listener;
    private String query = "";
    private Set<String> emailFilter = null;
    private Map<String, UserStatus> statuses = new HashMap<>();
    private User meUser;

    public UserAdapter(boolean selectable, OnUserClick listener) {
        this.selectable = selectable;
        this.listener = listener;
    }

    public void setUsers(List<User> users) {
        all.clear();
        all.addAll(users);
        applyFilter();
    }

    /** My own user document, used to mark people I blocked. */
    public void setMe(User me) {
        meUser = me;
        notifyDataSetChanged();
    }

    public void setStatuses(Map<String, UserStatus> statuses) {
        this.statuses = statuses == null ? new HashMap<>() : statuses;
        notifyDataSetChanged();
    }

    public void setQuery(String q) {
        query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public void setEmailFilter(Set<String> emails) {
        emailFilter = emails;
        applyFilter();
    }

    public List<User> getSelectedUsers() {
        List<User> result = new ArrayList<>();
        for (User u : all) {
            if (selected.contains(u.uid)) result.add(u);
        }
        return result;
    }

    private void applyFilter() {
        shown.clear();
        for (User u : all) {
            String name = u.name == null ? "" : u.name.toLowerCase(Locale.ROOT);
            String email = u.email == null ? "" : u.email.toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !name.contains(query) && !email.contains(query)) continue;
            if (emailFilter != null && !emailFilter.contains(email)) continue;
            shown.add(u);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemUserBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        User u = shown.get(position);
        boolean iBlocked = meUser != null && meUser.hasBlocked(u.uid);
        boolean theyBlocked = meUser != null && u.hasBlocked(meUser.uid);
        h.b.avatar.setUser(u.name, theyBlocked ? null : u.photoBase64);
        UserStatus s = statuses.get(u.uid);
        h.b.avatar.setOnline(!iBlocked && !theyBlocked && s != null && s.online);
        h.b.tvName.setText(u.name);

        if (iBlocked) {
            h.b.tvEmail.setText("Blocked");
            h.b.tvEmail.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.error_red));
        } else {
            boolean hasAbout = u.about != null && !u.about.isEmpty();
            h.b.tvEmail.setText(hasAbout ? u.about : u.email);
            h.b.tvEmail.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.text_muted));
        }

        h.b.cbSelect.setVisibility(selectable ? View.VISIBLE : View.GONE);
        h.b.cbSelect.setChecked(selected.contains(u.uid));
        h.itemView.setOnClickListener(v -> {
            if (selectable) {
                if (!selected.remove(u.uid)) selected.add(u.uid);
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
            } else if (listener != null) {
                listener.onClick(u);
            }
        });
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemUserBinding b;

        VH(ItemUserBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
