package com.barta.app.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.barta.app.R;
import com.barta.app.util.ImageUtil;

/**
 * Custom View: a round profile photo, or a coloured circle with the first letter of the name,
 * plus an optional green "online" dot drawn on the canvas.
 */
public class AvatarView extends View {
    private static final int[] COLORS = {
            0xFFFF0052, 0xFF7C3AED, 0xFF0891B2, 0xFFEA580C, 0xFF2563EB, 0xFF059669, 0xFFC026D3};

    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    private String initials = "?";
    private Bitmap photo;
    private boolean online;

    public AvatarView(Context context) {
        this(context, null);
    }

    public AvatarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        letter.setColor(Color.WHITE);
        letter.setTextAlign(Paint.Align.CENTER);
        letter.setFakeBoldText(true);
        circle.setColor(COLORS[0]);
        dot.setColor(ContextCompat.getColor(context, R.color.online_green));
        dotRing.setColor(ContextCompat.getColor(context, R.color.surface));
    }

    public void setName(@Nullable String name) {
        String n = name == null ? "" : name.trim();
        initials = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
        circle.setColor(colorFor(n));
        invalidate();
    }

    /** Name + optional Base64 photo. A null/empty photo shows the letter avatar. */
    public void setUser(@Nullable String name, @Nullable String photoBase64) {
        setName(name);
        setPhoto(ImageUtil.cached(photoBase64));
    }

    public void setPhoto(@Nullable Bitmap bmp) {
        photo = bmp;
        if (bmp != null) {
            photoPaint.setShader(new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            photoPaint.setShader(null);
        }
        invalidate();
    }

    public void setOnline(boolean online) {
        if (this.online == online) return;
        this.online = online;
        invalidate();
    }

    /** Stable colour for a name (also used for sender names in group chats). */
    public static int colorFor(@Nullable String name) {
        String n = name == null ? "" : name.trim();
        return COLORS[(n.hashCode() & 0x7fffffff) % COLORS.length];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w, h) / 2f;
        float cx = w / 2f;
        float cy = h / 2f;

        if (photo != null && photoPaint.getShader() != null) {
            // centre-crop the photo into the circle
            float scale = (2 * r) / Math.min(photo.getWidth(), photo.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - photo.getWidth() * scale / 2f, cy - photo.getHeight() * scale / 2f);
            photoPaint.getShader().setLocalMatrix(matrix);
            canvas.drawCircle(cx, cy, r, photoPaint);
        } else {
            canvas.drawCircle(cx, cy, r, circle);
            letter.setTextSize(r * 0.95f);
            float y = cy - (letter.descent() + letter.ascent()) / 2f;
            canvas.drawText(initials, cx, y, letter);
        }

        if (online) {
            float dr = r * 0.26f;
            float dx = cx + r * 0.70f;
            float dy = cy + r * 0.70f;
            canvas.drawCircle(dx, dy, dr + r * 0.07f, dotRing);
            canvas.drawCircle(dx, dy, dr, dot);
        }
    }
}
