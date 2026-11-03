package dev.herdr.companion

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorModernAndroidTest {
    private val application get() = RuntimeEnvironment.getApplication() as Application
    @Before fun setup() {
        SharedRelay.resetForTest()
        application.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    @Test fun notificationPermissionDeniedDoesNotStartHiddenMonitoring() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(MonitorNotifications.allowed(application))
        assertThrows(IllegalStateException::class.java) { AgentMonitorService.start(application) }
        assertFalse(MonitorPreferences(application).enabled)
    }
    @Test fun revokingNotificationsStopsMonitoringInsteadOfKeepingAnInvisibleWakeLock() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        MonitorPreferences(application).enabled = true
        val controller = Robolectric.buildService(AgentMonitorService::class.java).create(); val service = controller.get()
        try {
            service.onStartCommand(Intent(application, AgentMonitorService::class.java), 0, 1)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock(); assertTrue(lock.isHeld)
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertFalse(MonitorPreferences(application).enabled); assertFalse(lock.isHeld); assertFalse(AgentMonitorService.active)
        } finally { controller.destroy() }
    }
    @Test fun android15UsesDeclaredPrivateSpecialUseServiceNotTimeLimitedDataSync() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val info = application.packageManager.getServiceInfo(ComponentName(application, AgentMonitorService::class.java), 0)
        assertFalse(info.exported); assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        MonitorPreferences(application).enabled = true
        val controller = Robolectric.buildService(AgentMonitorService::class.java).create(); val service = controller.get()
        try {
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(Intent(application, AgentMonitorService::class.java), 0, 1))
            assertTrue(AgentMonitorService.active)
            service.onStartCommand(Intent(application, AgentMonitorService::class.java).setAction(AgentMonitorService.STOP), 0, 2)
            assertFalse(AgentMonitorService.active)
        } finally { controller.destroy() }
    }
}
