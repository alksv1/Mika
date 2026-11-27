package com.wangzan101.mika.calls

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.wangzan101.mika.MikaApp

/**
 * Periodic backup for incoming standby when Android drops the sticky foreground-service restart
 * (ported from FlashPhone v1.1.14). It only re-requests the standby the owner already granted, under
 * the same rule as the visible-resume path; it never asks for a permission and never replays
 * outgoing work. Turning standby, auto-answer or calls off, or a full exit, cancels it; a force stop
 * cancels it at the system level. The 15-minute interval is a request; Android may defer it.
 */
class CallStandbyRecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // Runs on the main thread like the runtime. The service request is asynchronous, so
        // nothing is left for this job to hold on to.
        (application as MikaApp).calls.restoreAuthorizedStandbyFromSystem()
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 0x43414C52
        internal const val INTERVAL_MS = 15 * 60 * 1_000L

        /**
         * Keeps the job scheduled while the owner's grant stands. Battery and notification permission
         * are checked when it runs, so a permission that comes back re-arms standby without opening Mika.
         */
        internal fun reconcile(context: Context, authorized: Boolean) {
            runCatching {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                if (!authorized) return scheduler.cancel(JOB_ID)
                val pending = scheduler.getPendingJob(JOB_ID)
                if (pending != null && pending.isPeriodic && pending.intervalMillis == INTERVAL_MS && pending.isPersisted) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, CallStandbyRecoveryJob::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build()
                // Scheduling the same id replaces a job left from an older build with other timing.
                check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS)
            }.onFailure { Log.w("CallStandby", "Could not configure the standby recovery job", it) }
        }

        /** The owner opened Mika (and any full exit was lifted): the schedule follows the stored grant again. */
        internal fun onOwnerOpenedApp(app: MikaApp) {
            // Only bring the call runtime up for owners who granted standby at some point.
            if (CallSettings.hasStoredStandbyAuthorization(app)) app.calls.refreshStandbyRecovery()
            else reconcile(app, authorized = false)
        }

        internal fun systemAllowsScheduledStart(context: Context): Boolean = runCatching {
            CallStandbyPolicy.systemAllowsScheduledStart(
                Build.VERSION.SDK_INT,
                backgroundRestricted = context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted != false,
                ignoringBatteryOptimizations =
                    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
            )
        }.getOrDefault(false)
    }
}
