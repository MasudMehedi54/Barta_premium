package com.barta.app.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Networking + JSON parsing demo: HTTP GET to a free public API, JSON parsed with org.json.
 * Call from a background thread. Returns null if offline or the API is unavailable.
 */
public final class QuoteFetcher {
    private QuoteFetcher() { }

    public static String fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://zenquotes.io/api/random").openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONArray(sb.toString()).getJSONObject(0);
            return "\u201C" + o.getString("q") + "\u201D \u2014 " + o.getString("a");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
