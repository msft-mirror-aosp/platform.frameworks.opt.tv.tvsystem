package com.android.server.tv.input;

import com.android.server.tv.input.ITvKeyEventActivityCallback;

/**
 * Service interface for accessing the hidden KeyEventActivityListener API
 * from InputManager.
 * @hide
 */
interface ITvKeyInputManagerService {
    const String NAME = "TvKeyInputManagerService";
     /**
     * Registers a callback to receive key event activity notifications.
     *
     * @param callback The callback interface implemented by the client.
     *        clientUid UID of the calling application.
     */
    void registerCallback(in ITvKeyEventActivityCallback callback, int clientUid);

    /**
     * Unregisters a previously registered callback.
     *
     * @param callback The callback interface previously registered.
     */
    void unregisterCallback(in ITvKeyEventActivityCallback callback);
}
