package com.barta.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.barta.app.R;
import com.barta.app.adapter.ChatAdapter;
import com.barta.app.data.Rtdb;
import com.barta.app.data.RtdbLiveData;
import com.barta.app.data.StatusLiveData;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.FragmentChatsBinding;
import com.barta.app.model.Chat;
import com.barta.app.util.AppExecutors;
import com.barta.app.util.QuoteFetcher;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Conversation list (search + All / Unread / Groups filter) + "quote of the day" from a free JSON API.
 * Unread dots come from the Realtime Database (userReads/{me}).
 */
public class ChatsFragment extends Fragment {
    private FragmentChatsBinding b;
    private final List<Chat> allChats = new ArrayList<>();   // everything from Firestore
    private final List<Chat> chats = new ArrayList<>();      // what the list shows
    private String query = "";
    private int filter = R.id.chipAll;
    private ChatAdapter adapter;
    private ListenerRegistration registration;
    private String myUid;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentChatsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;

        adapter = new ChatAdapter(chats, myUid,
                chat -> startActivity(ChatActivity.intentFor(requireContext(), chat, myUid)));
        b.rvChats.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvChats.setAdapter(adapter);

        b.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String q) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String q) {
                query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
                applyFilter();
                return true;
            }
        });
        b.chipFilters.setOnCheckedStateChangeListener((group, ids) -> {
            filter = ids.isEmpty() ? R.id.chipAll : ids.get(0);
            applyFilter();
        });

        // Live data shared with other screens (attached only while this screen is visible)
        UsersLiveData.get().observe(getViewLifecycleOwner(), users -> {
            adapter.setUsers(users);
            applyFilter();
        });
        StatusLiveData.get().observe(getViewLifecycleOwner(), adapter::setStatuses);
        RtdbLiveData.longs(Rtdb.userReads(myUid)).observe(getViewLifecycleOwner(), reads -> {
            adapter.setMyReads(reads);
            applyFilter();
        });

        registration = FirebaseFirestore.getInstance().collection("chats")
                .whereArrayContains("members", myUid)
                .addSnapshotListener((snap, e) -> {
                    if (snap == null || b == null) return;
                    allChats.clear();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Chat c = d.toObject(Chat.class);
                        if (c == null) continue;
                        c.id = d.getId();
                        if (c.lastMessage == null || c.lastMessage.isEmpty()) continue;
                        allChats.add(c);
                    }
                    Collections.sort(allChats, (x, y) -> Long.compare(y.updatedAt, x.updatedAt));
                    applyFilter();
                });

        loadQuote();
    }

    private void applyFilter() {
        if (b == null || adapter == null) return;
        chats.clear();
        for (Chat c : allChats) {
            if (filter == R.id.chipGroups && !c.group) continue;
            if (filter == R.id.chipUnread && !adapter.isUnread(c)) continue;
            if (!query.isEmpty() && !adapter.titleOf(c).toLowerCase(Locale.ROOT).contains(query)) continue;
            chats.add(c);
        }
        adapter.notifyDataSetChanged();
        b.tvEmpty.setText(allChats.isEmpty()
                ? "No chats yet.\nTap the button below to start one."
                : "No chats match.");
        b.tvEmpty.setVisibility(chats.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadQuote() {
        AppExecutors.IO.execute(() -> {
            String quote = QuoteFetcher.fetch();
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (b == null) return;
                b.tvQuote.setText(quote != null ? quote : "Say hello to someone today.");
            });
        });
    }

    @Override
    public void onDestroyView() {
        if (registration != null) registration.remove();
        registration = null;
        b = null;
        super.onDestroyView();
    }
}
