package com.workeffortwaste

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/** Owns the USB worker independently of any activity, including while its task is removed. */
class TransferService : Service() {
    inner class LocalBinder : Binder() {
        val model get() = this@TransferService.model
    }

    private val binder = LocalBinder()
    private lateinit var model: BackupModel
    private var wakeLock: PowerManager.WakeLock? = null
    private var operation: FlashOperation? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Flash transfers", NotificationManager.IMPORTANCE_LOW),
        )
        model = BackupModel(application, ::beginTransfer, ::completeTransfer)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service intents, including stale notification actions, never cancel a transfer.
        // A queued start can arrive after cleanup. Never restart or replay a transfer.
        if (operation == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** Called synchronously from the visible UI, before any worker task or document I/O. */
    private fun beginTransfer(name: FlashOperation): Boolean {
        if (operation != null) return false
        return try {
            check(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                "Allow background operation in Settings, then try again"
            }
            val device = model.state.device
            check(device != null && model.manager.hasPermission(device)) {
                "Reconnect and grant USB permission first"
            }
            startForegroundService(Intent(this, TransferService::class.java))
            operation = name
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:flash-transfer")
                .apply { setReferenceCounted(false) }
            wakeLock?.acquire(30 * 60 * 1000L)
            true
        } catch (failure: Exception) {
            endTransfer()
            model.transferStartFailed(failure.message ?: failure.javaClass.simpleName)
            false
        }
    }

    /** Publishes only status and verified hashes after the worker has cleaned up. */
    private fun completeTransfer(outcome: TransferOutcome) {
        val name = operation
        try {
            if (name != null && outcome != TransferOutcome.CANCELLED) {
                val state = model.state
                val summary = state.status.asNotificationText() +
                        if (state.hash.isNotEmpty()) "\n${state.hashLabel}: ${state.hash}" else ""
                val reopen = PendingIntent.getActivity(
                    this, 2,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(RESULT_SUMMARY, summary),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                getSystemService(NotificationManager::class.java).notify(
                    RESULT_NOTIFICATION_ID,
                    Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_wave)
                        .setContentTitle("FM-1 ${name.label} result")
                        .setContentText(state.status.heading.withTerminalPeriod())
                        .setStyle(Notification.BigTextStyle().bigText(summary))
                        .setContentIntent(reopen)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .build(),
                )
            }
        } catch (failure: RuntimeException) {
            Log.w("TransferService", "Could not post transfer result", failure)
        } finally {
            endTransfer()
        }
    }

    private fun notification(): Notification {
        val reopen = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val warning = "Keep USB and power connected."
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_wave)
            .setContentTitle(operation?.title)
            .setContentText(warning)
            .setContentIntent(reopen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    /** Also used when startup fails before any worker work is scheduled. */
    private fun endTransfer() {
        operation = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } finally {
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Normal destruction is idle: active transfers keep this service started, not just bound.
        model.shutdown()
        endTransfer()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "flash-transfers"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2
        const val RESULT_SUMMARY = "com.workeffortwaste.TRANSFER_RESULT"
    }
}
