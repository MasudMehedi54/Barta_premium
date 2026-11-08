package com.barta.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.barta.app.R;
import com.google.firebase.auth.FirebaseAuth;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean loggedIn = FirebaseAuth.getInstance().getCurrentUser() != null;
            // Explicit Intent
            startActivity(new Intent(this, loggedIn ? MainActivity.class : LoginActivity.class));
            finish();
        }, 1200);
    }
}
