package com.video.calar

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Video(
    val id: Long,
    val name: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val uri: Uri
)

enum class SortMode(val label: String) {
    NAME("Ada göre"),
    SIZE("Boyuta göre"),
    DATE("Tarihe göre")
}

fun Video.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
        .build()

fun loadVideos(context: Context): List<Video> {
    val result = mutableListOf<Video>()
    val collection = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED
    )
    context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val rawName = c.getString(nameCol) ?: "Bilinmeyen"
            result.add(
                Video(
                    id = id,
                    name = rawName.substringBeforeLast('.'),
                    size = c.getLong(sizeCol),
                    duration = c.getLong(durCol),
                    dateAdded = c.getLong(dateCol),
                    uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                )
            )
        }
    }
    return result
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    else -> "%.0f MB".format(bytes / 1_048_576.0)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { MuzikTheme { PlayerApp() } }
    }
}

@Composable
fun MuzikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB388FF),
            background = Color(0xFF0E0E12),
            surface = Color(0xFF1A1A22),
            onBackground = Color(0xFFF2F2F7),
            onSurface = Color(0xFFF2F2F7)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoPerm = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

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
        val wanted = if (Build.VERSION.SDK_INT >= 33)
            listOf(videoPerm, Manifest.permission.POST_NOTIFICATIONS)
        else listOf(videoPerm)
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
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

    var tab by remember { mutableStateOf(0) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var currentTitle by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
            } catch (e: Exception) {
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
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    currentId = item?.mediaId
                    currentTitle = item?.mediaMetadata?.title?.toString() ?: ""
                }
            }
            c.addListener(listener)
            currentId = c.currentMediaItem?.mediaId
            currentTitle = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
            onDispose { c.removeListener(listener) }
        }
    }

    fun playList(list: List<Video>, index: Int) {
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
                                .padding(horizontal = 20.dp),
                        ) {
                            SortMode.values().forEach { mode ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    label = { Text(mode.label) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = {
                                scope.launch {
                                    refreshing = true
                                    videos = withContext(Dispatchers.IO) { loadVideos(context) }
                                    refreshing = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                itemsIndexed(sorted, key = { _, v -> v.id }) { index, video ->
                                    VideoRow(
                                        video = video,
                                        active = video.id.toString() == currentId,
                                        onClick = {
                                            playList(sorted, index)
                                            showPlayer = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Çalma listeleri bir sonraki adımda eklenecek",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
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
                    onClose = { showPlayer = false }
                )
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(controller: MediaController, title: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = true } },
            update = { view -> view.player = controller },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Geri", tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun VideoRow(video: Video, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoThumb(video, 128.dp, 72.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground
            )
            Text(
                "${formatTime(video.duration)} · ${formatSize(video.size)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun VideoThumb(video: Video, width: Dp, height: Dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, video.id) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    context.contentResolver.loadThumbnail(video.uri, Size(480, 270), null)
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }
    val bmp = bitmap
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF4A3478), Color(0xFF1C1C28)))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
