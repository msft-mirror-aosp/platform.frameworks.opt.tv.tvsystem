package com.android.server.tv.input;

/**
 * Volume event type for {@link ITvKeyEventActivityCallback}
 */
@Backing(type="int")
enum VolumeEventType {
    UNDEFINED = 0,
    UP = 1,
    DOWN = 2,
    MUTE = 3,
    UNMUTE = 4,
}
