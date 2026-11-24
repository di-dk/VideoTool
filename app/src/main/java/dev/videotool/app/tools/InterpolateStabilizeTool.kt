package dev.videotool.app.tools

import android.content.Context
import dev.videotool.app.engine.ToolFailed
import dev.videotool.app.engine.VideoInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Frame interpolation (RIFE on the GPU, or FFmpeg minterpolate on the CPU) with optional stabilization (vid.stab). */
class InterpolateStabilizeTool : VideoTool {
    override val id = "interpolate"
    override val title = "Smooth & Stabilize"
    override val description = "Raise the frame rate (e.g. 30 → 60 fps) with AI interpolation and steady shaky footage."
    override val emoji = "🎞️"

    override fun options(env: Env) = listOf(
        ToolOption.Choice(
            "fps", "Target frame rate",
            listOf(
                ToolOption.Choice.Item("x2", "2×"),
                ToolOption.Choice.Item("60", "60 fps"),
                ToolOption.Choice.Item("120", "120 fps"),
            ), default = "x2",
            help = "Must be higher than the video's current frame rate.",
        ),
        ToolOption.Choice(
            "engine", "Interpolation engine",
            listOf(
                ToolOption.Choice.Item("rife", "RIFE (AI, GPU)", enabled = env.vulkan, note = if (env.vulkan) null else "needs Vulkan GPU"),
                ToolOption.Choice.Item("ffmpeg", "FFmpeg (CPU)"),
            ), default = if (env.vulkan) "rife" else "ffmpeg",
            help = "RIFE looks best but is limited to 1080p. FFmpeg works on any phone but is slower and can warp fast motion.",
        ),
        ToolOption.Choice(
            "ffquality", "CPU engine quality",
            listOf(ToolOption.Choice.Item("fast", "Fast"), ToolOption.Choice.Item("high", "High (slow)")),
            default = "fast", help = "Only used by the FFmpeg engine.",
        ),
        ToolOption.Choice(
            "stabilize", "Stabilization",
            listOf(
                ToolOption.Choice.Item("off", "Off"),
                ToolOption.Choice.Item("light", "Light"),
                ToolOption.Choice.Item("strong", "Strong"),
            ), default = "off",
            help = "Two-pass vid.stab. Strong crops more of the frame. Runs before interpolation.",
        ),
    )

    private fun targetFps(options: Map<String, String>, info: VideoInfo): Double =
        when (val v = options["fps"] ?: "x2") { "x2" -> info.fps * 2; else -> v.toDouble() }

    override fun outputSuffix(options: Map<String, String>, info: VideoInfo): String {
        val t = targetFps(options, info)
        val fpsLabel = if (t % 1.0 < 0.01) "${t.toInt()}fps" else String.format(Locale.US, "%.1ffps", t)
        return "_$fpsLabel" + if ((options["stabilize"] ?: "off") != "off") "_stab" else ""
    }

    private fun fmt(d: Double) = String.format(Locale.US, "%.3f", d)

    private val encode = listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "17", "-pix_fmt", "yuv420p")
    private val audio = listOf("-c:a", "aac", "-b:a", "192k")
    private val evenSize = "scale=trunc(iw/2)*2:trunc(ih/2)*2"

    private fun stabFilter(level: String, trf: File): String {
        val smoothing = if (level == "strong") 30 else 10
        return "vidstabtransform=input=${trf.path}:smoothing=$smoothing:zoom=0:optzoom=1:interpol=bicubic,unsharp=5:5:0.6:5:5:0.0"
    }

    private fun stabDetect(level: String, trf: File): String {
        val shak = if (level == "strong") 8 else 5
        return "vidstabdetect=shakiness=$shak:accuracy=15:result=${trf.path}"
    }

    override suspend fun run(job: JobContext) {
        val info = job.info
        val target = targetFps(job.options, info)
        if (target <= info.fps + 0.5) {
            throw ToolFailed("This video is already ${fmt(info.fps)} fps. Choose a target above that.")
        }
        val stab = job.opt("stabilize")
        val engine = job.opt("engine")
        val dur = max(info.durationSec, 0.1)
        val trf = File(job.workDir, "motion.trf")
        val out = job.output
        out.parentFile?.mkdirs()

        if (engine == "ffmpeg") {
            val minterp = if (job.opt("ffquality") == "high")
                "minterpolate=fps=${fmt(target)}:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1"
            else "minterpolate=fps=${fmt(target)}:mi_mode=mci:mc_mode=obmc:me_mode=bilat:me=epzs:vsbmc=0"
            var base = 0f
            if (stab != "off") {
                job.progress(0f, "Analysing camera shake")
                job.ffmpeg.run(listOf("-i", job.input.path, "-an", "-vf", stabDetect(stab, trf), "-f", "null", "-")) {
                    job.progress((it / dur).toFloat() * 0.2f, "Analysing camera shake")
                }
                base = 0.2f
            }
            val vf = listOfNotNull(if (stab != "off") stabFilter(stab, trf) else null, minterp, evenSize).joinToString(",")
            job.ffmpeg.run(
                listOf("-i", job.input.path, "-map", "0:v:0", "-map", "0:a:0?", "-vf", vf) + encode + audio +
                    listOf("-movflags", "+faststart", out.path)
            ) { job.progress(base + (it / dur).toFloat() * (1f - base), if (stab != "off") "Stabilizing and interpolating" else "Interpolating") }
            return
        }

        // ---- RIFE path
        var source = job.input
        var span0 = 0f
        if (stab != "off") {
            job.progress(0f, "Analysing camera shake")
            job.ffmpeg.run(listOf("-i", job.input.path, "-an", "-vf", stabDetect(stab, trf), "-f", "null", "-")) {
                job.progress((it / dur).toFloat() * 0.08f, "Analysing camera shake")
            }
            val stabFile = File(job.workDir, "stabilized.mp4")
            job.ffmpeg.run(
                listOf("-i", job.input.path, "-map", "0:v:0", "-map", "0:a:0?", "-vf", stabFilter(stab, trf) + "," + evenSize) +
                    encode + audio + listOf(stabFile.path)
            ) { job.progress(0.08f + (it / dur).toFloat() * 0.12f, "Stabilizing") }
            source = stabFile
            span0 = 0.2f
        }
        val base = job.ffmpeg.probe(source.path)
        val ratio = target / base.fps
        val modelDir = ModelStore.rifeModel(job.app)
        val segLen = (200.0 / base.fps).coerceIn(1.5, 6.0)
        val nSeg = max(1, Math.ceil(base.durationSec / segLen).toInt())
        val interpEnd = 0.97f
        val segments = mutableListOf<File>()
        val vf = "fps=${fmt(base.fps)},scale=-2:'min(1080,ih)',$evenSize"
        for (i in 0 until nSeg) {
            val segBase = span0 + (interpEnd - span0) * i / nSeg
            val segSpan = (interpEnd - span0) / nSeg
            val stage = "Interpolating part ${i + 1} of $nSeg"
            job.progress(segBase, stage)
            val inDir = File(job.workDir, "in").also { it.deleteRecursively(); it.mkdirs() }
            val outDir = File(job.workDir, "out").also { it.deleteRecursively(); it.mkdirs() }
            val start = i * segLen
            job.ffmpeg.run(
                listOf("-ss", fmt(start), "-t", fmt(segLen), "-i", source.path, "-an", "-vf", vf, "-q:v", "2", File(inDir, "%08d.jpg").path)
            )
            val count = inDir.list()?.size ?: 0
            if (count == 0) { continue }
            val wanted = if (count < 2) count else max(count + 1, (count * ratio).roundToInt())
            if (count >= 2) {
                coroutineScope {
                    val poller = launch {
                        while (true) {
                            delay(600)
                            val done = outDir.list()?.size ?: 0
                            job.progress(segBase + segSpan * (0.15f + 0.7f * min(1f, done / wanted.toFloat())), stage)
                        }
                    }
                    try { job.rife.run(inDir, outDir, modelDir, wanted) } finally { poller.cancel() }
                }
            } else {
                inDir.listFiles()?.forEach { it.copyTo(File(outDir, it.name)) }
            }
            val seg = File(job.workDir, "seg_%03d.mp4".format(i))
            job.ffmpeg.run(listOf("-framerate", fmt(target), "-i", File(outDir, "%08d.jpg").path) + encode + listOf(seg.path))
            segments += seg
            inDir.deleteRecursively(); outDir.deleteRecursively()
        }
        if (segments.isEmpty()) throw ToolFailed("No frames could be read from this video.")
        job.progress(interpEnd, "Finishing")
        val list = File(job.workDir, "segments.txt").apply { writeText(segments.joinToString("\n") { "file '${it.path}'" }) }
        job.ffmpeg.run(
            listOf("-f", "concat", "-safe", "0", "-i", list.path, "-i", source.path, "-map", "0:v:0", "-map", "1:a:0?",
                "-c:v", "copy") + audio + listOf("-movflags", "+faststart", out.path)
        )
    }
}

object ModelStore {
    /** Copies the bundled RIFE model out of the APK assets once and returns its directory. */
    fun rifeModel(context: Context): File {
        val dir = File(context.filesDir, "models/rife-v4.6")
        val marker = File(dir, ".complete")
        if (!marker.exists()) {
            dir.mkdirs()
            for (name in context.assets.list("models/rife-v4.6").orEmpty()) {
                context.assets.open("models/rife-v4.6/$name").use { src -> File(dir, name).outputStream().use { src.copyTo(it) } }
            }
            marker.writeText("ok")
        }
        return dir
    }
}
