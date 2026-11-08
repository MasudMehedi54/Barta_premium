package com.barta.app.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.barta.app.adapter.UserAdapter;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.ActivityCreateGroupBinding;
import com.barta.app.model.Chat;
import com.barta.app.model.User;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreateGroupActivity extends AppCompatActivity {
    private ActivityCreateGroupBinding b;
    private UserAdapter adapter;
    private FirebaseUser me;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCreateGroupBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) {
            finish();
            return;
        }
        setSupportActionBar(b.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        adapter = new UserAdapter(true, null);
        b.rvUsers.setLayoutManager(new LinearLayoutManager(this));
        b.rvUsers.setAdapter(adapter);

        UsersLiveData.get().observe(this, map -> {
            if (map == null) return;
            List<User> users = new ArrayList<>();
            for (User u : map.values()) {
                if (!u.uid.equals(me.getUid())) users.add(u);
            }
            adapter.setUsers(users);
        });

        b.btnCreate.setOnClickListener(v -> createGroup());
    }

    private void createGroup() {
        String name = b.etGroupName.getText().toString().trim();
        List<User> picked = adapter.getSelectedUsers();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "Enter a group name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (picked.size() < 2) {
            Toast.makeText(this, "Select at least 2 members", Toast.LENGTH_SHORT).show();
            return;
        }
        String myName = me.getDisplayName() != null ? me.getDisplayName() : me.getEmail();
        List<String> members = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        members.add(me.getUid());
        names.put(me.getUid(), myName);
        for (User u : picked) {
            members.add(u.uid);
            names.put(u.uid, u.name);
        }

        DocumentReference ref = FirebaseFirestore.getInstance().collection("chats").document();
        Map<String, Object> doc = new HashMap<>();
        doc.put("members", members);
        doc.put("memberNames", names);
        doc.put("group", true);
        doc.put("groupName", name);
        doc.put("lastMessage", myName + " created the group");
        doc.put("lastSenderId", me.getUid());
        doc.put("updatedAt", System.currentTimeMillis());

        b.btnCreate.setEnabled(false);
        ref.set(doc).addOnCompleteListener(task -> {
            b.btnCreate.setEnabled(true);
            if (!task.isSuccessful()) {
                Toast.makeText(this, "Could not create group", Toast.LENGTH_LONG).show();
                return;
            }
            Chat chat = new Chat();
            chat.id = ref.getId();
            chat.group = true;
            chat.groupName = name;
            startActivity(ChatActivity.intentFor(this, chat, me.getUid()));
            finish();
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
