/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Volume of YouTube's video playback, independent of the device volume.
 * Applied through {@link PlayerVolumePatch} as its own channel, next to mute and ducking.
 * <p>
 * The slider value is mapped on a squared curve, so the lower half of the slider
 * gives fine control over quiet levels, which a linear slider would rush through.
 * Long-pressing the Mute button in the player opens a volume slider.
 */
@SuppressWarnings("unused")
public final class AppVolumePatch {

    /** Slider level while the popup is being dragged, or -1. Saved to the setting on release. */
    private static volatile int liveLevel = -1;

    private static int getLevel() {
        final int live = liveLevel;
        return live >= 0 ? live : SeekBarPreference.clampToRange(Settings.APP_VOLUME);
    }

    /**
     * Gain to multiply the playback volume by, 0 to 1.
     * Can be called from any thread, including the ExoPlayer audio thread.
     */
    public static float getGain() {
        try {
            final float level = getLevel() / 100f;
            return level * level;
        } catch (Exception ex) {
            // Settings may not be ready yet when audio starts very early.
            return 1f;
        }
    }

    /**
     * Long click listener for player buttons that opens the volume popup.
     */
    public static boolean onLongClick(View view) {
        if (!Settings.APP_VOLUME_LONG_PRESS.get()) return false;
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        VolumePopup.show(view);
        return true;
    }

    /**
     * Small popup next to the anchor with a percentage label and a slider.
     * Changes apply while dragging and are saved when the finger lifts.
     */
    private static final class VolumePopup {
        /** The popup closes itself after this long without interaction. */
        private static final long AUTO_DISMISS_MS = 3500;

        private static final Handler handler = new Handler(Looper.getMainLooper());
        private static WeakReference<PopupWindow> current = new WeakReference<>(null);
        private static Runnable dismissTask;

        static void show(View anchor) {
            try {
                PopupWindow previous = current.get();
                if (previous != null && previous.isShowing()) {
                    previous.dismiss();
                }

                Context context = anchor.getContext();
                final int accent = resolveAccent(context);

                TextView label = new TextView(context);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                label.setGravity(Gravity.CENTER);
                updateLabel(label, getLevel());

                SeekBar seekBar = new SeekBar(context);
                seekBar.setMax(100);
                seekBar.setProgress(getLevel());
                seekBar.setProgressTintList(ColorStateList.valueOf(accent));
                seekBar.setThumbTintList(ColorStateList.valueOf(accent));

                LinearLayout content = new LinearLayout(context);
                content.setOrientation(LinearLayout.VERTICAL);
                final int padding = dp(context, 14);
                content.setPadding(padding, dp(context, 10), padding, dp(context, 10));
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xF0202020);
                background.setCornerRadius(dp(context, 16));
                content.setBackground(background);
                content.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                seekParams.topMargin = dp(context, 6);
                content.addView(seekBar, seekParams);

                final int screenWidth = anchor.getRootView().getWidth() > 0
                        ? anchor.getRootView().getWidth()
                        : context.getResources().getDisplayMetrics().widthPixels;
                final int width = Math.min(dp(context, 280), screenWidth - dp(context, 32));

                PopupWindow popup = new PopupWindow(content, width,
                        ViewGroup.LayoutParams.WRAP_CONTENT, true);
                popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                popup.setOutsideTouchable(true);
                popup.setElevation(dp(context, 8));

                seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (!fromUser) return;
                        liveLevel = progress;
                        updateLabel(label, progress);
                        PlayerVolumePatch.onAppVolumeChanged();
                        scheduleDismiss(popup);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        if (dismissTask != null) handler.removeCallbacks(dismissTask);
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        save();
                        scheduleDismiss(popup);
                    }
                });
                popup.setOnDismissListener(VolumePopup::save);

                // Centered horizontally on the anchor, above it, or below if there is no room.
                content.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                final int popupHeight = content.getMeasuredHeight();
                int[] location = new int[2];
                anchor.getLocationInWindow(location);
                int x = location[0] + anchor.getWidth() / 2 - width / 2;
                x = Math.max(dp(context, 16), Math.min(x, screenWidth - width - dp(context, 16)));
                int y = location[1] - popupHeight - dp(context, 8);
                if (y < dp(context, 24)) {
                    y = location[1] + anchor.getHeight() + dp(context, 8);
                }

                popup.showAtLocation(anchor.getRootView(), Gravity.NO_GRAVITY, x, y);
                current = new WeakReference<>(popup);
                scheduleDismiss(popup);
            } catch (Exception ex) {
                Logger.printException(() -> "VolumePopup show failure", ex);
            }
        }

        private static void save() {
            final int live = liveLevel;
            if (live >= 0) {
                Settings.APP_VOLUME.save(live);
                liveLevel = -1;
            }
        }

        private static void scheduleDismiss(PopupWindow popup) {
            if (dismissTask != null) handler.removeCallbacks(dismissTask);
            dismissTask = () -> {
                if (popup.isShowing()) popup.dismiss();
            };
            handler.postDelayed(dismissTask, AUTO_DISMISS_MS);
        }

        private static void updateLabel(TextView label, int level) {
            label.setText("Volume " + level + "%");
        }

        private static int resolveAccent(Context context) {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)
                    && value.data != 0) {
                return value.data;
            }
            return Color.WHITE;
        }

        private static int dp(Context context, int dp) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    dp, context.getResources().getDisplayMetrics()));
        }
    }
}
