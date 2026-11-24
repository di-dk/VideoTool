package dev.videotool.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

class ToolFailed(message: String) : Exception(message)

data class ProcResult(val exitCode: Int, val stderr: String)

/** Runs a native executable that is shipped as lib<name>.so inside nativeLibraryDir. */
class NativeExec(private val context: Context, private val libName: String) {
    private val dir get() = context.applicationInfo.nativeLibraryDir
    val file get() = File(dir, libName)

    suspend fun run(
        args: List<String>,
        onStdoutLine: ((String) -> Unit)? = null,
        workDir: File? = null,
    ): ProcResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(listOf(file.path) + args)
        pb.environment()["LD_LIBRARY_PATH"] = dir
        if (workDir != null) pb.directory(workDir)
        val proc = pb.start()
        proc.outputStream.close()
        val err = StringBuilder()
        val errThread = thread(isDaemon = true) {
            proc.errorStream.bufferedReader().forEachLine {
                synchronized(err) { err.appendLine(it); if (err.length > 20000) err.delete(0, 10000) }
            }
        }
        try {
            coroutineScope {
                // Cancelling the coroutine kills the process, which unblocks the reads below.
                val watcher = launch { try { awaitCancellation() } finally { proc.destroyForcibly() } }
                proc.inputStream.bufferedReader().forEachLine { onStdoutLine?.invoke(it) }
                val code = proc.waitFor()
                errThread.join(2000)
                watcher.cancel()
                ensureActive()
                ProcResult(code, synchronized(err) { err.toString() })
            }
        } finally {
            proc.destroyForcibly()
        }
    }
}

class FfmpegRunner(context: Context) {
    private val exec = NativeExec(context, "libffmpeg.so")

    /** Runs ffmpeg; [onProgress] receives the encoded output time in seconds. Throws ToolFailed on a non-zero exit. */
    suspend fun run(args: List<String>, onProgress: ((Double) -> Unit)? = null) {
        val full = listOf("-hide_banner", "-nostdin", "-y", "-nostats", "-progress", "pipe:1") + args
        val res = exec.run(full, onStdoutLine = { line ->
            if (onProgress != null && line.startsWith("out_time_us=")) {
                line.substringAfter('=').trim().toLongOrNull()?.let { if (it >= 0) onProgress(it / 1_000_000.0) }
            }
        })
        if (res.exitCode != 0) throw ToolFailed("ffmpeg failed (${res.exitCode}): " + res.stderr.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n"))
    }

    /** `ffmpeg -i file` always exits 1 but prints the stream info on stderr. */
    suspend fun probe(path: String): VideoInfo {
        val res = exec.run(listOf("-hide_banner", "-nostdin", "-i", path))
        return VideoInfo.parse(res.stderr) ?: throw ToolFailed("Could not read this video:\n" + res.stderr.lines().takeLast(3).joinToString("\n"))
    }
}

class RifeRunner(context: Context) {
    private val exec = NativeExec(context, "librife.so")
    suspend fun run(framesIn: File, framesOut: File, modelDir: File, outFrames: Int, gpu: Int = 0) {
        val res = exec.run(
            listOf("-i", framesIn.path, "-o", framesOut.path, "-m", modelDir.path, "-n", outFrames.toString(),
                "-g", gpu.toString(), "-j", "1:2:2", "-f", "%08d.jpg")
        )
        if (res.exitCode != 0) throw ToolFailed("RIFE failed (${res.exitCode}): " + res.stderr.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n"))
    }
}

data class VideoInfo(
    val durationSec: Double,
    val fps: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
) {
    companion object {
        private val duration = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
        private val size = Regex(""",\s*(\d{2,5})x(\d{2,5})[\s,\[]""")
        private val fpsRe = Regex("""([\d.]+)\s*fps""")
        private val tbrRe = Regex("""([\d.]+)\s*tbr""")
        private val rotRe = Regex("""rotation of (-?[\d.]+) degrees""")

        fun parse(text: String): VideoInfo? {
            val d = duration.find(text) ?: return null
            val dur = d.groupValues[1].toDouble() * 3600 + d.groupValues[2].toDouble() * 60 + d.groupValues[3].toDouble()
            val vLine = text.lines().firstOrNull { it.contains("Video:") && !it.contains("attached pic") } ?: return null
            val s = size.find(vLine) ?: return null
            var w = s.groupValues[1].toInt()
            var h = s.groupValues[2].toInt()
            val rot = rotRe.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.abs(it).toInt() % 180 } ?: 0
            if (rot == 90) { val t = w; w = h; h = t }
            val fps = fpsRe.find(vLine)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: tbrRe.find(vLine)?.groupValues?.get(1)?.toDoubleOrNull() ?: 30.0
            return VideoInfo(dur, fps, w, h, text.lines().any { it.contains("Audio:") })
        }
    }
}
