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

/**
 * A wrapper of {@link InputManager} {@link KeyEventActivityListener} and adds foreground checking
 * on the service side.
 */
public interface TvKeyEventActivityCallback {
    public void onKeyActivity();

    /**
     * Called when volume change (UP, DOWN, MUTE) is reported
     * @param type The direction of the change (UP, DOWN, MUTE).
     * @param count The number of volume steps detected in the coalesced window.
     */
    public void onVolumeChangeEvent(int type, int count);
}
