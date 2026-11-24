package dev.videotool.app.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.Surface
import android.view.TextureView
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

/**
 * Looping, muted video player on a TextureView (a TextureView, unlike SurfaceView, follows the
 * zoom/pan transforms applied by Compose). [speed] is the playback-speed multiplier.
 */
@Composable
fun PreviewPlayer(path: String, speed: Float, paused: Boolean, modifier: Modifier = Modifier) = key(path) {
    val player = remember { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try { player.release() } catch (_: Exception) {}
            surface?.release()
        }
    }
    AndroidView(modifier = modifier, factory = { ctx ->
        TextureView(ctx).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val sf = Surface(st); surface = sf
                    try {
                        player.setSurface(sf)
                        player.setDataSource(path)
                        player.isLooping = true
                        player.setVolume(0f, 0f)
                        player.setOnPreparedListener { it.start(); ready = true }
                        player.prepareAsync()
                    } catch (_: Exception) {}
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    })
    LaunchedEffect(ready, speed, paused) {
        if (!ready) return@LaunchedEffect
        try {
            player.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.1f, 2f))
            if (paused) player.pause() else if (!player.isPlaying) player.start()
        } catch (_: Exception) {}
    }
}
