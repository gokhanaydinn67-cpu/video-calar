package com.video.calar

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Android 11 ve üzeri için silme onayı isteyen intent. Eski sürümlerde null döner.
fun deleteIntentSender(context: Context, uri: Uri): IntentSender? =
    if (Build.VERSION.SDK_INT >= 30) {
        MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
    } else {
        null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerApp() {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val videoPerm = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, videoPerm) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = ContextCompat.checkSelfPermission(context, videoPerm) ==
            PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) {
        val wanted = if (Build.VERSION.SDK_INT >= 33) {
            listOf(videoPerm, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(videoPerm)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }

    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            videos = withContext(Dispatchers.IO) { loadVideos(context) }
        }
    }

    var sortMode by remember { mutableStateOf(SortMode.DATE) }
    val sorted = remember(videos, sortMode) {
        when (sortMode) {
            SortMode.NAME -> videos.sortedBy { it.name.lowercase() }
            SortMode.SIZE -> videos.sortedByDescending { it.size }
            SortMode.DATE -> videos.sortedByDescending { it.dateAdded }
        }
    }

    var playlists by remember { mutableStateOf(PlaylistStore.load(context)) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addTarget by remember { mutableStateOf<Video?>(null) }
    var menuFor by remember { mutableStateOf<Video?>(null) }
    var renameTarget by remember { mutableStateOf<Video?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Video?>(null) }

    fun savePlaylists(newMap: Map<String, List<Long>>) {
        playlists = newMap
        PlaylistStore.save(context, newMap)
    }

    var tab by remember { mutableStateOf(0) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var currentTitle by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        scope.launch { videos = reloadVideos(context) }
    }

    fun performDelete(v: Video) {
        val sender = deleteIntentSender(context, v.uri)
        if (sender != null) {
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.delete(v.uri, null, null)
                    } catch (e: Exception) {
                    }
                }
                videos = reloadVideos(context)
            }
        }
    }

    // Oynatıcıdan çıkınca oynatmayı durdurur ve listeyi temizler.
    // Böylece ana ekrandaki bar ve kilit ekranındaki kart kapanır.
    fun closePlayer() {
        showPlayer = false
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    BackHandler(enabled = showPlayer) {
        if (fullscreen) {
            fullscreen = false
        } else {
            closePlayer()
        }
    }

    LaunchedEffect(showPlayer, isPlaying) {
        activity?.pipAllowed = showPlayer && isPlaying
        if (!showPlayer) {
            fullscreen = false
        }
    }

    DisposableEffect(fullscreen) {
        val w = activity?.window
        if (activity != null && w != null) {
            val ctrl = WindowCompat.getInsetsController(w, w.decorView)
            if (fullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
            } catch (e: Exception) {
                controller = null
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }

    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    currentId = item?.mediaId
                    currentTitle = item?.mediaMetadata?.title?.toString() ?: ""
                }
            }
            c.addListener(listener)
            isPlaying = c.isPlaying
            currentId = c.currentMediaItem?.mediaId
            currentTitle = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
            onDispose { c.removeListener(listener) }
        }
    }

    fun playQueue(list: List<Video>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    val c = controller

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
                        label = { Text("Videolar") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Rounded.PlaylistPlay, contentDescription = null) },
                        label = { Text("Listeler") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!hasPermission) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Videolarına erişmek için izin gerekli")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { launcher.launch(arrayOf(videoPerm)) }) {
                            Text("İzin ver")
                        }
                    }
                } else if (tab == 0) {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            "Videolar",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp)
                        )
                        Text(
                            "${videos.size} video",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp)
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                        ) {
                            for (mode in SortMode.values()) {
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    label = { Text(mode.label) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PullToRefreshBox(
                                isRefreshing = refreshing,
                                onRefresh = {
                                    scope.launch {
                                        refreshing = true
                                        videos = reloadVideos(context)
                                        refreshing = false
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(end = 24.dp, bottom = 8.dp)
                                ) {
                                    itemsIndexed(sorted, key = { _, v -> v.id }) { index, video ->
                                        VideoRow(
                                            video = video,
                                            active = video.id.toString() == currentId,
                                            onClick = {
                                                playQueue(sorted, index)
                                                showPlayer = true
                                            },
                                            trailing = {
                                                Box {
                                                    IconButton(onClick = { menuFor = video }) {
                                                        Icon(
                                                            Icons.Rounded.MoreVert,
                                                            contentDescription = "Seçenekler"
                                                        )
                                                    }
                                                    DropdownMenu(
                                                        expanded = menuFor == video,
                                                        onDismissRequest = { menuFor = null }
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = { Text("Listeye ekle") },
                                                            leadingIcon = {
                                                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null)
                                                            },
                                                            onClick = {
                                                                menuFor = null
                                                                addTarget = video
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("Yeniden adlandır") },
                                                            leadingIcon = {
                                                                Icon(Icons.Rounded.Edit, contentDescription = null)
                                                            },
                                                            onClick = {
                                                                menuFor = null
                                                                renameText = video.name
                                                                renameTarget = video
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("Paylaş") },
                                                            leadingIcon = {
                                                                Icon(Icons.Rounded.Share, contentDescription = null)
                                                            },
                                                            onClick = {
                                                                menuFor = null
                                                                shareVideo(context, video.uri)
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("Sil") },
                                                            leadingIcon = {
                                                                Icon(Icons.Rounded.Delete, contentDescription = null)
                                                            },
                                                            onClick = {
                                                                menuFor = null
                                                                deleteTarget = video
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            FastScroller(listState)
                        }
                    }
                } else {
                    PlaylistsTab(
                        videos = videos,
                        playlists = playlists,
                        openName = openPlaylist,
                        onOpen = { openPlaylist = it },
                        onCreate = { creating = true },
                        onDelete = { name ->
                            savePlaylists(playlists - name)
                            if (openPlaylist == name) {
                                openPlaylist = null
                            }
                        },
                        onRemove = { name, id ->
                            val current = playlists[name].orEmpty()
                            savePlaylists(playlists + (name to current.filter { it != id }))
                        },
                        onPlay = { list, index ->
                            playQueue(list, index)
                            showPlayer = true
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && c != null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            if (c != null) {
                VideoPlayerScreen(
                    controller = c,
                    title = currentTitle,
                    inPip = activity?.inPip?.value ?: false,
                    fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onPip = { activity?.enterPip() },
                    onClose = { closePlayer() }
                )
            }
        }

        if (creating) {
            AlertDialog(
                onDismissRequest = { creating = false },
                title = { Text("Yeni liste") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Liste adı") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty() && !playlists.containsKey(name)) {
                            savePlaylists(playlists + (name to emptyList<Long>()))
                        }
                        newName = ""
                        creating = false
                    }) {
                        Text("Oluştur")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { creating = false }) {
                        Text("İptal")
                    }
                }
            )
        }

        addTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { addTarget = null },
                title = { Text("Listeye ekle") },
                text = {
                    Column {
                        if (playlists.isEmpty()) {
                            Text("Önce Listeler sekmesinden bir liste oluştur.")
                        }
                        for (name in playlists.keys) {
                            TextButton(onClick = {
                                val ids = playlists[name].orEmpty()
                                if (!ids.contains(target.id)) {
                                    savePlaylists(playlists + (name to (ids + target.id)))
                                }
                                addTarget = null
                            }) {
                                Text(name)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { addTarget = null }) {
                        Text("Kapat")
                    }
                }
            )
        }

        renameTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("Yeniden adlandır") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Yeni ad") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val newBase = renameText.trim()
                        val uri = target.uri
                        renameTarget = null
                        if (newBase.isNotEmpty()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        renameVideo(context, uri, newBase)
                                    } catch (e: Exception) {
                                    }
                                }
                                videos = reloadVideos(context)
                            }
                        }
                    }) {
                        Text("Kaydet")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text("İptal")
                    }
                }
            )
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Videoyu sil") },
                text = { Text("\"${target.name}\" kalıcı olarak silinsin mi?") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        performDelete(target)
                    }) {
                        Text("Sil")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("İptal")
                    }
                }
            )
        }
    }
}
