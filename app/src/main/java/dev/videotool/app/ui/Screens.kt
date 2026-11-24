package dev.videotool.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import dev.videotool.app.R
import dev.videotool.app.service.JobState
import dev.videotool.app.tools.Env
import dev.videotool.app.tools.ToolOption
import dev.videotool.app.tools.VideoTool

@Composable
fun HomeScreen(tools: List<VideoTool>, onOpen: (VideoTool) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                painterResource(R.drawable.banner), null,
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop,
            )
            Text("VideoTool", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Video editing tools that run on your phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            tools.forEach { t ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpen(t) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(t.emoji, fontSize = 34.sp)
                        Column {
                            Text(t.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(t.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
            }
            Text("More tools are on the way.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolScreen(
    tool: VideoTool, env: Env, state: JobState, pickedName: String?,
    onBack: () -> Unit, onPick: () -> Unit, onStart: (Map<String, String>) -> Unit, onPreview: (Map<String, String>) -> Unit,
    onClosePreview: () -> Unit, onCancel: () -> Unit,
    onOpen: (Uri) -> Unit, onShare: (Uri) -> Unit,
) {
    val options = remember(tool, env) { tool.options(env) }
    val values = remember(tool) { mutableStateMapOf<String, String>().apply { options.forEach { if (it is ToolOption.Choice) put(it.key, it.default) } } }
    val running = state is JobState.Running
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = !running) { Text("← Back") }
                Spacer(Modifier.size(4.dp))
                Text(tool.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            Card(Modifier.fillMaxWidth().clickable(enabled = !running, onClick = onPick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (pickedName == null) {
                        Image(painterResource(R.drawable.empty_state), null, Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.height(8.dp))
                        Text("Tap to choose a video", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("🎬 $pickedName", fontWeight = FontWeight.SemiBold)
                        Text("Tap to change", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }

            options.filterIsInstance<ToolOption.Choice>().forEach { opt ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(opt.label, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        opt.items.forEach { item ->
                            FilterChip(
                                selected = values[opt.key] == item.value,
                                onClick = { values[opt.key] = item.value },
                                enabled = item.enabled && !running,
                                label = { Text(item.label + (item.note?.let { " ($it)" } ?: "")) },
                            )
                        }
                    }
                    opt.help?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            when (state) {
                is JobState.Running -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.stage, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { state.fraction }, Modifier.fillMaxWidth())
                        Text("${(state.fraction * 100).toInt()}% — keeps running with the screen off", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is JobState.Done -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✅ Saved to Movies/VideoTool", fontWeight = FontWeight.SemiBold)
                        Text(state.name, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpen(state.uri) }) { Text("Play") }
                            OutlinedButton(onClick = { onShare(state.uri) }) { Text("Share") }
                        }
                    }
                }
                is JobState.Failed -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Something went wrong", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        Text(state.message, fontSize = 12.sp)
                    }
                }
                is JobState.PreviewReady -> PreviewCard(state, onExport = { onStart(values.toMap()) }, onClose = onClosePreview)
                JobState.Idle -> {}
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onPreview(values.toMap()) }, enabled = pickedName != null && !running, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Preview 3 s", fontSize = 16.sp)
                }
                Button(onClick = { onStart(values.toMap()) }, enabled = pickedName != null && !running, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(if (running) "Working…" else "Export", fontSize = 16.sp)
                }
            }
            Box(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreviewCard(state: JobState.PreviewReady, onExport: () -> Unit, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview: original | processed", fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().aspectRatio(state.aspect).clip(RoundedCornerShape(12.dp))) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f) }
                            setVideoPath(state.path)
                            start()
                        }
                    },
                    update = { v -> if (v.tag != state.path) { v.tag = state.path; v.setVideoPath(state.path); v.start() } },
                    modifier = Modifier.fillMaxSize(),
                )
                Text("ORIGINAL", Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 11.sp, color = Color.White)
                Text("PROCESSED", Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 11.sp, color = Color(0xFF3DDCFF))
            }
            Text("Short clip from the middle of your video, using the settings above. Change a setting and preview again, or export the whole video.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExport) { Text("Export full video") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
