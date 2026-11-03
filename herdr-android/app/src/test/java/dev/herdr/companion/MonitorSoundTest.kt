package dev.herdr.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class MonitorSoundTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0)
        Settings.System.putString(context.contentResolver, Settings.System.NOTIFICATION_SOUND, "content://media/internal/audio/media/42")
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    @Test fun newChannelsUseNotificationSoundAndVibrationButKeepServiceQuiet() {
        MonitorNotifications.createChannels(context)
        val completion = manager.getNotificationChannel(MonitorNotifications.COMPLETION_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, completion.importance)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, completion.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, completion.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, completion.audioAttributes.contentType)
        assertTrue(completion.shouldVibrate()); assertFalse(completion.canBypassDnd())
        val service = manager.getNotificationChannel(MonitorNotifications.SERVICE_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, service.importance)
        assertNull(service.sound); assertFalse(service.shouldVibrate())
        val foreground = MonitorNotifications.foreground(context, "监控连接正常")
        assertEquals(MonitorNotifications.SERVICE_CHANNEL, foreground.channelId)
        assertNull(foreground.sound); assertNull(foreground.vibrate)
        assertTrue(foreground.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("已配置通知铃声"))
    }
    @Test fun registrationDoesNotResetAnExistingMutedChannelOrCreateAReplacement() {
        manager.createNotificationChannel(NotificationChannel(MonitorNotifications.COMPLETION_CHANNEL, "Existing", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        repeat(2) { MonitorNotifications.createChannels(context) }
        val channel = manager.getNotificationChannel(MonitorNotifications.COMPLETION_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate())
        assertEquals(setOf(MonitorNotifications.SERVICE_CHANNEL, MonitorNotifications.COMPLETION_CHANNEL), manager.notificationChannels.map { it.id }.toSet())
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("通道为静默"))
        assertTrue(MonitorNotifications.completionsAllowed(context))
    }
    @Test fun registrationPreservesAnExistingCustomRingtone() {
        val custom = Uri.parse("content://media/internal/audio/media/99")
        manager.createNotificationChannel(NotificationChannel(MonitorNotifications.COMPLETION_CHANNEL, "Existing", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(custom, Notification.AUDIO_ATTRIBUTES_DEFAULT) })
        MonitorNotifications.createChannels(context)
        assertEquals(custom, manager.getNotificationChannel(MonitorNotifications.COMPLETION_CHANNEL).sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(MonitorNotifications.COMPLETION_CHANNEL).importance)
    }
    @Test fun missingChannelIsNotReportedAsEnabled() {
        assertFalse(MonitorNotifications.completionsAllowed(context))
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("尚未创建"))
    }
    @Test fun reportsAChannelWithoutRingtone() {
        manager.createNotificationChannel(NotificationChannel(MonitorNotifications.COMPLETION_CHANNEL, "Existing", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        MonitorNotifications.createChannels(context)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("未选择铃声"))
        assertNull(manager.getNotificationChannel(MonitorNotifications.COMPLETION_CHANNEL).sound)
    }
    @Test fun reportsSilentModeZeroNotificationVolumeAndDoNotDisturbWithoutChangingThem() {
        MonitorNotifications.createChannels(context)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("静音/振动模式"))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("通知音量为 0"))
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("勿扰模式"))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, manager.currentInterruptionFilter)
    }
    @Test fun reportsSystemDefaultRingtoneSetToNone() {
        MonitorNotifications.createChannels(context)
        Settings.System.putString(context.contentResolver, Settings.System.NOTIFICATION_SOUND, null)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("系统默认通知铃声为无"))
    }
    @Test fun testNotificationUsesRealCompletionChannelWithoutStartingMonitoringOrPretendingTaskCompleted() {
        val prefs = MonitorPreferences(context); val ledger = prefs.ledger()
        assertTrue(MonitorNotifications.testCompletion(context))
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(MonitorNotifications.COMPLETION_CHANNEL, notification.channelId)
        assertEquals("Herdr Pocket · 提醒测试", notification.extras.getCharSequence("android.title").toString())
        assertTrue(notification.extras.getCharSequence("android.text").toString().contains("不代表 Agent 任务完成"))
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("dev.herdr.companion.MONITOR_SETTINGS", intent.action)
        assertFalse(intent.hasExtra(MonitorNotifications.EXTRA_ROUTE))
        assertFalse(prefs.enabled); assertEquals(ledger, prefs.ledger())
        assertFalse(AgentMonitorService.active)
    }
    @Test fun realCompletionRequestsAlertsAgainForNextTurnsAndDoesNotOverwriteSoundTest() {
        MonitorNotifications.createChannels(context)
        val agent = JSONObject().put("workspace_id", "workspace").put("agent_session_id", "session").put("tab_label", "demo")
        val shared = SharedRelay.get(context)
        repeat(2) { MonitorNotifications.completed(context, shared, "computer", agent, JSONObject()) }
        assertEquals(1, shadowOf(manager).allNotifications.size)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(MonitorNotifications.COMPLETION_CHANNEL, notification.channelId)
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertNotEquals("silent", notification.group)
        assertTrue(MonitorNotifications.testCompletion(context))
        assertEquals(2, shadowOf(manager).allNotifications.size)
    }
    @Test fun disabledCompletionChannelDoesNotPostTestsOrReenableItself() {
        manager.createNotificationChannel(NotificationChannel(MonitorNotifications.COMPLETION_CHANNEL, "Disabled", NotificationManager.IMPORTANCE_NONE))
        assertFalse(MonitorNotifications.testCompletion(context))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(MonitorNotifications.COMPLETION_CHANNEL).importance)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("已被系统禁用"))
    }
    @Test fun deniedAppNotificationsDoNotPostTestsOrStartMonitoring() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(MonitorNotifications.testCompletion(context))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(MonitorPreferences(context).enabled)
        assertTrue(MonitorNotifications.completionSoundStatus(context).contains("通知权限未开启"))
    }
}
