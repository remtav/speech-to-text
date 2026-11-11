package io.github.remtav.dictee.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.Downloadable
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Download queue shared by the setup and settings screens; runs one download at a time. */
object Downloads {
    data class Status(val downloaded: Long, val total: Long, val error: String? = null, val queued: Boolean = false) {
        val percent: Int get() = if (total > 0) (downloaded * 100 / total).toInt() else 0
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "model-download") }
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    val status = ConcurrentHashMap<String, Status>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    fun isBusy(id: String) = cancels.containsKey(id)
    fun anyBusy() = cancels.isNotEmpty()

    fun start(context: Context, item: Downloadable, label: String) {
        if (cancels.containsKey(item.id)) return
        val app = DicteeApp.from(context)
        val cancel = AtomicBoolean(false)
        cancels[item.id] = cancel
        status[item.id] = Status(0, item.files.sumOf { it.size }, queued = true)
        changed()
        DownloadService.ensureRunning(context)
        executor.execute {
            try {
                var lastPost = 0L
                app.store.download(item, cancel) { done, total ->
                    status[item.id] = Status(done, total)
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 250) {
                        lastPost = now
                        changed()
                        DownloadService.update(context, label, done, total)
                    }
                }
                status.remove(item.id)
            } catch (_: InterruptedIOException) {
                status.remove(item.id)
            } catch (e: Exception) {
                status[item.id] = Status(0, 0, error = e.message ?: e.javaClass.simpleName)
            } finally {
                cancels.remove(item.id)
                changed()
                if (cancels.isEmpty()) DownloadService.stop(context)
            }
        }
    }

    fun cancel(id: String) {
        cancels[id]?.set(true)
    }
}

/** Keeps the process alive (foreground, progress notification) while models download. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, getString(R.string.download_running), 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        if (!Downloads.anyBusy()) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "downloads"

        private fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }

        private fun notification(context: Context, text: String, done: Long, total: Long): Notification {
            channel(context)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .apply { if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false) }
                .build()
        }

        fun ensureRunning(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (_: Exception) {
                // Background start refused: the download still runs while the app is open.
            }
        }

        fun update(context: Context, label: String, done: Long, total: Long) {
            try {
                context.getSystemService(NotificationManager::class.java)
                    .notify(ID, notification(context, label, done, total))
            } catch (_: SecurityException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
