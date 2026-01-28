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
package com.android.tv.tvservices.client.input;

import android.annotation.NonNull;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.server.tv.input.ITvKeyEventActivityCallback;
import com.android.server.tv.input.ITvKeyInputManagerService;

import java.util.ArrayList;

/**
 * Provide access {@link InputManager} {@link KeyEventActivityListener} and adds foreground checking
 * on the service side.
 */
public final class TvKeyInputManager {
    private final Object mLock = new Object();
    private final ITvKeyInputManagerService mService;
    private final InternalCallback mInternalCallback;
    private final ArrayList<TvKeyEventActivityCallback> mCallbacks = new ArrayList<>();

    private class InternalCallback extends ITvKeyEventActivityCallback.Stub {
        @Override
        public void onKeyEventActivity() {
            synchronized (mLock) {
                for (int index = 0; index < mCallbacks.size(); index++) {
                    mCallbacks.get(index).onKeyActivity();
                }
            }
        }
    }

    public TvKeyInputManager() {
        this(
                ITvKeyInputManagerService.Stub.asInterface(
                        ServiceManager.getService(ITvKeyInputManagerService.NAME)));
    }

    TvKeyInputManager(ITvKeyInputManagerService service) {
        mService = service;
        mInternalCallback = new InternalCallback();
    }

    /**
     * Registers a callback to receive key event activity notifications.
     *
     * @param callback The callback interface implemented by the client. clientUid UID of the
     *                 calling application.
     */
    public void registerCallback(@NonNull TvKeyEventActivityCallback callback, int clientUid)
            throws RemoteException {
        if (mService == null) {
            throw new RemoteException("TvKeyInputManagerService not available");
        }
        synchronized (mLock) {
            if (mCallbacks.isEmpty()) {
                mService.registerCallback(mInternalCallback, clientUid);
            }
            if (!mCallbacks.contains(callback)) {
                mCallbacks.add(callback);
            }
        }
    }

    /**
     * Unregisters a previously registered callback.
     *
     * @param callback The callback interface previously registered.
     */
    public void unregisterCallback(@NonNull TvKeyEventActivityCallback callback)
            throws RemoteException {
        if (mService == null) {
            throw new RemoteException("TvKeyInputManagerService not available");
        }
        synchronized (mLock) {
            mCallbacks.remove(callback);
            if (mCallbacks.isEmpty()) {
                mService.unregisterCallback(mInternalCallback);
            }
        }
    }
}
