package com.barta.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Broadcast Receiver: listens for connectivity changes (registered dynamically by MainActivity
 * while it is visible) and tells the UI whether to show the "offline" banner.
 */
public class NetworkReceiver extends BroadcastReceiver {

    public interface Listener {
        void onConnectivityChanged(boolean online);
    }

    private final Listener listener;

    public NetworkReceiver(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        listener.onConnectivityChanged(isOnline(context));
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
