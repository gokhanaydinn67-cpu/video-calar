package com.video.calar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlaylistsTab(
    videos: List<Video>,
    playlists: Map<String, List<Long>>,
    openName: String?,
    onOpen: (String?) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRemove: (String, Long) -> Unit,
    onPlay: (List<Video>, Int) -> Unit
) {
    if (openName == null) {
        PlaylistList(
            playlists = playlists,
            onOpen = onOpen,
            onCreate = onCreate,
            onDelete = onDelete
        )
    } else {
        PlaylistDetail(
            name = openName,
            videos = videos,
            playlists = playlists,
            onBack = { onOpen(null) },
            onRemove = onRemove,
            onPlay = onPlay
        )
    }
}

@Composable
fun PlaylistList(
    playlists: Map<String, List<Long>>,
    onOpen: (String?) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Listeler",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp)
        )
        Text(
            "${playlists.size} liste",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp)
        )
        FilledTonalButton(
            onClick = onCreate,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Yeni liste")
        }
        Spacer(Modifier.height(12.dp))

        if (playlists.isEmpty()) {
            Text(
                "Henüz liste yok. Dizilerini eklemek için bir liste oluştur.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(playlists.keys.toList()) { name ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(name) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${playlists[name].orEmpty().size} video",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = { onDelete(name) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Listeyi sil")
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDetail(
    name: String,
    videos: List<Video>,
    playlists: Map<String, List<Long>>,
    onBack: () -> Unit,
    onRemove: (String, Long) -> Unit,
    onPlay: (List<Video>, Int) -> Unit
) {
    val list = playlists[name].orEmpty().mapNotNull { id ->
        videos.firstOrNull { it.id == id }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Geri")
            }
            Text(
                name,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (list.isEmpty()) {
            Text(
                "Bu listede video yok. Videolar sekmesinde + simgesiyle ekleyebilirsin.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(list, key = { _, v -> v.id }) { index, video ->
                VideoRow(
                    video = video,
                    active = false,
                    onClick = { onPlay(list, index) },
                    trailing = {
                        IconButton(onClick = { onRemove(name, video.id) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Listeden çıkar")
                        }
                    }
                )
            }
        }
    }
}
