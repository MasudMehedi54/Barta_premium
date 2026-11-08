package com.barta.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.work.WorkManager;

import com.barta.app.R;
import com.barta.app.data.Presence;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.ActivityMainBinding;
import com.barta.app.db.AppDatabase;
import com.barta.app.receiver.NetworkReceiver;
import com.barta.app.service.MessageListenerService;
import com.barta.app.util.AppExecutors;
import com.barta.app.work.ScheduledMessageWorker;
import com.google.firebase.auth.FirebaseAuth;

public class MainActivity extends AppCompatActivity {
    private static final String[] TITLES = {"Barta", "Contacts", "You"};
    private static final int[] NAV_IDS = {R.id.nav_chats, R.id.nav_contacts, R.id.nav_profile};

    private ActivityMainBinding b;
    private NetworkReceiver networkReceiver;

    /** Back from another tab returns to Chats first, like WhatsApp. */
    private final OnBackPressedCallback backToChats = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            b.bottomNav.setSelectedItemId(R.id.nav_chats);
        }
    };

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            goToLogin();
            return;
        }
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);

        b.pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                if (position == 0) return new ChatsFragment();
                if (position == 1) return new UsersFragment();
                return new ProfileFragment();
            }

            @Override
            public int getItemCount() {
                return TITLES.length;
            }
        });
        // WhatsApp-style bottom navigation instead of swipe tabs
        b.pager.setUserInputEnabled(false);
        b.pager.setOffscreenPageLimit(TITLES.length);
        b.bottomNav.setOnItemSelectedListener(item -> {
            for (int i = 0; i < NAV_IDS.length; i++) {
                if (NAV_IDS[i] == item.getItemId()) showPage(i);
            }
            return true;
        });
        // "New chat" button opens the contact list, like WhatsApp
        b.fabNewChat.setOnClickListener(v -> b.bottomNav.setSelectedItemId(R.id.nav_contacts));
        getOnBackPressedDispatcher().addCallback(this, backToChats);
        showPage(0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        MessageListenerService.start(this);
        Presence.goOnline();   // the app was already open when we logged in
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (b == null) return;
        networkReceiver = new NetworkReceiver(online ->
                b.tvOffline.setVisibility(online ? View.GONE : View.VISIBLE));
        ContextCompat.registerReceiver(this, networkReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        b.tvOffline.setVisibility(NetworkReceiver.isOnline(this) ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onStop() {
        if (networkReceiver != null) {
            unregisterReceiver(networkReceiver);
            networkReceiver = null;
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_new_group) {
            startActivity(new Intent(this, CreateGroupActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_sign_out) {
            signOut();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showPage(int index) {
        b.pager.setCurrentItem(index, false);
        b.toolbar.setTitle(TITLES[index]);
        b.toolbar.setTitleTextColor(ContextCompat.getColor(this,
                index == 0 ? R.color.barta_primary : R.color.text_primary));
        if (index == 0) b.fabNewChat.show(); else b.fabNewChat.hide();
        backToChats.setEnabled(index != 0);
    }

    public void signOut() {
        MessageListenerService.stop(this);
        Presence.signOut();   // write "last seen" while we are still signed in
        WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(ScheduledMessageWorker.TAG_ALL);
        AppExecutors.IO.execute(() -> AppDatabase.get(getApplicationContext()).clearAllTables());
        UsersLiveData.reset();
        FirebaseAuth.getInstance().signOut();
        goToLogin();
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
