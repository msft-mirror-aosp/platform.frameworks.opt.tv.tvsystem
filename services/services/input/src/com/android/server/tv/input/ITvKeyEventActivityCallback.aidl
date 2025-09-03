package com.android.server.tv.input;

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
}
