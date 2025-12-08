/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.tv.watchdogservice;

import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_SHUTDOWN;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED;

import static com.android.server.tv.watchdogservice.TvWatchdogHelper.ACTION_NOTIFICATION_DISMISSED;
import static com.android.server.tv.watchdogservice.TvWatchdogHelper.EXTRA_NOTIFICATION_ID;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.automotive.watchdog.internal.GarageMode;
import android.automotive.watchdog.internal.ICarWatchdogServiceForSystem;
import android.automotive.watchdog.internal.PackageInfo;
import android.automotive.watchdog.internal.PowerCycle;
import android.automotive.watchdog.internal.ResourceStats;
import android.automotive.watchdog.internal.StateType;
import android.automotive.watchdog.internal.UserState;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.tv.watchdogmanager.IResourceOveruseListener;
import android.media.tv.watchdogmanager.ITvWatchdogService;
import android.media.tv.watchdogmanager.PackageKillableState;
import android.media.tv.watchdogmanager.ResourceOveruseConfiguration;
import android.media.tv.watchdogmanager.ResourceOveruseStats;
import android.media.tv.watchdogmanager.TvWatchdogManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * TV Watchdog Service.
 *
 * <p>This service runs in the System Server to monitor system health, focusing on I/O overuse, and
 * interacts with a native watchdog daemon. It replaces CarWatchdogService for the TV platform.
 */
public class TvWatchdogService extends SystemService implements TvWatchdogHelper.Callback {

    static final String TAG = "TvWatchdogService";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String TV_WATCHDOG_SERVICE_NAME = "tv_watchdog";
    private static final String PERMISSION_CONTROL_TV_WATCHDOG_CONFIG =
            "android.permission.CONTROL_TV_WATCHDOG_CONFIG";
    private static final String PERMISSION_COLLECT_TV_WATCHDOG_METRICS =
            "android.permission.COLLECT_TV_WATCHDOG_METRICS";
    private static final String HANDLER_THREAD_NAME = "TvWatchdogServiceHandler";
    private static final String WATCHDOG_DIR_NAME = "tv_watchdog";
    private static final String FALLBACK_DATA_DIR_PATH = "/data/system";

    public static final String NOTIFICATION_CHANNEL_ID = "tv_watchdog_channel";
    public static final int RESOURCE_OVERUSE_NOTIFICATION_BASE_ID = 1100000;
    public static final int RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET = 1000;

    private final Context mContext;
    final Object mLock = new Object();
    HandlerThread mHandlerThread;
    ICarWatchdogServiceForSystemImpl mWatchdogServiceForSystem;

    Handler mServiceHandler;
    IoOveruseHandler mIoOveruseHandler;
    PackageInfoHandler mPackageInfoHandler;
    CarWatchdogDaemonHelper mWatchdogDaemonHelper;
    WatchdogStorage mWatchdogStorage;

    DisplayManager.DisplayListener mDisplayListener;
    BroadcastReceiver mBroadcastReceiver;

    @GuardedBy("mLock")
    boolean mIsConnectedToDaemon;

    @GuardedBy("mLock")
    boolean mIsDeviceIdle = false;

    @GuardedBy("mLock")
    int mDisplayState = Display.STATE_UNKNOWN;

    @GuardedBy("mLock")
    final SparseArray<String> mActiveUserNotificationsByNotificationId = new SparseArray<>();

    @GuardedBy("mLock")
    final ArraySet<String> mActiveUserNotifications = new ArraySet<>();

    @GuardedBy("mLock")
    private int mCurrentOveruseNotificationIdOffset;

    private final Injector mInjector;

    @VisibleForTesting
    public interface ServicePublisher {
        void publish(String name, IBinder service);
    }

    @VisibleForTesting
    public static class Injector {
        private final Context mContext;

        public Injector(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public int getDefaultDisplayState() {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            if (dm == null) return Display.STATE_UNKNOWN;
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            return (display != null) ? display.getState() : Display.STATE_UNKNOWN;
        }

        public HandlerThread createHandlerThread(String name) {
            return new HandlerThread(name);
        }

        public void registerDisplayListener(
                DisplayManager.DisplayListener listener, Handler handler) {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            if (dm != null) {
                dm.registerDisplayListener(listener, handler);
            }
        }

        public void unregisterDisplayListener(DisplayManager.DisplayListener listener) {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            if (dm != null) {
                dm.unregisterDisplayListener(listener);
            }
        }

        public boolean isDeviceIdleMode() {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            return pm != null && pm.isDeviceIdleMode();
        }
    }

    public TvWatchdogService(Context context) {
        this(context, new Injector(context), new HandlerThread(HANDLER_THREAD_NAME));
    }

    @VisibleForTesting
    public TvWatchdogService(Context context, Injector injector, HandlerThread handlerThread) {
        super(context);
        mContext = context;
        mInjector = injector;
        mHandlerThread = handlerThread;
        mWatchdogServiceForSystem = new ICarWatchdogServiceForSystemImpl(this);
    }

    @Override
    public void onStart() {
        mServiceHandler = new Handler(mHandlerThread.getLooper());
        initializeListeners();
        mServiceHandler.post(this::init);

        publishService(TV_WATCHDOG_SERVICE_NAME, new TvWatchdogBinder());
        registerLocalService(new TvWatchdogServiceInternal());
    }

    @VisibleForTesting
    void publishService(String name, IBinder service) {
        publishBinderService(name, service);
    }

    @VisibleForTesting
    void registerLocalService(TvWatchdogServiceInternal service) {
        LocalServices.addService(TvWatchdogServiceInternal.class, service);
    }

    void initializeListeners() {
        mDisplayListener =
                new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int id) {}

                    @Override
                    public void onDisplayRemoved(int id) {}

                    @Override
                    public void onDisplayChanged(int id) {
                        if (id == Display.DEFAULT_DISPLAY) {
                            mServiceHandler.post(TvWatchdogService.this::onDisplayStateChanged);
                        }
                    }
                };

        mBroadcastReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        handleBroadcast(intent);
                    }
                };
    }

    @VisibleForTesting
    void init() {
        if (DEBUG) Slogf.d(TAG, "Initializing TvWatchdogService");

        mPackageInfoHandler = new PackageInfoHandler(mContext.getPackageManager());
        mWatchdogDaemonHelper = new CarWatchdogDaemonHelper(TAG, mHandlerThread.getLooper());
        mWatchdogStorage =
                new WatchdogStorage(
                        mContext,
                        new TimeSource() {
                            @Override
                            public Instant now() {
                                return Instant.now();
                            }
                        });

        android.content.res.Resources resources = mContext.getResources();
        int uidIoUsageSummaryTopCount =
                resources.getInteger(R.integer.config_tvWatchdogUidIoUsageSummaryTopCount);
        int ioUsageSummaryMinSystemTotalWrittenBytes =
                resources.getInteger(
                        R.integer.config_tvWatchdogIoUsageSummaryMinSystemTotalWrittenBytes);
        int packageKillableStateResetDays =
                resources.getInteger(R.integer.config_tvWatchdogUserPackageSettingsResetDays);
        int recurringOverusePeriodInDays =
                resources.getInteger(R.integer.config_tvWatchdogRecurringOverusePeriodInDays);
        int recurringOveruseTimes =
                resources.getInteger(R.integer.config_tvWatchdogRecurringOveruseTimes);

        TvWatchdogHelper helper = new TvWatchdogHelper(mContext, this, DEBUG);

        mIoOveruseHandler =
                new IoOveruseHandler(
                        mContext,
                        helper, // IoOveruseHandler.IoOveruseHelper implementation
                        mWatchdogDaemonHelper,
                        mPackageInfoHandler,
                        mWatchdogStorage,
                        new TimeSource() {
                            @Override
                            public Instant now() {
                                return Instant.now();
                            }
                        },
                        uidIoUsageSummaryTopCount,
                        ioUsageSummaryMinSystemTotalWrittenBytes,
                        packageKillableStateResetDays,
                        recurringOverusePeriodInDays,
                        recurringOveruseTimes,
                        mServiceHandler);
        mIoOveruseHandler.init();

        syncDisabledUserPackages();

        subscribeToDisplayChanges();
        subscribeBroadcastReceiver();

        mWatchdogDaemonHelper.addOnConnectionChangeListener(this::onDaemonConnectionChange);
        mWatchdogDaemonHelper.connect();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Slogf.d(TAG, "Boot completed. TV Watchdog is fully running.");
        }
    }

    void release() {
        if (mBroadcastReceiver != null) {
            mContext.unregisterReceiver(mBroadcastReceiver);
            mBroadcastReceiver = null;
        }
        unsubscribeFromDisplayChanges();
        if (mWatchdogStorage != null) {
            mWatchdogStorage.release();
        }
        unregisterFromDaemon();
        mWatchdogDaemonHelper.disconnect();
    }

    private void handleBroadcast(Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        switch (intent.getAction()) {
            case ACTION_DEVICE_IDLE_MODE_CHANGED -> onIdleModeChanged();
            case ACTION_SHUTDOWN -> {
                Slogf.i(TAG, "System is shutting down. Releasing TvWatchdogService resources.");
                onPowerState(PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER);
                if (mServiceHandler != null) {
                    mServiceHandler.runWithScissors(this::release, 5000);
                }
            }
            case ACTION_USER_REMOVED -> {
                UserHandle userHandle =
                        intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle.class);
                if (userHandle != null) {
                    int userId = userHandle.getIdentifier();
                    notifyUserStateChange(userId, UserState.USER_STATE_REMOVED);
                    mIoOveruseHandler.deleteUser(userId);
                }
            }
            case ACTION_USER_SWITCHED -> {
                int newUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (newUserId != UserHandle.USER_NULL) {
                    notifyUserStateChange(newUserId, UserState.USER_STATE_SWITCHING);
                }
            }
            case ACTION_PACKAGE_CHANGED -> mIoOveruseHandler.processActionPackageChanged(intent);
            case ACTION_NOTIFICATION_DISMISSED -> {
                int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
                if (notificationId != -1) {
                    onNotificationDismissed(notificationId);
                }
            }
        }
    }

    @VisibleForTesting
    void onDaemonConnectionChange(boolean isConnected) {
        mServiceHandler.post(
                () -> {
                    Slogf.i(
                            TAG,
                            "Watchdog daemon connection state changed: "
                                    + (isConnected ? "connected" : "disconnected"));
                    synchronized (mLock) {
                        mIsConnectedToDaemon = isConnected;
                    }
                    if (isConnected) {
                        registerToDaemon();
                    }
                    mIoOveruseHandler.onDaemonConnectionChange(isConnected);
                });
    }

    void onPowerState(int powerCycle) {
        switch (powerCycle) {
            case PowerCycle.POWER_CYCLE_SHUTDOWN_PREPARE -> {
                Slogf.i(TAG, "Handling shutdown prepare: writing metadata file.");
                mIoOveruseHandler.writeMetadataFile();
            }
            case PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER -> {
                Slogf.i(TAG, "Handling shutdown enter: writing to database.");
                mIoOveruseHandler.writeToDatabase();
            }
        }
        notifyPowerCycleChange(powerCycle);
    }

    private void onDisplayStateChanged() {
        int newState;
        synchronized (mLock) {
            int oldState = mDisplayState;
            newState = mInjector.getDefaultDisplayState();
            if (oldState == newState) return;
            mDisplayState = newState;
        }
        if (DEBUG) Slogf.d(TAG, "Display state changed to: " + Display.stateToString(newState));

        if (newState == Display.STATE_OFF) {
            mIoOveruseHandler.processUxStateChange(IoOveruseHandler.UX_STATE_NO_INTERACTION);
        } else {
            mIoOveruseHandler.processUxStateChange(IoOveruseHandler.UX_STATE_USER_NOTIFICATION);
        }
    }

    void onIdleModeChanged() {
        boolean isIdle = mInjector.isDeviceIdleMode();
        synchronized (mLock) {
            mIsDeviceIdle = isIdle;
        }
        if (DEBUG) Slogf.d(TAG, "Device idle mode changed. Is idle: " + isIdle);
        notifyIdleModeChange();
    }

    void registerToDaemon() {
        synchronized (mLock) {
            if (!mIsConnectedToDaemon) return;
        }
        try {
            mWatchdogDaemonHelper.registerCarWatchdogService(mWatchdogServiceForSystem);
            if (DEBUG) Slogf.d(TAG, "Successfully registered to watchdog daemon");
            notifyAllUserStates();
            notifyIdleModeChange();
        } catch (RemoteException | RuntimeException e) {
            Slogf.e(TAG, "Cannot register to watchdog daemon", e);
        }
    }

    void unregisterFromDaemon() {
        try {
            mWatchdogDaemonHelper.unregisterCarWatchdogService(mWatchdogServiceForSystem);
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, "Cannot unregister from watchdog daemon", e);
        }
    }

    void notifyUserStateChange(int userId, int userState) {
        try {
            mWatchdogDaemonHelper.notifySystemStateChange(StateType.USER_STATE, userId, userState);
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, "Failed to notify daemon of user state change", e);
        }
    }

    void notifyAllUserStates() {
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        final long identity = Binder.clearCallingIdentity();
        try {
            List<UserHandle> users = userManager.getUserHandles(false);
            for (UserHandle user : users) {
                int userState =
                        userManager.isUserRunning(user)
                                ? UserState.USER_STATE_STARTED
                                : UserState.USER_STATE_STOPPED;
                notifyUserStateChange(user.getIdentifier(), userState);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void notifyPowerCycleChange(@PowerCycle int powerCycle) {
        try {
            mWatchdogDaemonHelper.notifySystemStateChange(StateType.POWER_CYCLE, powerCycle, -1);
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, "Failed to notify daemon of power cycle change", e);
        }
    }

    // NOTE: GarageMode is an automotive-specific concept and not a valid state for TVs. However,
    // since TvWatchdogService reuses the ICarWatchdogServiceForSystem interface for daemon
    // communication, we map the device idle state to GarageMode.
    void notifyIdleModeChange() {
        int garageMode = isDeviceIdle() ? GarageMode.GARAGE_MODE_ON : GarageMode.GARAGE_MODE_OFF;
        try {
            mWatchdogDaemonHelper.notifySystemStateChange(StateType.GARAGE_MODE, garageMode, -1);
        } catch (RemoteException | RuntimeException e) {
            Slogf.w(TAG, "Failed to notify daemon of idle mode change", e);
        }
    }

    private void subscribeToDisplayChanges() {
        mInjector.registerDisplayListener(mDisplayListener, mServiceHandler);
        onDisplayStateChanged();
    }

    private void unsubscribeFromDisplayChanges() {
        mInjector.unregisterDisplayListener(mDisplayListener);
    }

    @VisibleForTesting
    void subscribeBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(ACTION_SHUTDOWN);
        filter.addAction(ACTION_USER_REMOVED);
        filter.addAction(ACTION_USER_SWITCHED);
        filter.addAction(ACTION_NOTIFICATION_DISMISSED);
        mContext.registerReceiver(
                mBroadcastReceiver, filter, null, mServiceHandler, Context.RECEIVER_NOT_EXPORTED);

        IntentFilter packageFilter = new IntentFilter(ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiver(
                mBroadcastReceiver,
                packageFilter,
                null,
                mServiceHandler,
                Context.RECEIVER_NOT_EXPORTED);
    }

    static File getWatchdogDirFile() {
        File dataDir = new File(FALLBACK_DATA_DIR_PATH);
        return new File(dataDir, WATCHDOG_DIR_NAME);
    }

    @VisibleForTesting
    void syncDisabledUserPackages() {
        mServiceHandler.post(
                () -> {
                    int[] userIds = mIoOveruseHandler.getAliveUserIds();
                    SparseArray<ArraySet<String>> disabledUserPackagesByUserId =
                            new SparseArray<>();
                    for (int userId : userIds) {
                        ContentResolver contentResolverForUser;
                        try {
                            contentResolverForUser =
                                    mContext.createContextAsUser(UserHandle.of(userId), 0)
                                            .getContentResolver();
                        } catch (Exception e) {
                            Slogf.w(
                                    TAG,
                                    "Could not create context for user %d: %s",
                                    userId,
                                    e.getMessage());
                            continue;
                        }

                        String settingsValue =
                                Settings.Secure.getString(
                                        contentResolverForUser,
                                        TvWatchdogSettings.Secure
                                                .KEY_PACKAGES_DISABLED_ON_RESOURCE_OVERUSE);
                        ArraySet<String> packages = IoOveruseHandler.extractPackages(settingsValue);

                        if (!packages.isEmpty()) {
                            disabledUserPackagesByUserId.put(userId, packages);
                        }
                    }
                    mIoOveruseHandler.setDisabledUserPackagesByUserId(disabledUserPackagesByUserId);
                    if (DEBUG) {
                        Slogf.d(
                                TAG,
                                "Synced disabled user packages from Settings.Secure to cache.");
                    }
                });
    }

    @Override
    public boolean isDeviceIdle() {
        synchronized (mLock) {
            return mIsDeviceIdle;
        }
    }

    @Override
    public int reserveNotificationSlot(String userPackageUniqueId) {
        synchronized (mLock) {
            if (mActiveUserNotifications.contains(userPackageUniqueId)) {
                Slogf.w(TAG, "Dropping duplicate notification request for " + userPackageUniqueId);
                return -1;
            }

            final int initialOffset = mCurrentOveruseNotificationIdOffset;
            while (true) {
                final int notificationId =
                        TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID
                                + mCurrentOveruseNotificationIdOffset;
                if (mActiveUserNotificationsByNotificationId.get(notificationId) == null) {
                    mActiveUserNotifications.add(userPackageUniqueId);
                    mActiveUserNotificationsByNotificationId.put(
                            notificationId, userPackageUniqueId);
                    mCurrentOveruseNotificationIdOffset =
                            (mCurrentOveruseNotificationIdOffset + 1)
                                    % TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
                    return notificationId;
                }

                mCurrentOveruseNotificationIdOffset =
                        (mCurrentOveruseNotificationIdOffset + 1)
                                % TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET;
                if (mCurrentOveruseNotificationIdOffset == initialOffset) {
                    Slogf.e(
                            TAG,
                            "All "
                                    + TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET
                                    + " notification slots are in use. Cannot post for "
                                    + userPackageUniqueId);
                    return -1;
                }
            }
        }
    }

    @Override
    public void cancelNotificationSlot(String userPackageUniqueId, int notificationId) {
        synchronized (mLock) {
            mActiveUserNotifications.remove(userPackageUniqueId);
            mActiveUserNotificationsByNotificationId.remove(notificationId);
        }
    }

    @Override
    public void onNotificationDismissed(int notificationId) {
        synchronized (mLock) {
            String userPackageUniqueId =
                    mActiveUserNotificationsByNotificationId.get(notificationId);
            if (userPackageUniqueId != null) {
                mActiveUserNotificationsByNotificationId.remove(notificationId);
                mActiveUserNotifications.remove(userPackageUniqueId);
                if (DEBUG) {
                    Slogf.d(TAG, "Cleared dismissed notification state for " + userPackageUniqueId);
                }
            }
        }
    }

    static final class ICarWatchdogServiceForSystemImpl extends ICarWatchdogServiceForSystem.Stub {
        private final WeakReference<TvWatchdogService> mServiceRef;

        ICarWatchdogServiceForSystemImpl(TvWatchdogService service) {
            mServiceRef = new WeakReference<>(service);
        }

        private TvWatchdogService getService() {
            return mServiceRef.get();
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            throw new UnsupportedOperationException("Health checking not supported in TV");
        }

        @Override
        public void prepareProcessTermination() {
            throw new UnsupportedOperationException("Health checking not supported in TV");
        }

        @Override
        public void requestAidlVhalPid() {
            throw new UnsupportedOperationException("VHAL not supported in TV");
        }

        @Override
        public List<PackageInfo> getPackageInfosForUids(int[] uids, List<String> prefixes) {
            TvWatchdogService s = getService();
            return (s == null || s.mPackageInfoHandler == null)
                    ? Collections.emptyList()
                    : s.mPackageInfoHandler.getPackageInfosForUids(uids, prefixes);
        }

        @Override
        public void onLatestResourceStats(List<ResourceStats> stats) {
            TvWatchdogService service = getService();
            if (service == null || service.mIoOveruseHandler == null) return;
            for (ResourceStats resourceStats : stats) {
                if (resourceStats.resourceOveruseStats != null) {
                    service.mIoOveruseHandler.latestIoOveruseStats(
                            resourceStats.resourceOveruseStats.packageIoOveruseStats);
                }
            }
        }

        @Override
        public void resetResourceOveruseStats(List<String> packageNames) {
            TvWatchdogService s = getService();
            if (s != null && s.mIoOveruseHandler != null) {
                s.mIoOveruseHandler.resetResourceOveruseStats(new ArraySet<>(packageNames));
            }
        }

        @Override
        public void requestTodayIoUsageStats() {
            TvWatchdogService s = getService();
            if (s != null && s.mIoOveruseHandler != null) {
                s.mIoOveruseHandler.asyncFetchTodayIoUsageStats();
            }
        }
    }

    public class TvWatchdogServiceInternal {
        public void setOveruseHandlingDelay(long millis) {
            mContext.enforceCallingOrSelfPermission(PERMISSION_CONTROL_TV_WATCHDOG_CONFIG, TAG);
            if (mIoOveruseHandler != null) mIoOveruseHandler.setOveruseHandlingDelay(millis);
        }

        public boolean performResourceOveruseKill(String packageName, @UserIdInt int userId) {
            mContext.enforceCallingOrSelfPermission(PERMISSION_CONTROL_TV_WATCHDOG_CONFIG, TAG);
            return (mIoOveruseHandler != null)
                    && mIoOveruseHandler.disablePackageForUser(packageName, userId);
        }

        public void injectPowerState(int powerCycle) {
            mContext.enforceCallingOrSelfPermission(PERMISSION_CONTROL_TV_WATCHDOG_CONFIG, TAG);
            onPowerState(powerCycle);
        }
    }

    class TvWatchdogBinder extends ITvWatchdogService.Stub {
        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer, String[] args) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                writer.println("Permission Denial: Requester does not have DUMP permission.");
                return;
            }

            boolean isProto = false;
            if (args != null) {
                for (String arg : args) {
                    if ("--proto".equals(arg)) {
                        isProto = true;
                        break;
                    }
                }
            }

            if (isProto) {
                final ProtoOutputStream proto = new ProtoOutputStream(fd);
                if (mIoOveruseHandler != null) {
                    mIoOveruseHandler.dumpProto(proto);
                }
                proto.flush();
            } else {
                IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
                pw.println("*" + TvWatchdogService.class.getSimpleName() + "*");
                pw.increaseIndent();
                synchronized (mLock) {
                    pw.println("Display state: " + Display.stateToString(mDisplayState));
                    pw.println("Device idle (Garage Mode): " + mIsDeviceIdle);
                    pw.println("Connected to daemon: " + mIsConnectedToDaemon);
                    pw.println("Active notifications: " + mActiveUserNotifications.size());
                }
                if (mIoOveruseHandler != null) {
                    mIoOveruseHandler.dump(pw);
                }
                pw.decreaseIndent();
            }
        }

        @Override
        public ResourceOveruseStats getResourceOveruseStats(
                int resourceOveruseFlag, int maxStatsPeriod) {
            if (mIoOveruseHandler == null) return null;
            return mIoOveruseHandler.getResourceOveruseStats(resourceOveruseFlag, maxStatsPeriod);
        }

        @Override
        @EnforcePermission(PERMISSION_COLLECT_TV_WATCHDOG_METRICS)
        public List<ResourceOveruseStats> getAllResourceOveruseStats(
                int resourceOveruseFlag, int minimumStatsFlag, int maxStatsPeriod) {
            getAllResourceOveruseStats_enforcePermission();
            if (mIoOveruseHandler == null) return Collections.emptyList();
            return mIoOveruseHandler.getAllResourceOveruseStats(
                    resourceOveruseFlag, minimumStatsFlag, maxStatsPeriod);
        }

        @Override
        @EnforcePermission(PERMISSION_COLLECT_TV_WATCHDOG_METRICS)
        public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
                @NonNull String packageName,
                @NonNull UserHandle userHandle,
                int resourceOveruseFlag,
                int maxStatsPeriod) {
            getResourceOveruseStatsForUserPackage_enforcePermission();
            if (mIoOveruseHandler == null) return null;
            return mIoOveruseHandler.getResourceOveruseStatsForUserPackage(
                    packageName, userHandle, resourceOveruseFlag, maxStatsPeriod);
        }

        @Override
        public void addResourceOveruseListener(
                int resourceOveruseFlag, @NonNull IResourceOveruseListener listener) {
            if (mIoOveruseHandler != null) {
                mIoOveruseHandler.addResourceOveruseListener(resourceOveruseFlag, listener);
            }
        }

        @Override
        public void removeResourceOveruseListener(@NonNull IResourceOveruseListener listener) {
            if (mIoOveruseHandler != null) {
                mIoOveruseHandler.removeResourceOveruseListener(listener);
            }
        }

        @Override
        @EnforcePermission(PERMISSION_COLLECT_TV_WATCHDOG_METRICS)
        public void addResourceOveruseListenerForSystem(
                int resourceOveruseFlag, @NonNull IResourceOveruseListener listener) {
            addResourceOveruseListenerForSystem_enforcePermission();
            if (mIoOveruseHandler != null) {
                mIoOveruseHandler.addResourceOveruseListenerForSystem(
                        resourceOveruseFlag, listener);
            }
        }

        @Override
        @EnforcePermission(PERMISSION_COLLECT_TV_WATCHDOG_METRICS)
        public void removeResourceOveruseListenerForSystem(
                @NonNull IResourceOveruseListener listener) {
            removeResourceOveruseListenerForSystem_enforcePermission();
            if (mIoOveruseHandler != null) {
                mIoOveruseHandler.removeResourceOveruseListenerForSystem(listener);
            }
        }

        @Override
        @EnforcePermission(PERMISSION_CONTROL_TV_WATCHDOG_CONFIG)
        public void setKillablePackageAsUser(
                String packageName, UserHandle userHandle, boolean isKillable) {
            setKillablePackageAsUser_enforcePermission();
            if (mIoOveruseHandler != null) {
                mIoOveruseHandler.setKillablePackageAsUser(packageName, userHandle, isKillable);
            }
        }

        @Override
        @NonNull
        @EnforcePermission(PERMISSION_CONTROL_TV_WATCHDOG_CONFIG)
        public List<PackageKillableState> getPackageKillableStatesAsUser(UserHandle user) {
            getPackageKillableStatesAsUser_enforcePermission();
            if (mIoOveruseHandler == null) return Collections.emptyList();
            return mIoOveruseHandler.getPackageKillableStatesAsUser(user);
        }

        @Override
        @EnforcePermission(PERMISSION_CONTROL_TV_WATCHDOG_CONFIG)
        public int setResourceOveruseConfigurations(
                List<ResourceOveruseConfiguration> configurations, int resourceOveruseFlag)
                throws RemoteException {
            setResourceOveruseConfigurations_enforcePermission();
            if (mIoOveruseHandler == null) return TvWatchdogManager.RETURN_CODE_ERROR;
            return mIoOveruseHandler.setResourceOveruseConfigurations(
                    configurations, resourceOveruseFlag);
        }

        @Override
        @NonNull
        @EnforcePermission(
                anyOf = {
                    PERMISSION_COLLECT_TV_WATCHDOG_METRICS,
                    PERMISSION_CONTROL_TV_WATCHDOG_CONFIG
                })
        public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
                int resourceOveruseFlag) {
            getResourceOveruseConfigurations_enforcePermission();
            if (mIoOveruseHandler == null) return Collections.emptyList();
            return mIoOveruseHandler.getResourceOveruseConfigurations(resourceOveruseFlag);
        }
    }
}
