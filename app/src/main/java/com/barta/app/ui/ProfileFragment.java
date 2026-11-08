package com.barta.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.barta.app.BartaApp;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.FragmentProfileBinding;
import com.barta.app.model.User;
import com.barta.app.util.AppExecutors;
import com.barta.app.util.ImageUtil;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "You" tab: profile photo (small Base64 JPEG in Firestore), name, about, theme
 * (System / Light / Dark), blocked contacts and account actions.
 */
public class ProfileFragment extends Fragment {
    private static final String DEFAULT_ABOUT = "Hey there! I am using Barta.";

    private FragmentProfileBinding b;
    private FirebaseUser fbUser;
    private User meUser;
    private Map<String, User> allUsers;

    private final ActivityResultLauncher<String> photoPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onPhotoPicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentProfileBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fbUser = FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser == null) return;

        String name = fbUser.getDisplayName() != null && !fbUser.getDisplayName().isEmpty()
                ? fbUser.getDisplayName() : fbUser.getEmail();
        b.avatar.setName(name);
        b.tvName.setText(name);
        b.tvEmail.setText(fbUser.getEmail());
        b.tvAbout.setText(DEFAULT_ABOUT);
        refreshThemeLabel();

        UsersLiveData.get().observe(getViewLifecycleOwner(), map -> {
            if (map == null || b == null) return;
            allUsers = map;
            meUser = map.get(fbUser.getUid());
            if (meUser == null) return;
            b.avatar.setUser(meUser.name, meUser.photoBase64);
            b.tvName.setText(meUser.name);
            b.tvAbout.setText(meUser.about == null || meUser.about.isEmpty() ? DEFAULT_ABOUT : meUser.about);
            int blocked = meUser.blocked == null ? 0 : meUser.blocked.size();
            b.rowBlocked.setText(blocked == 0 ? "Blocked contacts" : "Blocked contacts (" + blocked + ")");
        });

        b.photoBox.setOnClickListener(v -> choosePhoto());
        b.rowName.setOnClickListener(v -> editText("Your name", b.tvName.getText().toString(), 40, this::saveName));
        b.rowAbout.setOnClickListener(v -> editText("About", b.tvAbout.getText().toString(), 120, this::saveAbout));
        b.rowTheme.setOnClickListener(v -> chooseTheme());
        b.rowBlocked.setOnClickListener(v -> showBlocked());
        b.rowNewGroup.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CreateGroupActivity.class)));
        b.rowSignOut.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).signOut();
        });
    }

    // ---- photo ----

    private void choosePhoto() {
        boolean hasPhoto = meUser != null && meUser.photoBase64 != null && !meUser.photoBase64.isEmpty();
        if (!hasPhoto) {
            photoPicker.launch("image/*");
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Profile photo")
                .setItems(new String[]{"Choose new photo", "Remove photo"}, (d, which) -> {
                    if (which == 0) photoPicker.launch("image/*");
                    else updateMe("photoBase64", FieldValue.delete(), "Photo removed");
                })
                .show();
    }

    private void onPhotoPicked(Uri uri) {
        if (uri == null || getContext() == null) return;
        android.content.ContentResolver cr = requireContext().getContentResolver();
        Toast.makeText(requireContext(), "Uploading photo…", Toast.LENGTH_SHORT).show();
        AppExecutors.IO.execute(() -> {
            try {
                // 256 px is plenty for an avatar and keeps the users list small (fewer bytes downloaded)
                String b64 = ImageUtil.uriToBase64(cr, uri,
                        ImageUtil.PROFILE_PHOTO_SIDE, ImageUtil.PROFILE_PHOTO_QUALITY);
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> updateMe("photoBase64", b64, "Photo updated"));
            } catch (Exception e) {
                if (getActivity() == null) return;
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(),
                        "Could not use this image: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---- name + about ----

    private interface OnText {
        void save(String text);
    }

    private void editText(String title, String current, int max, OnText onSave) {
        EditText et = new EditText(requireContext());
        et.setText(current);
        et.setSelection(et.getText().length());
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        FrameLayout box = new FrameLayout(requireContext());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String text = et.getText().toString().trim();
                    if (!text.isEmpty()) onSave.save(text);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveName(String name) {
        updateMe("name", name, "Name updated");
        fbUser.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name).build());
    }

    private void saveAbout(String about) {
        updateMe("about", about, "About updated");
    }

    private void updateMe(String field, Object value, String done) {
        FirebaseFirestore.getInstance().collection("users").document(fbUser.getUid())
                .update(field, value)
                .addOnSuccessListener(v -> {
                    if (getContext() != null) Toast.makeText(getContext(), done, Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    if (getContext() != null) Toast.makeText(getContext(),
                            "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    // ---- dark mode ----

    private void refreshThemeLabel() {
        int mode = BartaApp.getThemeMode(requireContext());
        String label = mode == AppCompatDelegate.MODE_NIGHT_YES ? "Dark"
                : mode == AppCompatDelegate.MODE_NIGHT_NO ? "Light" : "System default";
        b.rowTheme.setText("Theme: " + label);
    }

    private void chooseTheme() {
        int[] modes = {AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES};
        int current = BartaApp.getThemeMode(requireContext());
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == current) checked = i;
        new AlertDialog.Builder(requireContext())
                .setTitle("Choose theme")
                .setSingleChoiceItems(new String[]{"System default", "Light", "Dark"}, checked, (d, which) -> {
                    d.dismiss();
                    // The activity is recreated automatically with the new colours
                    BartaApp.setThemeMode(requireContext().getApplicationContext(), modes[which]);
                })
                .show();
    }

    // ---- blocked contacts ----

    private void showBlocked() {
        if (meUser == null || meUser.blocked == null || meUser.blocked.isEmpty()) {
            Toast.makeText(requireContext(),
                    "No blocked contacts. Block someone from the chat menu.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> uids = new ArrayList<>(meUser.blocked);
        String[] names = new String[uids.size()];
        for (int i = 0; i < uids.size(); i++) {
            User u = allUsers == null ? null : allUsers.get(uids.get(i));
            names[i] = u == null || u.name == null ? "Unknown user" : u.name;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Blocked contacts (tap to unblock)")
                .setItems(names, (d, which) -> updateMe("blocked",
                        FieldValue.arrayRemove(uids.get(which)), names[which] + " unblocked"))
                .setPositiveButton("Close", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        b = null;
        super.onDestroyView();
    }
}
