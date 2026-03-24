/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tv.tvservices.client.watchdog;

/**
 * Permissions to use the TV watchdog.
 *
 * @hide
 */
public final class Permission {

    public static final String CONTROL_TV_WATCHDOG_CONFIG =
            "com.android.tv.permission.CONTROL_TV_WATCHDOG_CONFIG";
    public static final String COLLECT_TV_WATCHDOG_METRICS =
            "com.android.tv.permission.COLLECT_TV_WATCHDOG_METRICS";

    private Permission() {}
}
