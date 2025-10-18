/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.server.tv.watchdogservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * Helper class for {@code PackageManager}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class PackageManagerHelper {

    /** Check {@link PackageManager#getNamesForUids(int[])}. */
    @Nullable
    public static String[] getNamesForUids(@NonNull PackageManager pm, int[] uids) {
        return pm.getNamesForUids(uids);
    }

    /** Check {@link PackageManager#getPackageInfoAsUser(String, int, int)}. */
    public static PackageInfo getPackageInfoAsUser(
            @NonNull PackageManager pm,
            @NonNull String packageName,
            int packageInfoFlags,
            @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        return pm.getPackageInfoAsUser(packageName, packageInfoFlags, userId);
    }

    /** Tells if the passed app is OEM app or not. */
    public static boolean isOemApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    /** Tells if the passed app is ODM app or not. */
    public static boolean isOdmApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_ODM) != 0;
    }

    /** Tells if the passed app is vendor app or not. */
    public static boolean isVendorApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    /** Tells if the passed app is system app or not. */
    public static boolean isSystemApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /** Tells if the passed app is updated system app or not. */
    public static boolean isUpdatedSystemApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /** Tells if the passed app is product app or not. */
    public static boolean isProductApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    /** Tells if the passed app is system ext vendor app or not. */
    public static boolean isSystemExtApp(@NonNull ApplicationInfo appInfo) {
        return (appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0;
    }

    /** Check {@link PackageManager#getPackageUidAsUser(String, int)}. */
    public static int getPackageUidAsUser(
            @NonNull PackageManager pm, @NonNull String packageName, @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        return pm.getPackageUidAsUser(packageName, userId);
    }
}
