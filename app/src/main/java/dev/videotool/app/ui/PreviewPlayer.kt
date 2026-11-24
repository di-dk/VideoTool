package dev.videotool.app.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Which part of the side-by-side clip is shown. */
object ViewMode { const val BOTH = 0; const val ORIGINAL = 1; const val PROCESSED = 2 }

/**
 * Looping, muted player for the side-by-side preview clip (left half = original, right half = processed).
 * The same video frame is drawn once per visible half with ONE shared zoom/pan, so both halves always show
 * exactly the same area. Zoom is [scale] with offset ([ox], [oy]) in normalised half-frame units:
 * content point c (0..1) appears at view point scale*c + offset, so offset is within [1 - scale, 0].
 */
@Composable
fun PreviewPlayer(
    path: String, speed: Float, paused: Boolean, mode: Int, stacked: Boolean, scale: Float, ox: Float, oy: Float,
    modifier: Modifier = Modifier,
) = key(path) {
    val player = remember { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { try { player.release() } catch (_: Exception) {} } }
    AndroidView(modifier = modifier,
        factory = { ctx ->
            PreviewGlView(ctx).also { v ->
                v.onSurface = { surface ->
                    try {
                        player.setSurface(surface)
                        if (!started) {
                            started = true
                            player.setDataSource(path)
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            player.setOnPreparedListener { it.start(); ready = true }
                            player.prepareAsync()
                        }
                    } catch (_: Exception) {}
                }
            }
        },
        update = { it.setParams(mode, stacked, scale, ox, oy) })
    LaunchedEffect(ready, speed, paused) {
        if (!ready) return@LaunchedEffect
        try {
            player.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.1f, 2f))
            if (paused) player.pause() else if (!player.isPlaying) player.start()
        } catch (_: Exception) {}
    }
}

class PreviewGlView(context: Context) : GLSurfaceView(context) {
    @Volatile private var mode = ViewMode.BOTH
    @Volatile private var stacked = false
    @Volatile private var zs = 1f
    @Volatile private var zx = 0f
    @Volatile private var zy = 0f
    /** Called (on the main thread) whenever a new video Surface is available, e.g. after the GL context is recreated. */
    var onSurface: ((Surface) -> Unit)? = null
    private val frameAvailable = AtomicBoolean(false)
    private var texture: SurfaceTexture? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(Renderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setParams(mode: Int, stacked: Boolean, scale: Float, ox: Float, oy: Float) {
        this.mode = mode; this.stacked = stacked; zs = scale; zx = ox; zy = oy
        requestRender()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var tex = 0
        private var uST = 0; private var uZoom = 0; private var uHalf = 0
        private val st = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private var w = 1; private var h = 1
        // x, y (clip space), vx, vy (view coords, 0..1, y up)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
        }

        private fun shader(type: Int, src: String): Int =
            GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vs = shader(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 aPos; attribute vec2 aV; varying vec2 vV;
                void main() { gl_Position = vec4(aPos, 0.0, 1.0); vV = aV; }""")
            val fs = shader(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                varying vec2 vV;
                uniform samplerExternalOES uTex;
                uniform mat4 uST;
                uniform vec3 uZoom;   // scale, offsetX, offsetY (offsets in top-left origin)
                uniform vec4 uHalf;   // u start, u width, v start, v height (GL texture coords, v up) of the half inside the frame
                void main() {
                    vec2 vt = vec2(vV.x, 1.0 - vV.y);
                    vec2 c = clamp((vt - uZoom.yz) / uZoom.x, 0.001, 0.999);
                    vec2 uv = vec2(uHalf.x + c.x * uHalf.y, uHalf.z + (1.0 - c.y) * uHalf.w);
                    gl_FragColor = texture2D(uTex, (uST * vec4(uv, 0.0, 1.0)).xy);
                }""")
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs)
                GLES20.glBindAttribLocation(it, 0, "aPos"); GLES20.glBindAttribLocation(it, 1, "aV")
                GLES20.glLinkProgram(it)
            }
            uST = GLES20.glGetUniformLocation(program, "uST")
            uZoom = GLES20.glGetUniformLocation(program, "uZoom")
            uHalf = GLES20.glGetUniformLocation(program, "uHalf")
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); tex = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texture?.release()
            val t = SurfaceTexture(tex).also { it.setOnFrameAvailableListener { frameAvailable.set(true); requestRender() } }
            texture = t
            val surface = Surface(t)
            post { onSurface?.invoke(surface) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height }

        private fun drawHalf(x: Int, y: Int, vw: Int, vh: Int, u0: Float, uw: Float, v0: Float, vhh: Float) {
            GLES20.glViewport(x, y, vw, vh)
            GLES20.glUniform3f(uZoom, zs, zx, zy)
            GLES20.glUniform4f(uHalf, u0, uw, v0, vhh)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val t = texture ?: return
            if (frameAvailable.getAndSet(false)) { t.updateTexImage(); t.getTransformMatrix(st) }
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glUniformMatrix4fv(uST, 1, false, st, 0)
            quad.position(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(0)
            quad.position(2); GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(1)
            // Halves of the clip in texture coords: side by side = original left / processed right;
            // stacked = original on top (v 0.5..1, v is up) / processed below (v 0..0.5).
            val stk = stacked
            val orig = if (stk) floatArrayOf(0f, 1f, 0.5f, 0.5f) else floatArrayOf(0f, 0.5f, 0f, 1f)
            val proc = if (stk) floatArrayOf(0f, 1f, 0f, 0.5f) else floatArrayOf(0.5f, 0.5f, 0f, 1f)
            when (mode) {
                ViewMode.ORIGINAL -> drawHalf(0, 0, w, h, orig[0], orig[1], orig[2], orig[3])
                ViewMode.PROCESSED -> drawHalf(0, 0, w, h, proc[0], proc[1], proc[2], proc[3])
                else -> if (stk) {
                    drawHalf(0, h / 2, w, h - h / 2, orig[0], orig[1], orig[2], orig[3])   // top
                    drawHalf(0, 0, w, h / 2, proc[0], proc[1], proc[2], proc[3])           // bottom
                } else {
                    drawHalf(0, 0, w / 2, h, orig[0], orig[1], orig[2], orig[3])           // left
                    drawHalf(w / 2, 0, w - w / 2, h, proc[0], proc[1], proc[2], proc[3])   // right
                }
            }
        }
    }
}
