package com.barta.app.util;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Share location WITHOUT the Google Maps API (no key, no billing):
 * - the position comes from Android's own LocationManager (GPS / network)
 * - it is sent as numbers and opened with a geo: link, which any map app understands
 */
public final class LocationHelper {
    private static final long FRESH_MS = 2 * 60 * 1000;   // reuse a fix younger than 2 minutes
    private static final long TIMEOUT_MS = 15_000;

    public interface Callback {
        void onLocation(@Nullable Location location, @Nullable String error);
    }

    private LocationHelper() { }

    /** Caller must already hold ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION. */
    @SuppressLint("MissingPermission")
    public static void getCurrent(Context ctx, Callback cb) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            cb.onLocation(null, "Location is not available on this device");
            return;
        }
        boolean gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!gps && !net) {
            cb.onLocation(null, "Turn on Location in your phone settings");
            return;
        }

        Location last = bestLastKnown(lm);
        if (last != null && System.currentTimeMillis() - last.getTime() < FRESH_MS) {
            cb.onLocation(last, null);
            return;
        }

        String provider = gps ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        Handler main = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};

        // If no fix arrives in time (e.g. indoors with GPS only), fall back to the last known one
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            Location fallback = bestLastKnown(lm);
            cb.onLocation(fallback, fallback == null ? "Could not get your location, try again outside" : null);
        };
        main.postDelayed(timeout, TIMEOUT_MS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx), loc -> {
                if (done[0]) return;
                done[0] = true;
                main.removeCallbacks(timeout);
                Location result = loc != null ? loc : bestLastKnown(lm);
                cb.onLocation(result, result == null ? "Could not get your location" : null);
            });
        } else {
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location loc) {
                    if (done[0]) return;
                    done[0] = true;
                    main.removeCallbacks(timeout);
                    cb.onLocation(loc, null);
                }

                @Override
                public void onStatusChanged(String p, int status, Bundle extras) { }

                @Override
                public void onProviderEnabled(@NonNull String p) { }

                @Override
                public void onProviderDisabled(@NonNull String p) { }
            };
            //noinspection deprecation
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
        }
    }

    @SuppressLint("MissingPermission")
    @Nullable
    private static Location bestLastKnown(LocationManager lm) {
        Location best = null;
        for (String p : lm.getProviders(true)) {
            Location l;
            try {
                l = lm.getLastKnownLocation(p);
            } catch (SecurityException e) {
                l = null;
            }
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        return best;
    }

    /** Opens any installed map app; falls back to OpenStreetMap in the browser. */
    public static void open(Context ctx, double lat, double lng, String label) {
        String c = ChatUtil.coords(lat, lng);
        Uri geo = Uri.parse("geo:" + c + "?q=" + c + "(" + Uri.encode(label) + ")");
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (ActivityNotFoundException e) {
            String osm = String.format(java.util.Locale.US,
                    "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f", lat, lng, lat, lng);
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(osm)));
            } catch (ActivityNotFoundException ignored) { }
        }
    }
}
