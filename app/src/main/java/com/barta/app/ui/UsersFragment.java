package com.barta.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.barta.app.adapter.UserAdapter;
import com.barta.app.data.StatusLiveData;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.FragmentUsersBinding;
import com.barta.app.model.User;
import com.barta.app.util.AppExecutors;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Find users: live list, search box, and a Content Provider (Contacts) based friend finder. */
public class UsersFragment extends Fragment {
    private FragmentUsersBinding b;
    private UserAdapter adapter;
    private boolean contactsMode = false;

    private final ActivityResultLauncher<String> contactsPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    readContacts();
                } else {
                    Toast.makeText(requireContext(), "Contacts permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentUsersBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid == null) return;

        adapter = new UserAdapter(false,
                user -> startActivity(ChatActivity.intentForUser(requireContext(), user)));
        b.rvUsers.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvUsers.setAdapter(adapter);

        b.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                adapter.setQuery(newText);
                return true;
            }
        });

        b.rowNewGroup.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CreateGroupActivity.class)));

        b.btnContacts.setOnClickListener(v -> {
            if (contactsMode) {
                contactsMode = false;
                adapter.setEmailFilter(null);
                b.btnContacts.setText("Find friends from my contacts");
            } else {
                contactsPermission.launch(Manifest.permission.READ_CONTACTS);
            }
        });

        // Shared live user list (photos, about, block lists) + online dots from the Realtime Database
        UsersLiveData.get().observe(getViewLifecycleOwner(), map -> {
            if (map == null) return;
            List<User> users = new ArrayList<>();
            for (User u : map.values()) {
                if (!u.uid.equals(myUid)) users.add(u);
            }
            adapter.setMe(map.get(myUid));
            adapter.setUsers(users);
        });
        StatusLiveData.get().observe(getViewLifecycleOwner(), adapter::setStatuses);
    }

    /** Reads e-mail addresses through the system Contacts Content Provider. */
    private void readContacts() {
        Context app = requireContext().getApplicationContext();
        AppExecutors.IO.execute(() -> {
            Set<String> emails = new HashSet<>();
            try (Cursor c = app.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                    null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String address = c.getString(0);
                    if (address != null) emails.add(address.toLowerCase(Locale.ROOT));
                }
            }
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (b == null) return;
                contactsMode = true;
                adapter.setEmailFilter(emails);
                b.btnContacts.setText("Show all users");
                Toast.makeText(app, emails.size() + " contact emails checked", Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    public void onDestroyView() {
        b = null;
        super.onDestroyView();
    }
}
