package com.barta.app.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Firebase Storage needs a paid plan, so Barta shrinks pictures and stores them
 * as Base64 text inside the free Firestore document (limit: 1 MB per document).
 */
public final class ImageUtil {
    public static final int CHAT_PHOTO_SIDE = 800;
    public static final int CHAT_PHOTO_QUALITY = 60;
    public static final int PROFILE_PHOTO_SIDE = 256;
    public static final int PROFILE_PHOTO_QUALITY = 70;
    private static final int MAX_BASE64_LENGTH = 900_000;

    /** Decoded avatars, so scrolling a list does not decode the same photo again and again. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(60);

    private ImageUtil() { }

    public static String uriToBase64(ContentResolver cr, Uri uri) throws IOException {
        return uriToBase64(cr, uri, CHAT_PHOTO_SIDE, CHAT_PHOTO_QUALITY);
    }

    public static String uriToBase64(ContentResolver cr, Uri uri, int maxSide, int quality)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("Cannot read image");

        float scale = Math.min(1f, (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale),
                    Math.round(bmp.getHeight() * scale), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        if (b64.length() > MAX_BASE64_LENGTH) throw new IOException("Image is too large");
        return b64;
    }

    public static Bitmap base64ToBitmap(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Same as base64ToBitmap but remembers the result (good for avatars in lists). */
    public static Bitmap cached(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        String key = b64.length() + ":" + b64.hashCode();
        Bitmap bmp = CACHE.get(key);
        if (bmp == null) {
            bmp = base64ToBitmap(b64);
            if (bmp != null) CACHE.put(key, bmp);
        }
        return bmp;
    }
}
