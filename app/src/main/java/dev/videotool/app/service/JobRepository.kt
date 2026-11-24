package dev.videotool.app.service

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface JobState {
    data object Idle : JobState
    data class Running(val tool: String, val fraction: Float, val stage: String) : JobState
    data class Done(val uri: Uri, val name: String) : JobState
    /** A short side-by-side (original | processed) clip, ready to play. [aspect] is width/height of the combined video. */
    data class PreviewReady(val path: String, val aspect: Float, val seconds: Double) : JobState
    data class Failed(val message: String) : JobState
}

/** Process-wide job state observed by the UI; written by [ProcessingService]. */
object JobRepository {
    val state = MutableStateFlow<JobState>(JobState.Idle)
}
