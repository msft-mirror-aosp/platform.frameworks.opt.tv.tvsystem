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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TvKeyInputManagerServiceTest {

    private static final int TEST_UID = 12345;
    private static final long VALIDATION_WINDOW_MS = 1000;
    private static final long MUTE_DEBOUNCE_MS = 100;

    @Mock
    private Context mContext;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private ITvKeyEventActivityCallback mCallback;
    @Mock
    private IBinder mBinder;

    private TvKeyInputManagerService mService;
    private ITvKeyInputManagerService.Stub mBinderService;
    private TestLooper mTestLooper;
    private BroadcastReceiver mVolumeReceiver;
    private InputManager.KeyEventActivityListener mKeyListener;

    private class TestableTvKeyInputManagerService extends TvKeyInputManagerService {
        InputManager.KeyEventActivityListener mCapturedListener;
        private long mCurrentTimeMillis = 1000000L; // Start at some time

        TestableTvKeyInputManagerService(Context context, Looper looper, Executor executor) {
            super(context, looper, executor);
        }

        @Override
        public void onStart() {
            setActivityManager(mActivityManager);
        }

        @Override
        protected boolean registerInputManagerListener(
                InputManager.KeyEventActivityListener listener) {
            mCapturedListener = listener;
            return true;
        }

        @Override
        protected void unregisterInputManagerListener(
                InputManager.KeyEventActivityListener listener) {
            if (mCapturedListener == listener) {
                mCapturedListener = null;
            }
        }

        @Override
        protected long getCurrentTimeMillis() {
            return mCurrentTimeMillis;
        }

        void advanceTime(long millis) {
            mCurrentTimeMillis += millis;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();

        when(mContext.getSystemServiceName(ActivityManager.class)).thenReturn(
                Context.ACTIVITY_SERVICE);
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mActivityManager);

        when(mCallback.asBinder()).thenReturn(mBinder);

        // Mock foreground check
        when(mActivityManager.getUidImportance(TEST_UID))
                .thenReturn(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);

        mService = new TestableTvKeyInputManagerService(mContext, mTestLooper.getLooper(),
                Runnable::run);
        mService.onStart();

        mBinderService = mService.mBinderService;

        // Register callback to trigger listener registration
        mBinderService.registerCallback(mCallback, TEST_UID);

        // Capture the listeners
        mKeyListener = ((TestableTvKeyInputManagerService) mService).mCapturedListener;

        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiverCaptor.capture(), any());
        mVolumeReceiver = receiverCaptor.getValue();
    }

    @Test
    public void testLatchLogic_Success() throws Exception {
        mKeyListener.onKeyEventActivity();

        Intent intent = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 15);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intent);

        // Advance looper to process the delayed broadcast
        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback).onVolumeChangeEvent(VolumeEventType.UP, 5);
    }

    @Test
    public void testLatchLogic_Expired() throws Exception {
        mKeyListener.onKeyEventActivity();

        ((TestableTvKeyInputManagerService) mService).advanceTime(VALIDATION_WINDOW_MS + 100);

        Intent intent = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 15);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intent);

        mTestLooper.dispatchAll();

        verify(mCallback, never()).onVolumeChangeEvent(anyInt(), anyInt());
    }

    @Test
    public void testExternalSuppression() throws Exception {
        Intent intent = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 15);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intent);

        mTestLooper.dispatchAll();

        verify(mCallback, never()).onVolumeChangeEvent(anyInt(), anyInt());
    }

    @Test
    public void testBurstCoalescing() throws Exception {
        mKeyListener.onKeyEventActivity();

        for (int i = 0; i < 5; i++) {
            Intent intent = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
            intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10 - i);
            intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 9 - i);
            intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
            mVolumeReceiver.onReceive(mContext, intent);
        }

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback).onVolumeChangeEvent(VolumeEventType.DOWN, 5);
    }

    @Test
    public void testMuteImmediate() throws Exception {
        mKeyListener.onKeyEventActivity();

        Intent intent = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intent.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, true);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intent);

        mTestLooper.moveTimeForward(MUTE_DEBOUNCE_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback).onVolumeChangeEvent(VolumeEventType.MUTE, 1);
    }

    @Test
    public void testRapidMuteToggling() throws Exception {
        mKeyListener.onKeyEventActivity();

        Intent intentMute = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intentMute.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, true);
        intentMute.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentMute);

        Intent intentUnmute = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intentUnmute.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);
        intentUnmute.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentUnmute);

        mTestLooper.moveTimeForward(MUTE_DEBOUNCE_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback).onVolumeChangeEvent(VolumeEventType.UNMUTE, 1);
        verify(mCallback, never()).onVolumeChangeEvent(VolumeEventType.MUTE, 1);
    }

    @Test
    public void testVolumeChangeCancelsPendingMute() throws Exception {
        mKeyListener.onKeyEventActivity();

        Intent intentMute = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intentMute.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, true);
        intentMute.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentMute);

        Intent intentVol = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentVol.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 0);
        intentVol.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 1);
        intentVol.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentVol);

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback, never()).onVolumeChangeEvent(VolumeEventType.MUTE, 1);
        verify(mCallback).onVolumeChangeEvent(VolumeEventType.UP, 1);
    }

    @Test
    public void testStashedVolumeRestored() throws Exception {
        mKeyListener.onKeyEventActivity();

        Intent intentVol1 = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentVol1.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intentVol1.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 11);
        intentVol1.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentVol1);

        Intent intentMute = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intentMute.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, true);
        intentMute.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentMute);

        Intent intentVol2 = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentVol2.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 11);
        intentVol2.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 12);
        intentVol2.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentVol2);

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback, never()).onVolumeChangeEvent(VolumeEventType.MUTE, 1);
        verify(mCallback).onVolumeChangeEvent(VolumeEventType.UP, 2);
    }

    @Test
    public void testVolumeDownToZeroIgnoresUnmute() throws Exception {
        mKeyListener.onKeyEventActivity();

        Intent intentVol = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentVol.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 1);
        intentVol.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
        intentVol.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentVol);

        Intent intentUnmute = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intentUnmute.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);
        intentUnmute.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentUnmute);

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback).onVolumeChangeEvent(VolumeEventType.DOWN, 1);
        verify(mCallback, never()).onVolumeChangeEvent(VolumeEventType.UNMUTE, 1);
    }

    @Test
    public void testPrivacyGuardrail_BackgroundApp() throws Exception {
        // Simulate app going to background
        when(mActivityManager.getUidImportance(TEST_UID))
                .thenReturn(ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND);

        mKeyListener.onKeyEventActivity();

        Intent intent = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intent.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 15);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intent);

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback, never()).onVolumeChangeEvent(anyInt(), anyInt());
    }

    @Test
    public void testDirectionChangeFlush() throws Exception {
        mKeyListener.onKeyEventActivity();

        // Volume UP
        Intent intentUp = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentUp.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intentUp.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 11);
        intentUp.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentUp);

        // Volume DOWN immediately
        Intent intentDown = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentDown.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 11);
        intentDown.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 10);
        intentDown.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentDown);

        // Should flush UP immediately, then schedule DOWN
        verify(mCallback).onVolumeChangeEvent(VolumeEventType.UP, 1);

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        verify(mCallback).onVolumeChangeEvent(VolumeEventType.DOWN, 1);
    }

    @Test
    public void testStashConflictResolution() throws Exception {
        mKeyListener.onKeyEventActivity();

        // Volume UP (stashed)
        Intent intentUp = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentUp.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 10);
        intentUp.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 11);
        intentUp.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentUp);

        // Mute (triggers stash of UP)
        Intent intentMute = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        intentMute.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, true);
        intentMute.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentMute);

        // Volume DOWN (conflicts with stashed UP)
        Intent intentDown = new Intent(AudioManager.ACTION_VOLUME_CHANGED);
        intentDown.putExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 11);
        intentDown.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 10);
        intentDown.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
        mVolumeReceiver.onReceive(mContext, intentDown);

        // Should flush stashed UP immediately
        verify(mCallback).onVolumeChangeEvent(VolumeEventType.UP, 1);

        // Mute should be cancelled
        verify(mCallback, never()).onVolumeChangeEvent(VolumeEventType.MUTE, 1);

        mTestLooper.moveTimeForward(VALIDATION_WINDOW_MS + 10);
        mTestLooper.dispatchAll();

        // Should broadcast DOWN
        verify(mCallback).onVolumeChangeEvent(VolumeEventType.DOWN, 1);
    }
}
