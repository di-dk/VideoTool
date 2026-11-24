package dev.videotool.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.videotool.app.MainActivity
import dev.videotool.app.R
import dev.videotool.app.engine.FfmpegRunner
import dev.videotool.app.engine.RifeRunner
import dev.videotool.app.engine.ToolFailed
import dev.videotool.app.tools.JobContext
import dev.videotool.app.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runs one tool job in the foreground so it survives the screen turning off. */
class ProcessingService : LifecycleService() {
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); return START_NOT_STICKY }
        val uri = intent?.data
        val toolId = intent?.getStringExtra(EXTRA_TOOL)
        val tool = toolId?.let { ToolRegistry.byId(it) }
        if (uri == null || tool == null || job?.isActive == true) { if (job?.isActive != true) stopSelf(); return START_NOT_STICKY }
        val opts = intent.getBundleExtra(EXTRA_OPTIONS)?.let { b -> b.keySet().associateWith { b.getString(it).orEmpty() } }.orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME) ?: "video"
        val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)

        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(tool.title, 0f, "Starting", true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        JobRepository.state.value = JobState.Running(tool.title, 0f, "Preparing")
        val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoTool:job").apply { acquire(6 * 60 * 60 * 1000L) }

        job = lifecycleScope.launch {
            val work = File(cacheDir, "job-${System.currentTimeMillis()}").also { it.mkdirs() }
            try {
                val result: JobState = withContext(Dispatchers.IO) {
                    val input = File(work, "input")
                    contentResolver.openInputStream(uri)?.use { src -> input.outputStream().use { src.copyTo(it) } }
                        ?: throw ToolFailed("Could not open the selected video")
                    val ffmpeg = FfmpegRunner(this@ProcessingService)
                    var info = ffmpeg.probe(input.path)
                    var source = input
                    if (preview) {
                        // Cut a short clip from the middle so the preview is quick to process.
                        val clipLen = minOf(PREVIEW_SECONDS, info.durationSec)
                        val start = maxOf(0.0, info.durationSec / 2 - clipLen / 2)
                        JobRepository.state.value = JobState.Running(tool.title, 0f, "Cutting 480p preview clip")
                        val clip = File(work, "clip.mp4")
                        ffmpeg.run(listOf("-ss", "%.3f".format(java.util.Locale.US, start), "-t", "%.3f".format(java.util.Locale.US, clipLen), "-i", input.path,
                            "-map", "0:v:0", "-map", "0:a:0?",
                            // 480p (short side) keeps the preview fast to process; the real export uses the full resolution.
                            "-vf", "scale='if(gt(iw,ih),-2,min(480,iw))':'if(gt(iw,ih),min(480,ih),-2)'",
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", "14", "-pix_fmt", "yuv420p",
                            "-c:a", "aac", "-b:a", "192k", clip.path))
                        source = clip
                        info = ffmpeg.probe(clip.path)
                    }
                    val output = File(work, "output.mp4")
                    var lastPct = -1
                    val ctx = JobContext(this@ProcessingService, source, info, opts, ffmpeg, RifeRunner(this@ProcessingService), work, output) { f, stage ->
                        JobRepository.state.value = JobState.Running(tool.title, f, stage)
                        val pct = (f * 100).toInt()
                        if (pct != lastPct) { lastPct = pct; notify(buildNotification(tool.title, f, stage, false)) }
                    }
                    tool.run(ctx)
                    if (!output.exists() || output.length() == 0L) throw ToolFailed("The tool produced no output")
                    if (preview) {
                        JobRepository.state.value = JobState.Running(tool.title, 0.98f, "Building side-by-side preview")
                        val outInfo = ffmpeg.probe(output.path)
                        val h = minOf(720, outInfo.height) / 2 * 2
                        val target = "%.3f".format(java.util.Locale.US, outInfo.fps)
                        val dest = File(cacheDir, "preview.mp4")
                        // Original is brought to the processed frame rate (duplicated frames) so both halves play in lockstep.
                        ffmpeg.run(listOf("-i", source.path, "-i", output.path, "-filter_complex",
                            "[0:v]fps=$target,scale=-2:$h,setsar=1[a];[1:v]scale=-2:$h,setsar=1[b];[a][b]hstack=inputs=2,scale=trunc(iw/2)*2:trunc(ih/2)*2[v]",
                            "-map", "[v]", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                            "-movflags", "+faststart", dest.path))
                        val pInfo = ffmpeg.probe(dest.path)
                        JobState.PreviewReady(dest.path, pInfo.width.toFloat() / pInfo.height, info.durationSec)
                    } else {
                        JobRepository.state.value = JobState.Running(tool.title, 0.99f, "Saving to gallery")
                        val base = name.substringBeforeLast('.', name)
                        val fileName = base + tool.outputSuffix(opts, info) + ".mp4"
                        JobState.Done(MediaSaver.saveVideo(this@ProcessingService, output, fileName), fileName)
                    }
                }
                JobRepository.state.value = result
                if (result is JobState.Done) notify(doneNotification("Saved to Movies/VideoTool", result.name, result.uri))
                else getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            } catch (e: CancellationException) {
                JobRepository.state.value = JobState.Idle
            } catch (e: Exception) {
                JobRepository.state.value = JobState.Failed(e.message ?: e.javaClass.simpleName)
                notify(doneNotification("Failed", e.message ?: "", null))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { work.deleteRecursively() }
                if (wake.isHeld) wake.release()
                ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video processing", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(title: String, fraction: Float, stage: String, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(stage)
            .setProgress(100, (fraction * 100).toInt(), indeterminate)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp())
            .addAction(0, "Cancel", cancel).build()
    }

    private fun doneNotification(title: String, text: String, uri: Uri?): Notification {
        val pi = if (uri != null) PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PendingIntent.FLAG_IMMUTABLE) else openApp()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi).build()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    companion object {
        const val ACTION_CANCEL = "dev.videotool.app.CANCEL"
        const val EXTRA_TOOL = "tool"
        const val EXTRA_NAME = "name"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_PREVIEW = "preview"
        const val PREVIEW_SECONDS = 3.0
        private const val CHANNEL = "processing"
        private const val NOTIF_ID = 1
    }
}
