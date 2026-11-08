package com.barta.app;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.barta.app.data.Presence;

/**
 * Application class:
 * - applies the saved theme (System / Light / Dark) before any screen is drawn
 * - marks the user online while ANY Barta screen is visible, offline when the app is in the background
 */
public class BartaApp extends Application {
    private static final String PREFS = "barta_prefs";
    private static final String KEY_THEME = "theme_mode";

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(getThemeMode(this));

        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                Presence.goOnline();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                Presence.goOffline();
            }
        });
    }

    /** One of AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM / MODE_NIGHT_NO / MODE_NIGHT_YES. */
    public static int getThemeMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static void setThemeMode(Context ctx, int mode) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_THEME, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
