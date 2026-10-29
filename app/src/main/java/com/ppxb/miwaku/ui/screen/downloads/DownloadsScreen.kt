package com.ppxb.miwaku.ui.screen.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ppxb.miwaku.R
import com.ppxb.miwaku.core.data.AppGraph
import com.ppxb.miwaku.core.data.download.DownloadState
import com.ppxb.miwaku.core.data.download.DownloadTask
import com.ppxb.miwaku.model.ChapterSummary
import com.ppxb.miwaku.ui.component.EmptyState

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = AppGraph.downloadManager
    val tasks by manager.tasks.collectAsState()
    val waitingForWifi by manager.waitingForWifi.collectAsState()
    val hasRunning = tasks.any { it.state == DownloadState.Running }
    val hasQueued = tasks.any { it.state == DownloadState.Queued }
    val completedCount = tasks.count { it.state == DownloadState.Completed }
    var deleteTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var cancelTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = stringResource(R.string.downloads_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (completedCount > 0) {
                IconButton(onClick = { clearConfirm = true }) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.downloads_clear_completed),
                    )
                }
            }
            if (hasRunning || hasQueued) {
                TextButton(
                    onClick = { if (hasRunning) manager.pauseAll() else manager.resumeAll() },
                ) {
                    Text(
                        stringResource(
                            if (hasRunning) R.string.downloads_pause_all else R.string.downloads_resume_all,
                        ),
                    )
                }
            }
        }

        if (waitingForWifi) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.downloads_waiting_wifi),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Download,
                title = stringResource(R.string.downloads_empty_title),
                description = stringResource(R.string.downloads_empty_description),
                modifier = Modifier.padding(top = 96.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tasks, key = { it.chapterId }) { task ->
                DownloadRow(
                    task = task,
                    onDelete = { deleteTarget = task },
                    onCancel = { cancelTarget = task },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }

    cancelTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text(stringResource(R.string.downloads_cancel_title)) },
            text = { Text(stringResource(R.string.downloads_cancel_message, task.chapterName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.cancel(task.chapterId)
                        cancelTarget = null
                    },
                ) {
                    Text(stringResource(R.string.downloads_cancel_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) {
                    Text(stringResource(R.string.downloads_keep_downloading))
                }
            },
        )
    }

    deleteTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.downloads_delete)) },
            text = { Text(stringResource(R.string.downloads_delete_message, task.chapterName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.deleteFinished(task.chapterId)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.downloads_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.downloads_cancel))
                }
            },
        )
    }
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(stringResource(R.string.downloads_clear_completed)) },
            text = { Text(stringResource(R.string.downloads_clear_completed_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.clearCompleted()
                        clearConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.downloads_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(stringResource(R.string.downloads_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadRow(
    task: DownloadTask,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = task.albumTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.chapterName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stateLabel(task),
                style = MaterialTheme.typography.labelSmall,
                color =
                    when (task.state) {
                        DownloadState.Failed -> MaterialTheme.colorScheme.error
                        DownloadState.Completed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (task.state == DownloadState.Completed || task.state == DownloadState.Failed) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.downloads_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.downloads_cancel_task),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (task.state == DownloadState.Running && task.totalPages > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        if (task.state == DownloadState.Failed) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    AppGraph.downloadManager.enqueue(
                        albumId = task.albumId,
                        albumTitle = task.albumTitle,
                        chapters =
                            listOf(
                                ChapterSummary(
                                    id = task.chapterId,
                                    name = task.chapterName,
                                    sort = task.chapterSort,
                                ),
                            ),
                    )
                },
            ) {
                Text(stringResource(R.string.downloads_retry))
            }
        }
    }
}

@Composable
private fun stateLabel(task: DownloadTask): String =
    when (task.state) {
        DownloadState.Queued -> stringResource(R.string.downloads_state_queued)
        DownloadState.Running ->
            stringResource(R.string.downloads_state_running, task.donePages, task.totalPages)
        DownloadState.Completed -> stringResource(R.string.downloads_state_completed)
        DownloadState.Failed ->
            stringResource(R.string.downloads_state_failed, task.message.orEmpty())
    }
