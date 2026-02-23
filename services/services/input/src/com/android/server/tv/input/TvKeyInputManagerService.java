/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.tv.input;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.hardware.input.InputManager.KeyEventActivityListener;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class TvKeyInputManagerService extends SystemService {
    private static final String TAG = "TvKeyInputManagerService";
    private static final boolean DEBUG = false;

    /**
     * The duration (in milliseconds) the "Time-Window Latch" remains open after a generic
     * {@link onKeyEventActivity} is received from {@link InputManager}
     */
    private static final long VALIDATION_WINDOW_MS = 1000;

    /**
     * The duration (in milliseconds) to debounce MUTE/UNMUTE events
     */
    private static final long MUTE_DEBOUNCE_MS = 100;

    private final TvKeyEventActivityCallbackHandler mCallbacks =
            new TvKeyEventActivityCallbackHandler();
    private final Map<IBinder, Integer> mCallbackUids = new HashMap<>();
    private final LocalKeyEventActivityListener mLocalListener =
            new LocalKeyEventActivityListener();

    /**
     * Receiver for volume change events
     */
    private final VolumeBroadcastReceiver mVolumeReceiver = new VolumeBroadcastReceiver();
    private final Executor mExecutor;
    private InputManager mInputManager;
    private ActivityManager mActivityManager;
    private boolean mIsListeningToInputManager = false;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private long mLastValidInputTime = 0;

    @GuardedBy("mLock")
    private int mCumulativeVolumeCount = 0;

    @GuardedBy("mLock")
    private int mLastVolumeDirection = VolumeEventType.UNDEFINED;

    @GuardedBy("mLock")
    private int mStashedVolumeDirection = VolumeEventType.UNDEFINED;

    @GuardedBy("mLock")
    private int mStashedVolumeCount = 0;

    @GuardedBy("mLock")
    private int mLastVolumeLevel = -1;

    private final Handler mHandler;
    private final Runnable mBroadcastRunnable = this::broadcastVolumeEvent;

    @VisibleForTesting
    final BinderService mBinderService = new BinderService();

    public TvKeyInputManagerService(Context context) {
        this(context, Looper.getMainLooper(), Executors.newSingleThreadExecutor());
    }

    @VisibleForTesting
    TvKeyInputManagerService(Context context, Looper looper, Executor executor) {
        super(context);
        mHandler = new Handler(looper);
        mExecutor = executor;
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Starting TV Input Manager Service");
        mInputManager = getContext().getSystemService(InputManager.class);
        mActivityManager = getContext().getSystemService(ActivityManager.class);
        publishBinderService(ITvKeyInputManagerService.NAME, mBinderService);
    }

    @VisibleForTesting
    protected long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    @VisibleForTesting
    protected boolean registerInputManagerListener(KeyEventActivityListener listener) {
        if (mInputManager != null) {
            return mInputManager.registerKeyEventActivityListener(listener);
        }
        return false;
    }

    @VisibleForTesting
    protected void unregisterInputManagerListener(KeyEventActivityListener listener) {
        if (mInputManager != null) {
            mInputManager.unregisterKeyEventActivityListener(listener);
        }
    }

    @VisibleForTesting
    protected void setActivityManager(ActivityManager activityManager) {
        mActivityManager = activityManager;
    }

    private class TvKeyEventActivityCallbackHandler extends
            RemoteCallbackList<ITvKeyEventActivityCallback> {
        @Override
        public void onCallbackDied(ITvKeyEventActivityCallback callback, Object cookie) {
            Log.i(TAG, "Client binder died.");
            synchronized (mCallbacks) {
                mCallbackUids.remove(callback.asBinder());
                if (mCallbacks.getRegisteredCallbackCount() == 0) {
                    stopListeningToInputManager();
                }
            }
        }
    }

    private class LocalKeyEventActivityListener implements KeyEventActivityListener {
        @Override
        public void onKeyEventActivity() {

            synchronized (mLock) {
                long now = getCurrentTimeMillis();
                if (now - mLastValidInputTime >= VALIDATION_WINDOW_MS) {
                    mCumulativeVolumeCount = 0;
                    mLastVolumeDirection = VolumeEventType.UNDEFINED;
                    mStashedVolumeDirection = VolumeEventType.UNDEFINED;
                    mStashedVolumeCount = 0;
                }
                mLastValidInputTime = now;
            }

            if (DEBUG) Log.v(TAG, "onKeyEventActivity received from InputManager");

            final int count = mCallbacks.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    ITvKeyEventActivityCallback callback = mCallbacks.getBroadcastItem(i);
                    Integer clientUid;
                    synchronized (mCallbacks) {
                        clientUid = mCallbackUids.get(callback.asBinder());
                    }

                    if (clientUid == null || !isUidInForeground(clientUid)) {
                        if (DEBUG) {
                            Log.v(TAG, "Skipping broadcast to UID " + clientUid
                                    + " (not found or not in foreground)");
                        }
                        continue;
                    }

                    if (DEBUG) {
                        Log.v(TAG, "Broadcasting onKeyEventActivity to foreground client UID: "
                                + clientUid);
                    }
                    callback.onKeyEventActivity();
                } catch (RemoteException e) {
                    // The RemoteCallbackList handles removing dead listeners automatically
                    Log.w(TAG, "Failed to broadcast onKeyEventActivity to listener", e);
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    private class VolumeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final long now = getCurrentTimeMillis();
            int type = VolumeEventType.UNDEFINED;

            if (DEBUG) Log.v(TAG, "VolumeBroadcastReceiver received action: " + action);

            int flushStashedType = VolumeEventType.UNDEFINED;
            int flushStashedCount = 0;
            int flushRegType = VolumeEventType.UNDEFINED;
            int flushRegCount = 0;
            boolean requiresFlush = false;

            synchronized (mLock) {
                if (now - mLastValidInputTime >= VALIDATION_WINDOW_MS) {
                    if (DEBUG) Log.v(TAG, "Validation window expired. Ignoring volume event.");
                    mCumulativeVolumeCount = 0;
                    mLastVolumeDirection = VolumeEventType.UNDEFINED;
                    mStashedVolumeDirection = VolumeEventType.UNDEFINED;
                    mStashedVolumeCount = 0;
                    return;
                }
                // Valid volume event, extend the window to capture continuous interaction.
                mLastValidInputTime = now;

                int change = 0;

                // Infer volume change and direction from the intent
                if (AudioManager.ACTION_VOLUME_CHANGED.equals(action)) {
                    int newVol = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int oldVol = intent.getIntExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE,
                            -1);
                    if (DEBUG) {
                        Log.v(TAG, "Volume change: old=" + oldVol + ", new=" + newVol);
                    }
                    if (newVol != -1 && oldVol != -1) {
                        mLastVolumeLevel = newVol;
                        if (newVol > oldVol) {
                            type = VolumeEventType.UP;
                            change = newVol - oldVol;
                        } else if (newVol < oldVol) {
                            type = VolumeEventType.DOWN;
                            change = oldVol - newVol;
                        }
                    }
                } else if (AudioManager.STREAM_MUTE_CHANGED_ACTION.equals(action)) {
                    boolean isMuted = intent.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED,
                            false);
                    if (DEBUG) {
                        Log.v(TAG, "Mute change: muted=" + isMuted);
                    }
                    type = isMuted ? VolumeEventType.MUTE : VolumeEventType.UNMUTE;

                    // onVolumeChangeEvent(in VolumeEventType type, int count) for MUTE/UNMUTE
                    // always count 1
                    change = 1;
                }

                if (type == VolumeEventType.UNDEFINED) {
                    if (DEBUG) Log.v(TAG, "Volume event type is UNDEFINED. Ignoring.");
                }

                if (type == VolumeEventType.MUTE || type == VolumeEventType.UNMUTE) {
                    // Ignore UNMUTE if volume is 0
                    if (type == VolumeEventType.UNMUTE && mLastVolumeLevel == 0) {
                        if (DEBUG) Log.v(TAG, "Ignoring UNMUTE event because volume is 0");
                        return;
                    }

                    // Resolve Volume down key press to zero edge case
                    // See detailed docs for this edge case at:
                    // http://docs/document/d/1mST2VS0dpsMAaISt3ANgwXgIaLNoCRcoUf7ELVh1tcs
                    if ((mLastVolumeDirection == VolumeEventType.UP
                            || mLastVolumeDirection == VolumeEventType.DOWN)
                            && mHandler.hasCallbacks(mBroadcastRunnable)) {
                        if (DEBUG) Log.v(TAG, "Stashing pending volume change before MUTE/UNMUTE");
                        mStashedVolumeDirection = mLastVolumeDirection;
                        mStashedVolumeCount = mCumulativeVolumeCount;
                    }

                    // Filter by stream type. We only care about MUSIC stream (3).
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    if (streamType != AudioManager.STREAM_MUSIC) {
                        if (DEBUG) {
                            Log.v(TAG,
                                    "Ignoring volume event for stream type: " + streamType);
                        }
                        return;
                    }

                    // Schedule MUTE/UNMUTE broadcast with a short debounce
                    if (DEBUG) Log.v(TAG, "Scheduling MUTE/UNMUTE broadcast: Type=" + type);
                    mCumulativeVolumeCount = 1;
                    mLastVolumeDirection = type;
                    mHandler.removeCallbacks(mBroadcastRunnable);
                    mHandler.postDelayed(mBroadcastRunnable, MUTE_DEBOUNCE_MS);

                } else {
                    // If we receive a volume change event, and there is a pending MUTE/UNMUTE
                    // event, it means the MUTE/UNMUTE was likely a side effect of the volume
                    // change (e.g. unmuting when volume up from 0).
                    // In this case, we should cancel the pending MUTE/UNMUTE broadcast and
                    // proceed with the volume change.
                    if ((mLastVolumeDirection == VolumeEventType.MUTE
                            || mLastVolumeDirection == VolumeEventType.UNMUTE)
                            && mHandler.hasCallbacks(mBroadcastRunnable)) {
                        if (DEBUG) {
                            Log.v(TAG,
                                    "Cancelling pending MUTE/UNMUTE event due to incoming volume "
                                            + "change");
                        }
                        mHandler.removeCallbacks(mBroadcastRunnable);

                        if (mStashedVolumeDirection != VolumeEventType.UNDEFINED) {
                            if (mStashedVolumeDirection == type) {
                                // Restore stash
                                mLastVolumeDirection = mStashedVolumeDirection;
                                mCumulativeVolumeCount = mStashedVolumeCount;
                                if (DEBUG) {
                                    Log.v(TAG, "Restored stashed volume event: Type="
                                            + mLastVolumeDirection + ", Count="
                                            + mCumulativeVolumeCount);
                                }
                            } else {
                                // Direction changed from stash. Broadcast stash first.
                                mLastVolumeDirection = mStashedVolumeDirection;
                                mCumulativeVolumeCount = mStashedVolumeCount;
                                mStashedVolumeDirection = VolumeEventType.UNDEFINED;
                                mStashedVolumeCount = 0;

                                // plan to broadcastVolumeEvent();
                                flushRegType = mLastVolumeDirection;
                                flushRegCount = mCumulativeVolumeCount;
                                mLastVolumeDirection = VolumeEventType.UNDEFINED;
                                requiresFlush = true;

                                // Reset state for new direction
                                mCumulativeVolumeCount = 0;
                                mLastVolumeDirection = VolumeEventType.UNDEFINED;
                            }
                            // Clear stash if it was restored
                            mStashedVolumeDirection = VolumeEventType.UNDEFINED;
                            mStashedVolumeCount = 0;
                        } else {
                            mCumulativeVolumeCount = 0;
                            mLastVolumeDirection = VolumeEventType.UNDEFINED;
                        }
                    }

                    if (type == mLastVolumeDirection) {
                        mCumulativeVolumeCount += change;
                        if (DEBUG) {
                            Log.v(TAG, "Accumulating volume change: Type=" + type + ", Count="
                                    + mCumulativeVolumeCount);
                        }
                        // Schedule broadcast for coalescing
                        mHandler.removeCallbacks(mBroadcastRunnable);
                        mHandler.postDelayed(mBroadcastRunnable, VALIDATION_WINDOW_MS);
                    } else {
                        // Direction changed or first event
                        // If there was a pending broadcast for the previous direction,
                        // send it immediately
                        if (mHandler.hasCallbacks(mBroadcastRunnable)) {
                            if (DEBUG) Log.v(TAG, "Direction changed. Flushing pending broadcast.");
                            mHandler.removeCallbacks(mBroadcastRunnable);

                            if (mStashedVolumeDirection != VolumeEventType.UNDEFINED) {
                                flushStashedType = mStashedVolumeDirection;
                                flushStashedCount = mStashedVolumeCount;
                                mStashedVolumeDirection = VolumeEventType.UNDEFINED;
                                mStashedVolumeCount = 0;
                            }

                            // plan to  broadcastVolumeEvent();
                            flushRegType = mLastVolumeDirection;
                            flushRegCount = mCumulativeVolumeCount;
                            mLastVolumeDirection = VolumeEventType.UNDEFINED;
                            requiresFlush = true;
                        }

                        // Start new accumulation for the new direction
                        mCumulativeVolumeCount = change;
                        mLastVolumeDirection = type;
                        if (DEBUG) {
                            Log.v(TAG, "Starting new volume accumulation: Type=" + type + ", Count="
                                    + mCumulativeVolumeCount);
                        }

                        // Schedule broadcast for the new direction
                        mHandler.postDelayed(mBroadcastRunnable, VALIDATION_WINDOW_MS);
                    }
                }
            }

            // mLock release, start broadcasting
            if (requiresFlush) {
                final int finalFlushStashedType = flushStashedType;
                final int finalFlushStashedCount = flushStashedCount;
                final int finalFlushRegType = flushRegType;
                final int finalFlushRegCount = flushRegCount;
                mExecutor.execute(() -> {
                    if (finalFlushStashedType != VolumeEventType.UNDEFINED
                            && finalFlushStashedCount > 0) {
                        performBroadcast(finalFlushStashedType, finalFlushStashedCount);
                    }
                    if (finalFlushRegType != VolumeEventType.UNDEFINED && finalFlushRegCount > 0) {
                        performBroadcast(finalFlushRegType, finalFlushRegCount);
                    }
                });
            }
        }
    }

    private void broadcastVolumeEvent() {
        int type;
        int count;
        int stashedType = VolumeEventType.UNDEFINED;
        int stashedCount = 0;

        synchronized (mLock) {
            // Check for stashed event first
            if (mStashedVolumeDirection != VolumeEventType.UNDEFINED) {
                stashedType = mStashedVolumeDirection;
                stashedCount = mStashedVolumeCount;
                mStashedVolumeDirection = VolumeEventType.UNDEFINED;
                mStashedVolumeCount = 0;
            }

            type = mLastVolumeDirection;
            count = mCumulativeVolumeCount;

            // Reset after broadcasting to avoid double counting if called manually
            mCumulativeVolumeCount = 0;
            mLastVolumeDirection = VolumeEventType.UNDEFINED;
        }

        // Broadcast stashed event if exists
        if (stashedType != VolumeEventType.UNDEFINED && stashedCount > 0) {
            if (DEBUG) {
                Log.v(TAG, "Broadcasting volume event: Type: " + stashedType + ", Count: "
                        + stashedCount);
            }
            performBroadcast(stashedType, stashedCount);
        }

        if (type == VolumeEventType.UNDEFINED || count == 0) {
            if (DEBUG) {
                Log.v(TAG, "broadcastVolumeEvent: Nothing to broadcast (Type=" + type + ", Count="
                        + count + ")");
            }
            return;
        }

        if (DEBUG) {
            Log.v(TAG, "Broadcasting volume event: Type: " + type + ", Count: " + count);
        }
        performBroadcast(type, count);
    }

    private void performBroadcast(int type, int count) {
        final int n = mCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                ITvKeyEventActivityCallback callback = mCallbacks.getBroadcastItem(i);
                Integer clientUid;
                synchronized (mCallbacks) {
                    clientUid = mCallbackUids.get(callback.asBinder());
                }
                if (clientUid != null && isUidInForeground(clientUid)) {
                    callback.onVolumeChangeEvent(type, count);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to broadcast onVolumeChangeEvent", e);
            }
        }
        mCallbacks.finishBroadcast();
    }

    private boolean isUidInForeground(int uid) {
        final int importance = mActivityManager.getUidImportance(uid);
        final boolean isForeground =
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        if (DEBUG) {
            Log.v(TAG, "UID " + uid + " has importance: " + importance + " (isForeground="
                    + isForeground + ")");
        }
        return isForeground;
    }

    private void startListeningToInputManager() {
        mExecutor.execute(() -> {
            synchronized (mCallbacks) {
                if (!mIsListeningToInputManager && mCallbacks.getRegisteredCallbackCount() > 0) {
                    if (registerInputManagerListener(mLocalListener)) {
                        mIsListeningToInputManager = true;
                        if (DEBUG) Log.v(TAG, "Successfully registered with InputManager.");

                        IntentFilter filter = new IntentFilter();
                        filter.addAction(AudioManager.ACTION_VOLUME_CHANGED);
                        filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
                        getContext().registerReceiver(mVolumeReceiver, filter);
                    } else {
                        Log.e(TAG, "Failed to register listener with InputManager.");
                    }
                }
            }
        });
    }

    private void stopListeningToInputManager() {
        mExecutor.execute(() -> {
            synchronized (mCallbacks) { // Synchronize access
                if (mIsListeningToInputManager && mCallbacks.getRegisteredCallbackCount() == 0) {
                    if (DEBUG) Log.v(TAG, "Unregistering listener from InputManager");
                    unregisterInputManagerListener(mLocalListener);
                    try {
                        getContext().unregisterReceiver(mVolumeReceiver);
                    } catch (IllegalArgumentException e) {
                        // Ignore
                    }
                    mIsListeningToInputManager = false;
                }
            }
        });
    }

    @VisibleForTesting
    final class BinderService extends ITvKeyInputManagerService.Stub {
        @Override
        public void registerCallback(ITvKeyEventActivityCallback callback, int clientUid)
                throws RemoteException {
            if (callback == null) {
                Log.w(TAG, "registerCallback: callback is null");
                return;
            }
            if (DEBUG) Log.v(TAG, "Registering callback for client UID: " + clientUid);
            synchronized (mCallbacks) {
                boolean prepareToStartListening = mCallbacks.getRegisteredCallbackCount() == 0;
                mCallbacks.register(callback);
                // Store the client UID associated with this callback's binder
                mCallbackUids.put(callback.asBinder(), clientUid);
                if (DEBUG) {
                    Log.v(TAG, "Callback registered. Count: "
                            + mCallbacks.getRegisteredCallbackCount());
                }
                if (prepareToStartListening && mCallbacks.getRegisteredCallbackCount() > 0) {
                    startListeningToInputManager();
                }
            }
        }

        @Override
        public void unregisterCallback(ITvKeyEventActivityCallback callback)
                throws RemoteException {
            if (callback == null) {
                Log.w(TAG, "UnregisterCallback: callback is null");
                return;
            }
            if (DEBUG) Log.v(TAG, "Unregistering callback " + callback);
            synchronized (mCallbacks) {
                // Remove the associated UID from the map
                mCallbackUids.remove(callback.asBinder());
                boolean wasRegistered = mCallbacks.unregister(callback);
                if (DEBUG) {
                    Log.v(TAG, "Callback unregistered (" + wasRegistered + "). Count: "
                            + mCallbacks.getRegisteredCallbackCount());
                }
                if (wasRegistered && mCallbacks.getRegisteredCallbackCount() == 0) {
                    stopListeningToInputManager();
                }
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) return;
            writer.println("============ Beginning of TV Input Manager Service Dump ============");
            synchronized (mCallbacks) {
                writer.printf(" mIsListeningToInputManager: %s\n",
                        mIsListeningToInputManager ? "yes" : "no");
                writer.printf(" mCallbacks.getRegisteredCallbackCount(): %d\n",
                        mCallbacks.getRegisteredCallbackCount());
            }
            synchronized (mLock) {
                writer.printf(" mLastValidInputTime: %d\n", mLastValidInputTime);
                writer.printf(" mCumulativeVolumeCount: %d\n", mCumulativeVolumeCount);
                writer.printf(" mLastVolumeDirection: %d\n", mLastVolumeDirection);
                writer.printf(" mStashedVolumeDirection: %d\n", mStashedVolumeDirection);
                writer.printf(" mStashedVolumeCount: %d\n", mStashedVolumeCount);
                writer.printf(" mLastVolumeLevel: %d\n", mLastVolumeLevel);
            }
            writer.println("============ End of TV Input Manager Service Dump ============");
        }
    }
}
