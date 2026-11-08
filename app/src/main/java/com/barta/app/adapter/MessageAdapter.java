package com.barta.app.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.barta.app.R;
import com.barta.app.databinding.ItemMsgReceivedBinding;
import com.barta.app.databinding.ItemMsgSentBinding;
import com.barta.app.model.Message;
import com.barta.app.util.ChatUtil;
import com.barta.app.util.ImageUtil;
import com.barta.app.view.AvatarView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chat bubbles: two custom item layouts (sent / received) in one RecyclerView.
 * Each bubble can show a reply quote, a photo, a location card, text, "edited", ticks and reactions.
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int SENT = 1;
    private static final int RECEIVED = 2;

    public interface Listener {
        void onLongPress(Message m);

        void onQuoteClick(String replyToId);

        void onLocationClick(Message m);
    }

    private final List<Message> items;
    private final String me;
    private final boolean group;
    private final Listener listener;
    private final LruCache<String, Bitmap> bitmaps = new LruCache<>(20);

    /** Highest "read up to" time among the other members (for the seen double tick). */
    private long othersReadUpTo = 0;
    /** Text to highlight while searching. */
    private String highlight = null;
    /** Message to flash briefly after jumping to it. */
    private String flashId = null;

    public MessageAdapter(List<Message> items, String me, boolean group, Listener listener) {
        this.items = items;
        this.me = me;
        this.group = group;
        this.listener = listener;
    }

    public void setOthersReadUpTo(long millis) {
        if (millis == othersReadUpTo) return;
        othersReadUpTo = millis;
        notifyDataSetChanged();
    }

    public void setHighlight(@Nullable String query) {
        highlight = query == null || query.isEmpty() ? null : query.toLowerCase(Locale.ROOT);
        notifyDataSetChanged();
    }

    public void setFlashId(@Nullable String id) {
        flashId = id;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return me.equals(items.get(position).senderId) ? SENT : RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == SENT) {
            return new SentVH(ItemMsgSentBinding.inflate(inf, parent, false));
        }
        return new ReceivedVH(ItemMsgReceivedBinding.inflate(inf, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message m = items.get(position);
        // WhatsApp-style date chip ("Today", "Yesterday", ...) above the first message of each day
        boolean newDay = position == 0
                || !ChatUtil.sameDay(items.get(position - 1).timestamp, m.timestamp);

        if (holder instanceof SentVH) {
            ItemMsgSentBinding b = ((SentVH) holder).b;
            bindDate(b.tvDate, newDay, m.timestamp);
            Parts p = new Parts(b.row, b.bubble, b.layoutQuote, b.tvQuoteSender, b.tvQuoteText,
                    b.ivImage, b.layoutLocation, b.tvLocation, b.tvText, b.tvEdited, b.tvTime, b.tvReactions);
            bind(m, p, true);
            // Clock = waiting for server, one tick = saved, brand double tick = seen
            if (m.deleted) {
                b.ivStatus.setVisibility(View.GONE);
            } else {
                b.ivStatus.setVisibility(View.VISIBLE);
                if (m.pending) {
                    b.ivStatus.setImageResource(R.drawable.ic_pending);
                    b.ivStatus.setContentDescription("Sending");
                } else if (othersReadUpTo >= m.timestamp) {
                    b.ivStatus.setImageResource(R.drawable.ic_double_tick);
                    b.ivStatus.setContentDescription("Seen");
                } else {
                    b.ivStatus.setImageResource(R.drawable.ic_tick);
                    b.ivStatus.setContentDescription("Sent");
                }
            }
        } else {
            ItemMsgReceivedBinding b = ((ReceivedVH) holder).b;
            bindDate(b.tvDate, newDay, m.timestamp);
            Parts p = new Parts(b.row, b.bubble, b.layoutQuote, b.tvQuoteSender, b.tvQuoteText,
                    b.ivImage, b.layoutLocation, b.tvLocation, b.tvText, b.tvEdited, b.tvTime, b.tvReactions);
            bind(m, p, false);
            boolean showName = group && m.senderName != null;
            b.tvSender.setVisibility(showName ? View.VISIBLE : View.GONE);
            if (showName) {
                b.tvSender.setText(m.senderName);
                b.tvSender.setTextColor(AvatarView.colorFor(m.senderName));
            }
        }
    }

    /** The views both bubble layouts share. */
    private static class Parts {
        final View row, bubble, quote, location;
        final TextView quoteSender, quoteText, locationText, text, edited, time, reactions;
        final ImageView image;

        Parts(View row, View bubble, LinearLayout quote, TextView quoteSender, TextView quoteText,
              ImageView image, LinearLayout location, TextView locationText, TextView text,
              TextView edited, TextView time, TextView reactions) {
            this.row = row;
            this.bubble = bubble;
            this.quote = quote;
            this.quoteSender = quoteSender;
            this.quoteText = quoteText;
            this.image = image;
            this.location = location;
            this.locationText = locationText;
            this.text = text;
            this.edited = edited;
            this.time = time;
            this.reactions = reactions;
        }
    }

    private void bindDate(TextView tvDate, boolean show, long timestamp) {
        tvDate.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) tvDate.setText(ChatUtil.dayLabel(timestamp));
    }

    private void bind(Message m, Parts p, boolean mine) {
        Context ctx = p.row.getContext();
        p.time.setText(ChatUtil.time(m.timestamp));
        p.row.setBackgroundColor(m.id != null && m.id.equals(flashId)
                ? ContextCompat.getColor(ctx, R.color.flash_highlight) : 0);
        p.bubble.setOnLongClickListener(v -> {
            listener.onLongPress(m);
            return true;
        });

        // "This message was deleted" placeholder
        if (m.deleted) {
            p.quote.setVisibility(View.GONE);
            p.image.setVisibility(View.GONE);
            p.location.setVisibility(View.GONE);
            p.edited.setVisibility(View.GONE);
            p.reactions.setVisibility(View.GONE);
            p.text.setVisibility(View.VISIBLE);
            p.text.setText(mine ? "🚫 You deleted this message" : "🚫 This message was deleted");
            p.text.setTypeface(null, Typeface.ITALIC);
            p.text.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted));
            return;
        }
        p.text.setTypeface(null, Typeface.NORMAL);
        p.text.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));

        // Call log line ("📞 Voice call · 2:31" / "Missed voice call"), missed calls in red
        if (m.isCall()) {
            p.quote.setVisibility(View.GONE);
            p.image.setVisibility(View.GONE);
            p.location.setVisibility(View.GONE);
            p.edited.setVisibility(View.GONE);
            p.text.setVisibility(View.VISIBLE);
            p.text.setText(m.callLabel(mine));
            if (!mine && !"completed".equals(m.callStatus)) {
                p.text.setTextColor(ContextCompat.getColor(ctx, R.color.error_red));
            }
            String rc = reactionSummary(m.reactions);
            p.reactions.setVisibility(rc == null ? View.GONE : View.VISIBLE);
            if (rc != null) p.reactions.setText(rc);
            return;
        }

        // Reply quote
        boolean hasQuote = m.replyToId != null && !m.replyToId.isEmpty();
        p.quote.setVisibility(hasQuote ? View.VISIBLE : View.GONE);
        if (hasQuote) {
            p.quoteSender.setText(m.replyToSender == null ? "" : m.replyToSender);
            p.quoteText.setText(m.replyToText == null ? "" : m.replyToText);
            p.quote.setOnClickListener(v -> listener.onQuoteClick(m.replyToId));
        }

        // Text (with search highlight)
        p.text.setVisibility(m.hasText() ? View.VISIBLE : View.GONE);
        p.text.setText(m.hasText() ? highlighted(ctx, m.text) : "");
        p.edited.setVisibility(m.edited ? View.VISIBLE : View.GONE);

        // Location card
        p.location.setVisibility(m.isLocation() ? View.VISIBLE : View.GONE);
        if (m.isLocation()) {
            p.locationText.setText(ChatUtil.coords(m.lat, m.lng));
            p.location.setOnClickListener(v -> listener.onLocationClick(m));
            p.location.setOnLongClickListener(v -> {
                listener.onLongPress(m);
                return true;
            });
        }

        // Photo
        if (!m.isImage()) {
            p.image.setVisibility(View.GONE);
        } else {
            String key = m.id == null ? String.valueOf(m.timestamp) : m.id;
            Bitmap bmp = bitmaps.get(key);
            if (bmp == null) {
                bmp = ImageUtil.base64ToBitmap(m.imageBase64);
                if (bmp != null) bitmaps.put(key, bmp);
            }
            p.image.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            if (bmp != null) p.image.setImageBitmap(bmp);
            p.image.setOnLongClickListener(v -> {
                listener.onLongPress(m);
                return true;
            });
        }

        // Reactions: "👍❤️ 3"
        String r = reactionSummary(m.reactions);
        p.reactions.setVisibility(r == null ? View.GONE : View.VISIBLE);
        if (r != null) {
            p.reactions.setText(r);
            p.reactions.setOnClickListener(v -> listener.onLongPress(m));
        }
    }

    private CharSequence highlighted(Context ctx, String text) {
        if (highlight == null) return text;
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(highlight);
        if (idx < 0) return text;
        SpannableString s = new SpannableString(text);
        int color = ContextCompat.getColor(ctx, R.color.search_highlight);
        while (idx >= 0) {
            s.setSpan(new BackgroundColorSpan(color), idx, idx + highlight.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = lower.indexOf(highlight, idx + highlight.length());
        }
        return s;
    }

    @Nullable
    static String reactionSummary(@Nullable Map<String, String> reactions) {
        if (reactions == null || reactions.isEmpty()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String emoji : reactions.values()) {
            if (emoji == null) continue;
            Integer c = counts.get(emoji);
            counts.put(emoji, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String emoji : counts.keySet()) sb.append(emoji);
        if (reactions.size() > 1) sb.append(' ').append(reactions.size());
        return sb.toString();
    }

    public int positionOf(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) return i;
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SentVH extends RecyclerView.ViewHolder {
        final ItemMsgSentBinding b;

        SentVH(ItemMsgSentBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    static class ReceivedVH extends RecyclerView.ViewHolder {
        final ItemMsgReceivedBinding b;

        ReceivedVH(ItemMsgReceivedBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
