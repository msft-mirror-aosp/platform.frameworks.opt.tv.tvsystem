package com.android.server.tv.input;

import com.android.server.tv.input.VolumeEventType;

/**
 * Callback interface for receiving key event activity notifications from the
 * ITvKeyInputManagerService.
 * @hide
 */
oneway interface ITvKeyEventActivityCallback {
    /**
     * Called when InputManager reports key event activity.
     */
    void onKeyEventActivity();

    /**
     * Called when volume change (UP, DOWN, MUTE) is reported
     * @param type The direction of the change (UP, DOWN, MUTE).
     * @param count The number of volume steps detected in the coalesced window.
     */
    void onVolumeChangeEvent(in VolumeEventType type, int count);
}
