package com.barta.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.barta.app.databinding.ActivityRegisterBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {
    private ActivityRegisterBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnRegister.setOnClickListener(v -> register());
        b.tvGoLogin.setOnClickListener(v -> finish());
    }

    private void register() {
        String name = b.etName.getText().toString().trim();
        String email = b.etEmail.getText().toString().trim();
        String pass = b.etPassword.getText().toString();
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(email)) {
            Toast.makeText(this, "Fill in all fields", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pass.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show();
            return;
        }
        setLoading(true);
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                setLoading(false);
                String msg = task.getException() == null ? "Registration failed" : task.getException().getMessage();
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return;
            }
            FirebaseUser user = auth.getCurrentUser();
            if (user == null) {
                setLoading(false);
                return;
            }
            user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name).build())
                    .addOnCompleteListener(t -> saveProfile(user.getUid(), name, email));
        });
    }

    private void saveProfile(String uid, String name, String email) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("uid", uid);
        doc.put("name", name);
        doc.put("email", email.toLowerCase());
        doc.put("about", "Hey there! I am using Barta.");
        FirebaseFirestore.getInstance().collection("users").document(uid).set(doc)
                .addOnCompleteListener(t -> {
                    setLoading(false);
                    if (t.isSuccessful()) {
                        Intent i = new Intent(this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    } else {
                        Toast.makeText(this, "Could not save profile. Check Firestore rules.",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void setLoading(boolean loading) {
        b.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        b.btnRegister.setEnabled(!loading);
    }
}
