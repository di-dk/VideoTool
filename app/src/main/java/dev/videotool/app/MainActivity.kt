package dev.videotool.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.videotool.app.service.JobRepository
import dev.videotool.app.service.JobState
import dev.videotool.app.service.ProcessingService
import dev.videotool.app.tools.Env
import dev.videotool.app.tools.ToolRegistry
import dev.videotool.app.ui.AppTheme
import dev.videotool.app.ui.HomeScreen
import dev.videotool.app.ui.ToolScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val env = Env(vulkan = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION))
        setContent {
            AppTheme {
                var toolId by rememberSaveable { mutableStateOf<String?>(null) }
                var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
                var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
                val state by JobRepository.state.collectAsStateWithLifecycle()

                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) {
                        pickedUri = uri.toString()
                        pickedName = displayName(uri)
                        if (state !is JobState.Running) JobRepository.state.value = JobState.Idle
                    }
                }

                val tool = toolId?.let { ToolRegistry.byId(it) }
                BackHandler(enabled = tool != null) { toolId = null }
                if (tool == null) {
                    HomeScreen(ToolRegistry.all) { toolId = it.id }
                } else {
                    ToolScreen(
                        tool = tool, env = env, state = state, pickedName = pickedName,
                        onBack = { toolId = null },
                        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        onStart = { options -> start(tool.id, Uri.parse(pickedUri), pickedName ?: "video", options, false) },
                        onPreview = { options -> start(tool.id, Uri.parse(pickedUri), pickedName ?: "video", options, true) },
                        onClosePreview = { JobRepository.state.value = JobState.Idle },
                        onCancel = { startService(Intent(this, ProcessingService::class.java).setAction(ProcessingService.ACTION_CANCEL)) },
                        onOpen = { uri -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) },
                        onShare = { uri -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) },
                    )
                }
            }
        }
    }

    private fun start(toolId: String, uri: Uri, name: String, options: Map<String, String>, preview: Boolean) {
        JobRepository.state.value = JobState.Running("", 0f, "Starting")
        val intent = Intent(this, ProcessingService::class.java)
            .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(ProcessingService.EXTRA_TOOL, toolId)
            .putExtra(ProcessingService.EXTRA_NAME, name)
            .putExtra(ProcessingService.EXTRA_PREVIEW, preview)
            .putExtra(ProcessingService.EXTRA_OPTIONS, Bundle().apply { options.forEach { (k, v) -> putString(k, v) } })
        ContextCompat.startForegroundService(this, intent)
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video.mp4"
}
