/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
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
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;

/**
 * Scales the volume of YouTube Music's own players, independent of the device volume.
 * The slider value is mapped on a squared curve, so the lower half of the slider
 * gives fine control over quiet levels, which a linear slider would rush through.
 * <p>
 * Long-pressing the play/pause button of the player or miniplayer opens a volume slider.
 */
@SuppressWarnings("unused")
public class AppVolumePatch {

    /**
     * Implemented by the ExoPlayer implementation class during patching.
     */
    public interface VolumeAccess {
        /** Calls the player's own setVolume, which passes through {@link #overrideVolume}. */
        void patch_appVolume_setVolume(float volume);
    }

    /** Index of the volume the app last requested. */
    private static final int REQUESTED = 0;
    /** Index of the gain last applied, NaN if none yet. */
    private static final int APPLIED_GAIN = 1;

    /**
     * Requested volume and applied gain for each live player.
     * More than one player can be alive at the same time, such as when crossfading.
     */
    private static final Map<VolumeAccess, float[]> players =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Slider level while the popup is being dragged, or -1. Saved to the setting on release. */
    private static volatile int liveLevel = -1;

    private static int getLevel() {
        final int live = liveLevel;
        return live >= 0 ? live : SeekBarPreference.clampToRange(Settings.APP_VOLUME);
    }

    private static float getGain() {
        final float level = getLevel() / 100f;
        return level * level;
    }

    /**
     * Injection point.
     * End of each ExoPlayerImpl constructor. A new player starts at full volume.
     */
    public static void registerPlayer(Object player) {
        try {
            if (player instanceof VolumeAccess access) {
                players.put(access, new float[]{1f, Float.NaN});
            }
        } catch (Exception ex) {
            Logger.printException(() -> "registerPlayer failure", ex);
        }
    }

    /**
     * Injection point.
     * Start of ExoPlayerImpl.setVolume(float).
     *
     * @param volume Volume the app is setting, 0 to 1.
     * @return The volume to actually use.
     */
    public static float overrideVolume(Object player, float volume) {
        try {
            final float gain = getGain();
            if (player instanceof VolumeAccess access) {
                players.put(access, new float[]{volume, gain});
            }
            return volume * gain;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideVolume failure", ex);
            return volume;
        }
    }

    /**
     * Injection point.
     * Called on the main thread about every second while a track is playing.
     * Applies a changed slider value, and the slider value to new players.
     */
    public static void setVideoTime(long time) {
        applyGain();
        LongPress.ensureAttached();
    }

    /** Must be called on the main thread, which is the players' application thread. */
    private static void applyGain() {
        try {
            final float gain = getGain();

            List<Map.Entry<VolumeAccess, float[]>> entries;
            synchronized (players) {
                entries = new ArrayList<>(players.entrySet());
            }

            for (Map.Entry<VolumeAccess, float[]> entry : entries) {
                VolumeAccess player = entry.getKey();
                float[] state = entry.getValue();
                if (player == null || state[APPLIED_GAIN] == gain) {
                    continue;
                }

                // Set before applying, so a failure is not retried every second.
                state[APPLIED_GAIN] = gain;

                final float requested = state[REQUESTED];
                Logger.printDebug(() -> "Applying app volume gain: " + gain + " to volume: " + requested);
                player.patch_appVolume_setVolume(requested);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "applyGain failure", ex);
        }
    }

    /**
     * Attaches a long-press listener to the play/pause buttons, which opens the volume popup.
     */
    private static final class LongPress {
        private static final String[] BUTTON_IDS = {
                "player_control_play_pause_replay_button",
                "mini_player_play_pause_replay_button",
        };

        /** Minimum time between view tree scans from the layout listener. */
        private static final long SCAN_INTERVAL_MS = 500;

        private static final Map<View, Boolean> attachedButtons =
                Collections.synchronizedMap(new WeakHashMap<>());

        private static WeakReference<View> layoutListenerHost = new WeakReference<>(null);
        private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        private static long lastScanTime;

        /** Main thread only. */
        static void ensureAttached() {
            try {
                if (!Settings.APP_VOLUME_LONG_PRESS.get()) return;

                Activity activity = Utils.getActivity();
                if (activity == null || activity.getWindow() == null) return;
                View decorView = activity.getWindow().getDecorView();

                // The app rebinds and recreates the player views, so re-scan on layout changes.
                if (layoutListenerHost.get() != decorView) {
                    View previous = layoutListenerHost.get();
                    if (previous != null && layoutListener != null
                            && previous.getViewTreeObserver().isAlive()) {
                        previous.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                    }
                    layoutListener = () -> {
                        final long now = System.currentTimeMillis();
                        if (now - lastScanTime >= SCAN_INTERVAL_MS) {
                            scan(decorView);
                        }
                    };
                    decorView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
                    layoutListenerHost = new WeakReference<>(decorView);
                }

                scan(decorView);
            } catch (Exception ex) {
                Logger.printException(() -> "ensureAttached failure", ex);
            }
        }

        private static void scan(View decorView) {
            lastScanTime = System.currentTimeMillis();
            if (!Settings.APP_VOLUME_LONG_PRESS.get()) return;

            Resources res = decorView.getResources();
            String pkg = decorView.getContext().getPackageName();
            for (String idName : BUTTON_IDS) {
                @SuppressLint("DiscouragedApi")
                final int id = res.getIdentifier(idName, "id", pkg);
                if (id == 0) continue;

                List<View> found = new ArrayList<>();
                findAllViewsById(decorView, id, found);
                for (View button : found) {
                    if (attachedButtons.containsKey(button)) continue;
                    attachedButtons.put(button, Boolean.TRUE);

                    button.setOnLongClickListener(v -> {
                        if (!Settings.APP_VOLUME_LONG_PRESS.get()) return false;
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        VolumePopup.show(v);
                        return true;
                    });
                    button.setLongClickable(true);
                    Logger.printDebug(() -> "App volume long-press attached to: " + idName);
                }
            }
        }

        private static void findAllViewsById(View root, int id, List<View> out) {
            if (root.getId() == id) out.add(root);
            if (root instanceof ViewGroup group) {
                for (int i = 0, count = group.getChildCount(); i < count; i++) {
                    findAllViewsById(group.getChildAt(i), id, out);
                }
            }
        }
    }

    /**
     * Small popup above the anchor with a percentage label and a slider.
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

                final int width = Math.min(dp(context, 280),
                        context.getResources().getDisplayMetrics().widthPixels - dp(context, 32));

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
                        applyGain();
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

                // Centered horizontally on the anchor, just above it.
                content.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                final int popupHeight = content.getMeasuredHeight();
                int[] location = new int[2];
                anchor.getLocationInWindow(location);
                final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
                int x = location[0] + anchor.getWidth() / 2 - width / 2;
                x = Math.max(dp(context, 16), Math.min(x, screenWidth - width - dp(context, 16)));
                int y = location[1] - popupHeight - dp(context, 8);
                if (y < dp(context, 24)) {
                    // Not enough room above, such as for a button at the top. Show below.
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
