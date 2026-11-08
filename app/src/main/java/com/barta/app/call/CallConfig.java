package com.barta.app.call;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * Servers that help two phones find each other.
 *
 * STUN (free, Google): tells each phone its public internet address. Enough on most Wi-Fi.
 *
 * TURN (optional): relays the call when a direct connection is impossible, which often
 * happens on mobile data (GP / Robi / Banglalink share one address between many users).
 * To enable it, create a FREE account at https://www.metered.ca/tools/openrelay/ ,
 * copy the TURN url, username and password into the three constants below and rebuild.
 * Leave them empty to use STUN only.
 */
public final class CallConfig {
    // ---- paste your free Open Relay / Metered TURN details here (optional) ----
    public static final String[] TURN_URLS = {
            // "turn:YOUR-APP.metered.live:80",
            // "turn:YOUR-APP.metered.live:443",
            // "turns:YOUR-APP.metered.live:443?transport=tcp",
    };
    public static final String TURN_USERNAME = "";
    public static final String TURN_PASSWORD = "";

    /** How long an outgoing call rings before it counts as "no answer". */
    public static final long RING_TIMEOUT_MS = 45_000;

    private CallConfig() { }

    public static List<PeerConnection.IceServer> iceServers() {
        List<PeerConnection.IceServer> list = new ArrayList<>();
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        list.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        if (TURN_URLS.length > 0 && !TURN_USERNAME.isEmpty()) {
            for (String url : TURN_URLS) {
                list.add(PeerConnection.IceServer.builder(url)
                        .setUsername(TURN_USERNAME)
                        .setPassword(TURN_PASSWORD)
                        .createIceServer());
            }
        }
        return list;
    }
}
