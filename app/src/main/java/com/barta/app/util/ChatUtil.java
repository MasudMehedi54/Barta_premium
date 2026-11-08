package com.barta.app.util;

import com.barta.app.model.UserStatus;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class ChatUtil {
    private static final long DAY = 24L * 60 * 60 * 1000;

    private ChatUtil() { }

    /** Same id for both users: the two uids sorted and joined with "_". */
    public static String directChatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    public static String time(long millis) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(millis));
    }

    /** Chat list time, like WhatsApp: time if today, "Yesterday", otherwise a short date. */
    public static String listTime(long millis) {
        if (millis == 0) return "";
        long now = System.currentTimeMillis();
        if (sameDay(millis, now)) return time(millis);
        if (sameDay(millis, now - DAY)) return "Yesterday";
        return new SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(new Date(millis));
    }

    public static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance();
        Calendar y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR)
                && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    /** WhatsApp-style date separator: "Today", "Yesterday" or "12 March 2026". */
    public static String dayLabel(long millis) {
        long now = System.currentTimeMillis();
        if (sameDay(millis, now)) return "Today";
        if (sameDay(millis, now - DAY)) return "Yesterday";
        return new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(new Date(millis));
    }

    /** "online", "last seen today at 10:15 PM", "last seen yesterday at ...", "last seen 3 Nov". */
    public static String presence(UserStatus s) {
        if (s == null) return null;
        if (s.online) return "online";
        if (s.lastSeen <= 0) return null;
        long now = System.currentTimeMillis();
        if (sameDay(s.lastSeen, now)) return "last seen today at " + time(s.lastSeen);
        if (sameDay(s.lastSeen, now - DAY)) return "last seen yesterday at " + time(s.lastSeen);
        return "last seen " + new SimpleDateFormat("d MMM 'at' hh:mm a", Locale.getDefault())
                .format(new Date(s.lastSeen));
    }

    /** Scheduled message time: "Tue 10 Nov, 09:00 AM". */
    public static String scheduleLabel(long millis) {
        return new SimpleDateFormat("EEE d MMM, hh:mm a", Locale.getDefault()).format(new Date(millis));
    }

    /** Coordinates always with a dot (Locale.US), so the geo: link works in every language. */
    public static String coords(double lat, double lng) {
        return String.format(Locale.US, "%.6f,%.6f", lat, lng);
    }
}
