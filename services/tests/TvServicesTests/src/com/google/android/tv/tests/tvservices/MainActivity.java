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

package com.google.android.tv.tests.tvservices;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.android.tv.tvservices.client.input.TvKeyEventActivityCallback;
import com.android.tv.tvservices.client.input.TvKeyInputManager;

public class MainActivity extends FragmentActivity {
    private static final String TAG = "TvServicesTests";
    private TvKeyInputManager mTvKeyInputManager;
    private TestCallback mTestCallback;

    public static class TestCallback implements TvKeyEventActivityCallback {
        private Context mContext;

        TestCallback(Context context) {
            mContext = context;
        }

        @Override
        public void onKeyActivity() {
            Log.v(TAG, "TestCallback is called.");
        }

        @Override
        public void onVolumeChangeEvent(int type, int count) {
            Log.v(TAG, "TestCallback is called. Type: " + type + ", Count: " + count);
        }
    }

    @Override
    public final void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        mTvKeyInputManager = new TvKeyInputManager();
        mTestCallback = new TestCallback(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            mTvKeyInputManager.registerCallback(mTestCallback, Process.myUid());
        } catch (RemoteException e) {
            Log.e(TAG, "Should not throw an exception. " + e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            mTvKeyInputManager.unregisterCallback(mTestCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Should not throw an exception. " + e);
        }
    }
}
