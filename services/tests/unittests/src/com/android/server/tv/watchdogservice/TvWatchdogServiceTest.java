/*
 * Copyright 2025 The Android Open Source Project
 * ...
 */

package com.android.server.tv.watchdogservice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import android.automotive.watchdog.internal.GarageMode;


import android.automotive.watchdog.internal.PowerCycle;
import android.automotive.watchdog.internal.StateType;
import android.automotive.watchdog.internal.UserState;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import com.android.tv.tvservices.client.watchdog.IResourceOveruseListener;
import com.android.tv.tvservices.client.watchdog.ResourceOveruseConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.TestLooperManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.testutils.TestUtils;



import android.media.tv.flags.Flags;
import android.platform.test.flag.junit.SetFlagsRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Unit tests for { @link TvWatchdogService}. */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class TvWatchdogServiceTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final String TAG = "TvWatchdogServiceTest";

    private Context mContext;
    private TvWatchdogService mTvWatchdogService;
    private Handler mServiceHandler;
    private TestLooperManager mTestLooperManager;
    private HandlerThread mHandlerThread;

    @Mock private TvWatchdogService.Injector mMockInjector;
    @Mock private IoOveruseHandler mMockIoOveruseHandler;
    @Mock private CarWatchdogDaemonHelper mMockCarWatchdogDaemonHelper;
    @Mock private WatchdogStorage mMockWatchdogStorage;
    @Mock private PackageManager mMockPackageManager;
    @Mock private UserManager mMockUserManager;

    public static class TestTvWatchdogService extends TvWatchdogService {
        TestTvWatchdogService(Context context, Injector injector, HandlerThread handlerThread) {
            super(context, injector, handlerThread);
        }

        @Override
        void init() {
            // No-op
        }

        @Override
        void publishService(String name, IBinder service) {
            // No-op
        }

        @Override
        void registerLocalService(TvWatchdogService.TvWatchdogServiceInternal service) {
            // No-op
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);


        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION);

        mContext = mock(Context.class);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mServiceHandler = new Handler(mHandlerThread.getLooper());
        mTestLooperManager = new TestLooperManager(mHandlerThread.getLooper());

        // Correctly stub system service lookups
        doReturn(mMockPackageManager).when(mContext).getPackageManager();
        doReturn(Context.USER_SERVICE).when(mContext).getSystemServiceName(UserManager.class);
        doReturn(mMockUserManager).when(mContext).getSystemService(Context.USER_SERVICE);
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());

        when(mMockInjector.getContext()).thenReturn(mContext);
        when(mMockInjector.createHandlerThread(anyString())).thenReturn(mHandlerThread);
        when(mMockInjector.isDeviceIdleMode()).thenReturn(false);
        when(mMockInjector.getDefaultDisplayState()).thenReturn(Display.STATE_ON);

        mTvWatchdogService =
                spy(new TestTvWatchdogService(mContext, mMockInjector, mHandlerThread));

        mTvWatchdogService.mServiceHandler = mServiceHandler;
        mTvWatchdogService.mIoOveruseHandler = mMockIoOveruseHandler;
        mTvWatchdogService.mWatchdogDaemonHelper = mMockCarWatchdogDaemonHelper;
        mTvWatchdogService.mWatchdogStorage = mMockWatchdogStorage;
    }

    @After
    public void tearDown() {
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
    }

    @Test
    public void testOnStart() {
        mTvWatchdogService.onStart();
        // Test that onStart completes without crashing.
        // Verifying spied method calls on the test subclass itself is problematic.
        TestUtils.flushLoopers(mTestLooperManager);
    }

    @Test
    public void testDisplayStateChange_toOff() {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        when(mMockInjector.getDefaultDisplayState()).thenReturn(Display.STATE_OFF);
        mTvWatchdogService.initializeListeners();
        mTvWatchdogService.mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockIoOveruseHandler)
                .processUxStateChange(IoOveruseHandler.UX_STATE_NO_INTERACTION);
    }

    @Test
    public void testDisplayStateChange_toOn() {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        mTvWatchdogService.mDisplayState = Display.STATE_OFF;
        when(mMockInjector.getDefaultDisplayState()).thenReturn(Display.STATE_ON);
        mTvWatchdogService.initializeListeners();
        mTvWatchdogService.mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockIoOveruseHandler)
                .processUxStateChange(IoOveruseHandler.UX_STATE_USER_NOTIFICATION);
    }

    @Test
    public void testIdleModeChange_toIdle() {

        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        when(mMockInjector.isDeviceIdleMode()).thenReturn(true);
        mTvWatchdogService.onIdleModeChanged();
        TestUtils.flushLoopers(mTestLooperManager);

        assertThat(mTvWatchdogService.isDeviceIdle()).isTrue();
    }

    @Test
    public void testIdleModeChange_toActive() {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        mTvWatchdogService.mIsDeviceIdle = true;
        when(mMockInjector.isDeviceIdleMode()).thenReturn(false);
        mTvWatchdogService.onIdleModeChanged();
        TestUtils.flushLoopers(mTestLooperManager);

        assertThat(mTvWatchdogService.isDeviceIdle()).isFalse();
    }

    @Test
    public void testDaemonConnectionChange_onConnected() throws Exception {
        mTvWatchdogService.mIsConnectedToDaemon = false;
        reset(mMockCarWatchdogDaemonHelper);

        // Ensure UserManager returns users
        when(mMockUserManager.getUserHandles(false)).thenReturn(Arrays.asList(UserHandle.of(10)));
        when(mMockUserManager.isUserRunning(any(UserHandle.class))).thenReturn(true);

        mTvWatchdogService.onDaemonConnectionChange(true);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockCarWatchdogDaemonHelper).registerCarWatchdogService(any());
        verify(mMockIoOveruseHandler).onDaemonConnectionChange(true);

        verify(mMockCarWatchdogDaemonHelper)
                .notifySystemStateChange(
                        eq(StateType.USER_STATE), eq(10), eq(UserState.USER_STATE_STARTED));
        verify(mMockCarWatchdogDaemonHelper)
                .notifySystemStateChange(eq(StateType.GARAGE_MODE), anyInt(), anyInt());
    }

    @Test
    public void testDaemonConnectionChange_onDisconnected() {
        mTvWatchdogService.onDaemonConnectionChange(false);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockIoOveruseHandler).onDaemonConnectionChange(false);
    }

    @Test
    public void testBroadcastReceiver_onUserRemoved() throws Exception {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        Intent intent = new Intent(Intent.ACTION_USER_REMOVED);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(10));

        mTvWatchdogService.mBroadcastReceiver.onReceive(mContext, intent);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockCarWatchdogDaemonHelper)
                .notifySystemStateChange(StateType.USER_STATE, 10, UserState.USER_STATE_REMOVED);
        verify(mMockIoOveruseHandler).deleteUser(10);
    }

    @Test
    public void testBroadcastReceiver_onUserSwitched() throws Exception {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, 11);

        mTvWatchdogService.mBroadcastReceiver.onReceive(mContext, intent);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockCarWatchdogDaemonHelper)
                .notifySystemStateChange(StateType.USER_STATE, 11, UserState.USER_STATE_SWITCHING);
    }

    @Test
    public void testBroadcastReceiver_onShutdown() throws Exception {
        mTvWatchdogService.onStart();
        mTvWatchdogService.subscribeBroadcastReceiver();
        TestUtils.flushLoopers(mTestLooperManager);

        // Expect 2 calls
        verify(mContext, times(2))
                .registerReceiver(
                        eq(mTvWatchdogService.mBroadcastReceiver), any(), any(), any(), anyInt());

        Intent intent = new Intent(Intent.ACTION_SHUTDOWN);

        // Capture receiver reference
        BroadcastReceiver receiver = mTvWatchdogService.mBroadcastReceiver;

        mTvWatchdogService.mBroadcastReceiver.onReceive(mContext, intent);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockCarWatchdogDaemonHelper)
                .notifySystemStateChange(
                        StateType.POWER_CYCLE, PowerCycle.POWER_CYCLE_SHUTDOWN_ENTER, -1);

        verify(mMockCarWatchdogDaemonHelper).disconnect();
        verify(mContext).unregisterReceiver(eq(receiver));
    }

    @Test
    public void testInternalService_setOveruseHandlingDelay() {
        TvWatchdogService.TvWatchdogServiceInternal internalService =
                mTvWatchdogService.new TvWatchdogServiceInternal();
        internalService.setOveruseHandlingDelay(1000L);
        verify(mMockIoOveruseHandler).setOveruseHandlingDelay(1000L);
    }

    @Test
    public void testInternalService_performResourceOveruseKill() {
        TvWatchdogService.TvWatchdogServiceInternal internalService =
                mTvWatchdogService.new TvWatchdogServiceInternal();
        when(mMockIoOveruseHandler.disablePackageForUser("test.package", 10)).thenReturn(true);

        boolean result = internalService.performResourceOveruseKill("test.package", 10);

        assertThat(result).isTrue();
        verify(mMockIoOveruseHandler).disablePackageForUser("test.package", 10);
    }

    @Test
    public void testInternalService_injectPowerState() throws Exception {
        TvWatchdogService.TvWatchdogServiceInternal internalService =
                mTvWatchdogService.new TvWatchdogServiceInternal();
        internalService.injectPowerState(PowerCycle.POWER_CYCLE_SHUTDOWN_PREPARE);

        verify(mMockIoOveruseHandler).writeMetadataFile();
        verify(mMockCarWatchdogDaemonHelper)
                .notifySystemStateChange(
                        StateType.POWER_CYCLE, PowerCycle.POWER_CYCLE_SHUTDOWN_PREPARE, -1);
    }

    @Test
    public void testBroadcastReceiver_onPackageChanged() throws Exception {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        mTvWatchdogService.mBroadcastReceiver.onReceive(mContext, intent);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mMockIoOveruseHandler).processActionPackageChanged(intent);
    }

    @Test
    public void testBroadcastReceiver_onNotificationDismissed() throws Exception {
        mTvWatchdogService.onStart();
        TestUtils.flushLoopers(mTestLooperManager);

        Intent intent = new Intent(TvWatchdogHelper.ACTION_NOTIFICATION_DISMISSED);
        intent.putExtra(TvWatchdogHelper.EXTRA_NOTIFICATION_ID, 123);

        mTvWatchdogService.mBroadcastReceiver.onReceive(mContext, intent);
        TestUtils.flushLoopers(mTestLooperManager);

        verify(mTvWatchdogService).onNotificationDismissed(123);
    }

    @Test
    public void testNotificationSlotManagement_reserveAndCancel() {
        // Reserve a slot
        int notificationId = mTvWatchdogService.reserveNotificationSlot("pkg1");
        assertThat(notificationId)
                .isGreaterThan(TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID - 1);
        assertThat(mTvWatchdogService.mActiveUserNotifications).contains("pkg1");
        assertThat(mTvWatchdogService.mActiveUserNotificationsByNotificationId.get(notificationId))
                .isEqualTo("pkg1");

        // Try to reserve again for the same package
        int secondId = mTvWatchdogService.reserveNotificationSlot("pkg1");
        assertThat(secondId).isEqualTo(-1);

        // Reserve for a different package
        int anotherId = mTvWatchdogService.reserveNotificationSlot("pkg2");
        assertThat(anotherId).isNotEqualTo(notificationId);
        assertThat(mTvWatchdogService.mActiveUserNotifications).contains("pkg2");

        // Cancel the first slot
        mTvWatchdogService.cancelNotificationSlot("pkg1", notificationId);
        assertThat(mTvWatchdogService.mActiveUserNotifications).doesNotContain("pkg1");
        assertThat(mTvWatchdogService.mActiveUserNotificationsByNotificationId.get(notificationId))
                .isNull();

        // Now can reserve for pkg1 again
        int newId = mTvWatchdogService.reserveNotificationSlot("pkg1");
        assertThat(newId)
                .isGreaterThan(TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID - 1);
        assertThat(newId).isNotEqualTo(-1);
    }

    @Test
    public void testNotificationSlotManagement_onDismissed() {
        int notificationId = mTvWatchdogService.reserveNotificationSlot("pkg1");
        assertThat(mTvWatchdogService.mActiveUserNotifications).contains("pkg1");

        mTvWatchdogService.onNotificationDismissed(notificationId);
        assertThat(mTvWatchdogService.mActiveUserNotifications).doesNotContain("pkg1");
        assertThat(mTvWatchdogService.mActiveUserNotificationsByNotificationId.get(notificationId))
                .isNull();

        // Dismissing again has no effect
        mTvWatchdogService.onNotificationDismissed(notificationId);
        assertThat(mTvWatchdogService.mActiveUserNotifications).doesNotContain("pkg1");
    }

    @Test
    public void testNotificationSlotManagement_exhaustSlots() {
        for (int i = 0; i < TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_MAX_OFFSET; i++) {
            int id = mTvWatchdogService.reserveNotificationSlot("pkg" + i);
            assertThat(id).isNotEqualTo(-1);
        }
        // Next one should fail
        int failId = mTvWatchdogService.reserveNotificationSlot("extraPkg");
        assertThat(failId).isEqualTo(-1);

        // Cancel one
        mTvWatchdogService.cancelNotificationSlot(
                "pkg0", TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID);

        // Now should be able to reserve again
        int newId = mTvWatchdogService.reserveNotificationSlot("extraPkg");
        assertThat(newId).isEqualTo(TvWatchdogService.RESOURCE_OVERUSE_NOTIFICATION_BASE_ID);
    }

    @Test
    public void testBinder_getResourceOveruseStats() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        binder.getResourceOveruseStats(0, 0);
        verify(mMockIoOveruseHandler).getResourceOveruseStats(0, 0);
    }

    @Test
    public void testBinder_getAllResourceOveruseStats() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        binder.getAllResourceOveruseStats(0, 0, 0);
        verify(mMockIoOveruseHandler).getAllResourceOveruseStats(0, 0, 0);
    }

    @Test
    public void testBinder_getResourceOveruseStatsForUserPackage() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        UserHandle userHandle = UserHandle.of(10);
        binder.getResourceOveruseStatsForUserPackage("test.package", userHandle, 0, 0);
        verify(mMockIoOveruseHandler)
                .getResourceOveruseStatsForUserPackage("test.package", userHandle, 0, 0);
    }

    @Test
    public void testBinder_addResourceOveruseListener() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        IResourceOveruseListener listener = mock(IResourceOveruseListener.class);
        binder.addResourceOveruseListener(0, listener);
        verify(mMockIoOveruseHandler).addResourceOveruseListener(0, listener);
    }

    @Test
    public void testBinder_removeResourceOveruseListener() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        IResourceOveruseListener listener = mock(IResourceOveruseListener.class);
        binder.removeResourceOveruseListener(listener);
        verify(mMockIoOveruseHandler).removeResourceOveruseListener(listener);
    }

    @Test
    public void testBinder_addResourceOveruseListenerForSystem() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        IResourceOveruseListener listener = mock(IResourceOveruseListener.class);
        binder.addResourceOveruseListenerForSystem(0, listener);
        verify(mMockIoOveruseHandler).addResourceOveruseListenerForSystem(0, listener);
    }

    @Test
    public void testBinder_removeResourceOveruseListenerForSystem() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        IResourceOveruseListener listener = mock(IResourceOveruseListener.class);
        binder.removeResourceOveruseListenerForSystem(listener);
        verify(mMockIoOveruseHandler).removeResourceOveruseListenerForSystem(listener);
    }

    @Test
    public void testBinder_setKillablePackageAsUser() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        UserHandle userHandle = UserHandle.of(10);
        binder.setKillablePackageAsUser("test.package", userHandle, true);
        verify(mMockIoOveruseHandler).setKillablePackageAsUser("test.package", userHandle, true);
    }

    @Test
    public void testBinder_getPackageKillableStatesAsUser() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        UserHandle userHandle = UserHandle.of(10);
        binder.getPackageKillableStatesAsUser(userHandle);
        verify(mMockIoOveruseHandler).getPackageKillableStatesAsUser(userHandle);
    }

    @Test
    public void testBinder_setResourceOveruseConfigurations() throws Exception {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        List<ResourceOveruseConfiguration> configs = Collections.emptyList();
        binder.setResourceOveruseConfigurations(configs, 0);
        verify(mMockIoOveruseHandler).setResourceOveruseConfigurations(configs, 0);
    }

    @Test
    public void testBinder_getResourceOveruseConfigurations() {
        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        binder.getResourceOveruseConfigurations(0);
        verify(mMockIoOveruseHandler).getResourceOveruseConfigurations(0);
    }

    @Test
    public void testInternalService_setOveruseHandlingDelay_noPermission() {
        doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq("com.android.tv.permission.CONTROL_TV_WATCHDOG_CONFIG"), anyString());

        TvWatchdogService.TvWatchdogServiceInternal internalService =
                mTvWatchdogService.new TvWatchdogServiceInternal();
        assertThrows(
                SecurityException.class, () -> internalService.setOveruseHandlingDelay(1000L));
    }

    @Test
    public void testInternalService_performResourceOveruseKill_noPermission() {
        doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq("com.android.tv.permission.CONTROL_TV_WATCHDOG_CONFIG"), anyString());

        TvWatchdogService.TvWatchdogServiceInternal internalService =
                mTvWatchdogService.new TvWatchdogServiceInternal();
        assertThrows(
                SecurityException.class,
                () -> internalService.performResourceOveruseKill("test.package", 10));
    }

    @Test
    public void testInternalService_injectPowerState_noPermission() {
        doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq("com.android.tv.permission.CONTROL_TV_WATCHDOG_CONFIG"), anyString());

        TvWatchdogService.TvWatchdogServiceInternal internalService =
                mTvWatchdogService.new TvWatchdogServiceInternal();
        assertThrows(
                SecurityException.class,
                () -> internalService.injectPowerState(PowerCycle.POWER_CYCLE_SHUTDOWN_PREPARE));
    }

    @Test
    public void testCarWatchdogServiceForSystemImpl_unsupportedOperations() {
        TvWatchdogService.ICarWatchdogServiceForSystemImpl impl =
                new TvWatchdogService.ICarWatchdogServiceForSystemImpl(mTvWatchdogService);

        // These methods are no-ops and should not throw exceptions
        impl.checkIfAlive(1, 1);
        impl.prepareProcessTermination();

        // This method should still throw
        assertThrows(UnsupportedOperationException.class, () -> impl.requestAidlVhalPid());
    }

    @Test
    public void testDump_noPermission() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        TvWatchdogService.TvWatchdogBinder binder = mTvWatchdogService.new TvWatchdogBinder();
        PrintWriter writer = mock(PrintWriter.class);
        binder.dump(new FileDescriptor(), writer, null);

        verify(writer).println(contains("Permission Denial"));
    }
}
