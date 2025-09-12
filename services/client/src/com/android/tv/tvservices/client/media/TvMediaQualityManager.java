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
package com.android.tv.tvservices.client.media;


import android.annotation.NonNull;
import android.content.Context;
import android.media.quality.MediaQualityContract;
import android.media.quality.MediaQualityManager;
import android.media.quality.PictureProfile;
import android.media.tv.TunedInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.PersistableBundle;
import android.util.Log;

import java.util.List;


/**
 * Provide access MediaQualityManager and adds foreground checking on the service side.
 */
public final class TvMediaQualityManager {
    private final MediaQualityManager mMediaQualityManager;
    private final TvInputManager mTvInputManager;
    private static final String TAG = "TvMediaQualityManager";
    private static final Boolean DEBUG = false;

    public TvMediaQualityManager(Context context) {
        mMediaQualityManager = context.getSystemService(MediaQualityManager.class);
        mTvInputManager = context.getSystemService(TvInputManager.class);
    }

    /**
     * Set PQ params by key value.
     * @param keys keys to be set
     * @param values values to be updated
     * @return true if set successfully, false otherwise
     */
    public boolean setPqParmsByKeyValue(@NonNull String[] keys, @NonNull int[] values) {
        PictureProfile currentProfile = getCurrentPictureProfile();
        if (currentProfile == null) {
            if (DEBUG) {
                Log.d(TAG, "Default profile is missing.");
            }
            return false;
        }
        String profileId = currentProfile.getProfileId();
        if (profileId == null) {
            if (DEBUG) {
                Log.d(TAG, "Default profile id is missing.");
            }
            return false;
        }
        PersistableBundle currentParams = currentProfile.getParameters();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(MediaQualityContract.PictureQuality.PARAMETER_BRIGHTNESS)) {
                currentParams.putDouble(keys[i], values[i] / 100.0);
            } else {
                currentParams.putInt(keys[i], values[i]);
            }
        }
        PictureProfile updatedProfile = new PictureProfile.Builder(currentProfile).setParameters(
                currentParams).build();
        mMediaQualityManager.updatePictureProfile(profileId, updatedProfile);
        return true;
    }

    /**
     * Get PQ params for the current picture profile..
     * @return PersistableBundle containing the PQ params.
     */
    public PersistableBundle getPqParams() {
        PictureProfile currentProfile = getCurrentPictureProfile();
        if (currentProfile == null) {
            if (DEBUG) {
                Log.d(TAG, "Default profile is missing.");
            }
            return null;
            }
        return currentProfile.getParameters();
    }

    public boolean isSupported() {
        if (mMediaQualityManager == null) {
            return false;
        }
        return mMediaQualityManager.isSupported();
    }

    private PictureProfile getCurrentPictureProfile() {
        if (mMediaQualityManager == null) {
            if (DEBUG) {
                Log.d(TAG, "MediaQualityManager is not available to set PQ params.");
            }
            return null;
        }
        String currentInputId = getCurrentInputId();
        if (currentInputId != null) {
            return mMediaQualityManager.getCurrentPictureProfileForTvInput(currentInputId);
        }
        return mMediaQualityManager.getDefaultPictureProfile();
    }

    private String getCurrentInputId() {
        if (mTvInputManager == null) {
            if (DEBUG) {
                Log.d(TAG, "TvInputManager is not available to get current input id.");
                return null;
            }
        }
        List<TunedInfo> tunedInfos = mTvInputManager.getCurrentTunedInfos();
        if (tunedInfos.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "getCurrentInput: null");
            }
            return null;
        }

        // The API supports multiple window, but we currently only support single window use case
        String inputId = tunedInfos.getFirst().getInputId();
        TvInputInfo info = mTvInputManager.getTvInputInfo(inputId);
        if (info == null) {
            Log.w(TAG, "TvInputManager failed to get input source for input id: " + inputId);
            return null;
        }

        if (info.getParentId() != null) {
            info = mTvInputManager.getTvInputInfo(info.getParentId());
        }
        return info == null ? null : info.getId();
    }

}
