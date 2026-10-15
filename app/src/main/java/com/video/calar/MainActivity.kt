package com.video.calar

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlin.math.abs
import kotlin.math.roundToInt

const val MODE_NONE = 0
const val MODE_SEEK = 1
const val MODE_BRIGHTNESS = 2
const val MODE_VOLUME = 3

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

object PlaylistStore {
    private const val PREFS = "playlists"
    private const val KEY_NAMES = "names"

    fun load(context: Context): Map<String, List<Long>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        val result = LinkedHashMap<String, List<Long>>()
        for (name in names.sorted()) {
            val raw = prefs.getString("p_$name", "") ?: ""
            val ids = raw.split(",").filter { it.isNotBlank() }.map { it.toLong() }
            result[name] = ids
        }
        return result
    }

    fun save(context: Context, data: Map<String, List<Long>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val old = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        for (name in old) {
            editor.remove("p_$name")
        }
        editor.putStringSet(KEY_NAMES, data.keys.toSet())
        for ((name, ids) in data) {
            editor.putString("p_$name", ids.joinToString(","))
        }
        editor.apply()
    }
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

fun formatSize(bytes: Long): String {
    return if (bytes >= 1_073_741_824L) {
        "%.1f GB".format(bytes / 1_073_741_824.0)
    } else {
        "%.0f MB".format(bytes / 1_048_576.0)
    }
}

class MainActivity : ComponentActivity() {
    val inPip = mutableStateOf(false)
    var pipAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MuzikTheme {
                PlayerApp()
            }
        }
    }

    fun enterPip() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipAllowed) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
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
    val activity = context as? MainActivity
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

    BackHandler(enabled = showPlayer) {
        if (fullscreen) {
            fullscreen = false
        } else {
            showPlayer = false
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
                Column {
                    if (c != null && currentTitle.isNotEmpty() && !showPlayer) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showPlayer = true }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    currentTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    if (isPlaying) c.pause() else c.play()
                                }) {
                                    Icon(
                                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "Oynat / Duraklat"
                                    )
                                }
                            }
                        }
                    }
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
                                            playQueue(sorted, index)
                                            showPlayer = true
                                        },
                                        trailing = {
                                            IconButton(onClick = { addTarget = video }) {
                                                Icon(
                                                    Icons.Rounded.PlaylistAdd,
                                                    contentDescription = "Listeye ekle"
                                                )
                                            }
                                        }
                                    )
                                }
                            }
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
                    onClose = { showPlayer = false }
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

        val target = addTarget
        if (target != null) {
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
    }
}
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
    } else {
        val list = playlists[openName].orEmpty().mapNotNull { id ->
            videos.firstOrNull { it.id == id }
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onOpen(null) }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Geri")
                }
                Text(
                    openName,
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
                            IconButton(onClick = { onRemove(openName, video.id) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Listeden çıkar")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(
    controller: MediaController,
    title: String,
    inPip: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPip: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var volume by remember {
        mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol)
    }
    var brightness by remember {
        val start = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (start >= 0f) start else 0.5f)
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var showControls by remember { mutableStateOf(true) }
    var sliderDrag by remember { mutableStateOf<Float?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(MODE_NONE) }
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }
    var startX by remember { mutableFloatStateOf(0f) }
    var areaWidth by remember { mutableIntStateOf(0) }

    val max = duration.coerceAtLeast(1L).toFloat()

    LaunchedEffect(controller) {
        while (true) {
            position = controller.currentPosition.coerceAtLeast(0L)
            duration = controller.duration.coerceAtLeast(0L)
            isPlaying = controller.isPlaying
            delay(250)
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000)
            showControls = false
        }
    }

    fun applyBrightness(v: Float) {
        brightness = v
        val w = activity?.window ?: return
        val lp = w.attributes
        lp.screenBrightness = v
        w.attributes = lp
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { useController = false }
            },
            update = { view ->
                view.player = controller
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { areaWidth = it.width }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            accX = 0f
                            accY = 0f
                            mode = MODE_NONE
                        },
                        onDragEnd = {
                            if (mode == MODE_SEEK) {
                                val target = (controller.currentPosition + (accX * 200f).toLong())
                                    .coerceIn(0L, controller.duration.coerceAtLeast(0L))
                                controller.seekTo(target)
                            }
                            mode = MODE_NONE
                            gestureText = ""
                        },
                        onDragCancel = {
                            mode = MODE_NONE
                            gestureText = ""
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            accX += drag.x
                            accY += drag.y
                            if (mode == MODE_NONE && (abs(accX) > 30f || abs(accY) > 30f)) {
                                mode = if (abs(accX) > abs(accY)) {
                                    MODE_SEEK
                                } else if (startX < areaWidth / 2f) {
                                    MODE_BRIGHTNESS
                                } else {
                                    MODE_VOLUME
                                }
                            }
                            if (mode == MODE_SEEK) {
                                val target = (controller.currentPosition + (accX * 200f).toLong())
                                    .coerceIn(0L, controller.duration.coerceAtLeast(0L))
                                gestureText = "Sar: ${formatTime(target)}"
                            } else if (mode == MODE_VOLUME) {
                                volume = (volume - drag.y / 400f).coerceIn(0f, 1f)
                                audio.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (volume * maxVol).roundToInt(),
                                    0
                                )
                                gestureText = "Ses %" + (volume * 100).roundToInt()
                            } else if (mode == MODE_BRIGHTNESS) {
                                applyBrightness((brightness - drag.y / 400f).coerceIn(0.01f, 1f))
                                gestureText = "Parlaklık %" + (brightness * 100).roundToInt()
                            }
                        }
                    )
                }
        )

        if (!inPip && showControls) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x99000000),
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xCC000000)
                            )
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Geri",
                            tint = Color.White
                        )
                    }
                    Text(
                        title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(
                            if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            contentDescription = "Tam ekran",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onPip) {
                        Icon(
                            Icons.Rounded.PictureInPicture,
                            contentDescription = "Küçük pencere",
                            tint = Color.White
                        )
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = sliderDrag ?: position.toFloat().coerceAtMost(max),
                        onValueChange = { sliderDrag = it },
                        onValueChangeFinished = {
                            val d = sliderDrag
                            if (d != null) {
                                controller.seekTo(d.toLong())
                            }
                            sliderDrag = null
                        },
                        valueRange = 0f..max,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(sliderDrag?.toLong() ?: position),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            formatTime(duration),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            controller.seekTo((controller.currentPosition - 10_000L).coerceAtLeast(0L))
                        }) {
                            Icon(
                                Icons.Rounded.Replay10,
                                contentDescription = "10 saniye geri",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(onClick = { controller.seekToPreviousMediaItem() }) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = "Önceki",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        FilledIconButton(
                            onClick = {
                                if (isPlaying) controller.pause() else controller.play()
                            },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Oynat / Duraklat",
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        IconButton(onClick = { controller.seekToNextMediaItem() }) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "Sonraki",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        IconButton(onClick = {
                            controller.seekTo(controller.currentPosition + 10_000L)
                        }) {
                            Icon(
                                Icons.Rounded.Forward10,
                                contentDescription = "10 saniye ileri",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (gestureText.isNotEmpty()) {
            Text(
                gestureText,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x88000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun VideoRow(
    video: Video,
    active: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
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
                formatTime(video.duration) + " · " + formatSize(video.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        trailing()
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
            } else {
                null
            }
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
