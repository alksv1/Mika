package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps an explicitly enabled phone standby visible. Telecom still owns the actual call. */
class CallStandbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: CallRuntime? = null
    private var statusJob: Job? = null
    private var promoted = false
    private var stopping = false
    internal val leaseAvailable: Boolean get() = promoted && !stopping
    internal var stickyAutoAnswer = false
        private set

    internal fun revokeIncomingAuthorization() {
        stickyAutoAnswer = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = (application as? MikaApp)?.calls
        runtime = calls
        val explicit = intent?.action == ACTION_START && calls?.isStandbyStartRequested(intent.getStringExtra(EXTRA_REQUEST_ID)) == true
        // Always acknowledge the OS start before rejecting a stale or cancelled request. Android may
        // crash the process if we stop before acknowledging a startForegroundService delivery.
        val notificationAllowed = notificationsAvailable(this, create = true)
        if (!promoted) promoted = runCatching {
            val notification = notification(Notice(getString(R.string.calls_standby_checking), null))
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (!promoted) {
            if (explicit || intent == null) calls?.onStandbyStartFailed(getString(R.string.calls_standby_start_blocked))
            requestStop(startId)
            return START_NOT_STICKY
        }
        if (calls == null || (intent != null && !explicit)) {
            if (calls != null && stickyAutoAnswer && calls.hasAuthorizedIncomingStandby()) return START_STICKY
            if (calls?.hasOutgoingForegroundWork() != true || statusJob == null) requestStop(startId)
            return START_NOT_STICKY
        }
        val incoming = calls.hasAuthorizedIncomingStandby() && (intent == null || calls.isIncomingStandbyStartRequested() || stickyAutoAnswer)
        if (!notificationAllowed || !CallStandbyPolicy.acceptsStart(incoming, explicit, calls.hasOutgoingForegroundWork())) {
            calls.onStandbyStartFailed(getString(
                if (notificationAllowed) R.string.calls_standby_not_ready else R.string.calls_standby_notifications_required,
            ))
            requestStop(startId)
            return START_NOT_STICKY
        }

        stickyAutoAnswer = incoming
        calls.onStandbyStarted(this)
        if (statusJob == null) statusJob = scope.launch {
            calls.state.map { state ->
                val lockedDial = calls.hasOutgoingForegroundWork()
                val receive = stickyAutoAnswer && calls.hasAuthorizedIncomingStandby()
                if ((!receive && !lockedDial) || !notificationsAvailable(this@CallStandbyService)) return@map null
                val requestId = state.lockedDialRequestId
                when {
                    state.lockedDialPreparing -> Notice(
                        getString(R.string.calls_standby_locked_dial_preparing, state.lockedDialRemainingSeconds ?: 0), requestId)
                    requestId != null -> Notice(
                        getString(R.string.calls_device_locked_dial_remaining, state.lockedDialRemainingSeconds ?: 0), requestId)
                    lockedDial && state.aiActive -> Notice(getString(R.string.calls_standby_ai_active))
                    lockedDial -> Notice(getString(R.string.calls_notification_preparing))
                    state.autoAnswerStage == CallAutoAnswerStage.PAUSED -> Notice(getString(R.string.calls_auto_paused))
                    // Tapping opens the existing standby checklist with the reason, unless a call is on.
                    state.backgroundStandbyBlockedReason != null -> Notice(getString(R.string.calls_standby_unavailable),
                        opensStandbySettings = state.phase == CallPhase.IDLE)
                    state.aiActive -> Notice(getString(R.string.calls_standby_ai_active))
                    else -> Notice(getString(R.string.calls_standby_running))
                }
            }.distinctUntilChanged().collect { notice ->
                if (notice == null) requestStop()
                else if (runCatching {
                    checkNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification(notice))
                }.isFailure) requestStop()
            }
        }
        // A null Intent is only accepted when Android restarts this already opted-in service.
        // CallStandbyRecoveryJob reuses the same existing incoming-only grant.
        return if (incoming) START_STICKY else START_NOT_STICKY
    }

    private fun requestStop(startId: Int? = null) {
        // A queued stop cannot be revoked by reusing this instance for a new outgoing lease.
        stopping = true
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        runtime?.onStandbyStopped(this)
        runtime = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private data class Notice(val summary: String, val requestId: String? = null, val opensStandbySettings: Boolean = false)

    private fun notification(notice: Notice): Notification {
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notice.summary)
            .setContentIntent(notificationContentIntent(this, notice.opensStandbySettings))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(if (notice.requestId == null) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                notice.requestId?.let { id ->
                    val cancel = PendingIntent.getBroadcast(this@CallStandbyService, 0,
                        Intent(this@CallStandbyService, CallNotificationAction::class.java)
                            .setAction(CallNotifications.ACTION_CANCEL_LOCKED_DIAL)
                            .setData(Uri.fromParts(CallNotifications.LOCKED_DIAL_SCHEME, id, null)), PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null, getString(R.string.calls_device_locked_dial_cancel), cancel).build())
                }
            }
            .build()
    }

    companion object {
        const val CHANNEL = "mika_call_standby"
        private const val ACTION_START = "com.wangzan101.mika.calls.START_STANDBY"
        private const val EXTRA_REQUEST_ID = "standby_start_id"
        private const val NOTIFICATION_ID = 90_118
        private const val STANDBY_SETTINGS_REQUEST_CODE = 90_120

        /** Opens the phone page; when standby is blocked, the existing standby checklist (FlashPhone v1.1.15). */
        internal fun notificationContentIntent(context: Context, opensStandbySettings: Boolean): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(if (opensStandbySettings) MainActivity.EXTRA_OPEN_CALL_STANDBY else MainActivity.EXTRA_OPEN_CALLS, true)
            val requestCode = if (opensStandbySettings) STANDBY_SETTINGS_REQUEST_CODE else NOTIFICATION_ID
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        internal fun startIntent(context: Context, requestId: String? = null): Intent =
            Intent(context, CallStandbyService::class.java).setAction(ACTION_START).putExtra(EXTRA_REQUEST_ID, requestId)

        internal fun notificationsAvailable(context: Context, create: Boolean = false): Boolean = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (create) manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.calls_background_service), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
        }.getOrDefault(false)
    }
}
