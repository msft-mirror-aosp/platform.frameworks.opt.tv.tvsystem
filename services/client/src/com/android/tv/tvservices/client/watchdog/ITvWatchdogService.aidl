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

import com.android.tv.tvservices.client.watchdog.IResourceOveruseListener;
import com.android.tv.tvservices.client.watchdog.PackageKillableState;
import com.android.tv.tvservices.client.watchdog.ResourceOveruseConfiguration;
import com.android.tv.tvservices.client.watchdog.ResourceOveruseStats;
import android.os.UserHandle;

/** @hide */
interface ITvWatchdogService {

    ResourceOveruseStats getResourceOveruseStats(
        in int resourceOveruseFlag, in int maxStatsPeriod);
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.COLLECT_TV_WATCHDOG_METRICS")
    List<ResourceOveruseStats> getAllResourceOveruseStats(
        in int resourceOveruseFlag, in int minimumStatsFlag, in int maxStatsPeriod);
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.COLLECT_TV_WATCHDOG_METRICS")
    ResourceOveruseStats getResourceOveruseStatsForUserPackage(
        in String packageName, in UserHandle userHandle, in int resourceOveruseFlag,
            in int maxStatsPeriod);

    // addResourceOveruseListener needs to get callingUid, so cannot be oneway.
    void addResourceOveruseListener(
        in int resourceOveruseFlag, in IResourceOveruseListener listener);
    oneway void removeResourceOveruseListener(in IResourceOveruseListener listener);

    // Following APIs need to get calling pid/uid for permission checking, so cannot be oneway.
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.COLLECT_TV_WATCHDOG_METRICS")
    void addResourceOveruseListenerForSystem(
        in int resourceOveruseFlag, in IResourceOveruseListener listener);
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.COLLECT_TV_WATCHDOG_METRICS")
    void removeResourceOveruseListenerForSystem(in IResourceOveruseListener listener);
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.CONTROL_TV_WATCHDOG_CONFIG")
    void setKillablePackageAsUser(in String packageName, in UserHandle userHandle,
        in boolean isKillable);
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.CONTROL_TV_WATCHDOG_CONFIG")
    List<PackageKillableState> getPackageKillableStatesAsUser(in UserHandle user);
    @EnforcePermission("com.android.tv.tvservices.client.watchdog.Permission.CONTROL_TV_WATCHDOG_CONFIG")
    int setResourceOveruseConfigurations(
        in List<ResourceOveruseConfiguration> configurations, in int resourceOveruseFlag);
    @EnforcePermission(anyOf = {"com.android.tv.tvservices.client.watchdog.Permission.COLLECT_TV_WATCHDOG_METRICS", "com.android.tv.tvservices.client.watchdog.Permission.CONTROL_TV_WATCHDOG_CONFIG"})
    List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
        in int resourceOveruseFlag);
}
