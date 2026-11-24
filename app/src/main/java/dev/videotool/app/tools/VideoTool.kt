package dev.videotool.app.tools

import android.content.Context
import dev.videotool.app.engine.FfmpegRunner
import dev.videotool.app.engine.RifeRunner
import dev.videotool.app.engine.VideoInfo
import java.io.File

/** Device capabilities that tools may depend on. */
data class Env(val vulkan: Boolean)

sealed class ToolOption(val key: String, val label: String, val help: String? = null) {
    class Choice(key: String, label: String, val items: List<Item>, val default: String, help: String? = null) :
        ToolOption(key, label, help) {
        data class Item(val value: String, val label: String, val enabled: Boolean = true, val note: String? = null)
    }
}

/** Everything a tool needs to process one video. Tools write their result to [output]. */
class JobContext(
    val app: Context,
    val input: File,
    val info: VideoInfo,
    val options: Map<String, String>,
    val ffmpeg: FfmpegRunner,
    val rife: RifeRunner,
    val workDir: File,
    val output: File,
    private val report: (fraction: Float, stage: String) -> Unit,
) {
    fun progress(fraction: Float, stage: String) = report(fraction.coerceIn(0f, 1f), stage)
    fun opt(key: String) = options[key] ?: error("missing option $key")
}

/**
 * A video tool. To add a feature: implement this interface and add it to [ToolRegistry.all];
 * the home screen, option form, background service, notification and save-to-gallery are generic.
 */
interface VideoTool {
    val id: String
    val title: String
    val description: String
    val emoji: String
    fun options(env: Env): List<ToolOption>
    /** Suffix appended to the original file name for the saved result, e.g. "_60fps". */
    fun outputSuffix(options: Map<String, String>, info: VideoInfo): String
    suspend fun run(job: JobContext)
}

object ToolRegistry {
    val all: List<VideoTool> by lazy { listOf(InterpolateStabilizeTool()) }
    fun byId(id: String) = all.firstOrNull { it.id == id }
}
