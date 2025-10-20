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
import android.content.Context;
import android.hardware.input.InputManager;
import android.hardware.input.InputManager.KeyEventActivityListener;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

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
    private final TvKeyEventActivityCallbackHandler mCallbacks =
            new TvKeyEventActivityCallbackHandler();
    private final Map<IBinder, Integer> mCallbackUids = new HashMap<>();
    private final LocalKeyEventActivityListener mLocalListener =
            new LocalKeyEventActivityListener();
    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private InputManager mInputManager;
    private ActivityManager mActivityManager;
    private boolean mIsListeningToInputManager = false;

    public TvKeyInputManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Starting TV Input Manager Service");
        mInputManager = getContext().getSystemService(InputManager.class);
        mActivityManager = getContext().getSystemService(ActivityManager.class);
        publishBinderService(ITvKeyInputManagerService.NAME, new BinderService());
    }

    private class TvKeyEventActivityCallbackHandler
            extends RemoteCallbackList<ITvKeyEventActivityCallback> {
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
            if (DEBUG) Log.v(TAG, "onKeyEventActivity received from InputManager");

            final int count = mCallbacks.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    ITvKeyEventActivityCallback callback = mCallbacks.getBroadcastItem(i);
                    Integer clientUid;
                    synchronized (mCallbacks) {
                        clientUid = mCallbackUids.get(callback.asBinder());
                    }

                    if (clientUid != null && isUidInForeground(clientUid)) {
                        if (DEBUG)
                            Log.v(
                                    TAG,
                                    "Broadcasting onKeyEventActivity to foreground client UID: "
                                            + clientUid);
                        callback.onKeyEventActivity();
                    } else {
                        if (DEBUG)
                            Log.v(
                                    TAG,
                                    "Skipping broadcast to UID "
                                            + clientUid
                                            + " (not found or not in foreground)");
                    }
                } catch (RemoteException e) {
                    // The RemoteCallbackList handles removing dead listeners automatically
                    Log.w(TAG, "Failed to broadcast onKeyEventActivity to listener", e);
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    private boolean isUidInForeground(int uid) {
        final int importance = mActivityManager.getUidImportance(uid);
        final boolean isForeground =
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        if (DEBUG)
            Log.v(
                    TAG,
                    "UID "
                            + uid
                            + " has importance: "
                            + importance
                            + " (isForeground="
                            + isForeground
                            + ")");
        return isForeground;
    }

    private void startListeningToInputManager() {
        mExecutor.execute(
                () -> {
                    synchronized (mCallbacks) {
                        if (!mIsListeningToInputManager
                                && mInputManager != null
                                && mCallbacks.getRegisteredCallbackCount() > 0) {
                            if (mInputManager.registerKeyEventActivityListener(mLocalListener)) {
                                mIsListeningToInputManager = true;
                                if (DEBUG) Log.v(TAG, "Successfully registered with InputManager.");
                            } else {
                                Log.e(TAG, "Failed to register listener with InputManager.");
                            }
                        }
                    }
                });
    }

    private void stopListeningToInputManager() {
        mExecutor.execute(
                () -> {
                    synchronized (mCallbacks) { // Synchronize access
                        if (mIsListeningToInputManager
                                && mInputManager != null
                                && mCallbacks.getRegisteredCallbackCount() == 0) {
                            if (DEBUG) Log.v(TAG, "Unregistering listener from InputManager");
                            mInputManager.unregisterKeyEventActivityListener(mLocalListener);
                            mIsListeningToInputManager = false;
                        }
                    }
                });
    }

    private final class BinderService extends ITvKeyInputManagerService.Stub {
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
                if (DEBUG)
                    Log.v(
                            TAG,
                            "Callback registered. Count: "
                                    + mCallbacks.getRegisteredCallbackCount());
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
                if (DEBUG)
                    Log.v(
                            TAG,
                            "Callback unregistered ("
                                    + wasRegistered
                                    + "). Count: "
                                    + mCallbacks.getRegisteredCallbackCount());
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
                writer.printf(
                        " mIsListeningToInputManager: %s\n",
                        mIsListeningToInputManager ? "yes" : "no");
                writer.printf(
                        " mCallbacks.getRegisteredCallbackCount(): %d\n",
                        mCallbacks.getRegisteredCallbackCount());
            }
            writer.println("============ End of TV Input Manager Service Dump ============");
        }
    }
}
