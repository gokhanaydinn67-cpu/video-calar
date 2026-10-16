package com.video.calar

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.util.Size
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

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
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(MODE_NONE) }
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }
    var startX by remember { mutableFloatStateOf(0f) }
    var areaWidth by remember { mutableIntStateOf(0) }
    var seekBase by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }

    // Oynatıcı açıkken ekranın kapanıp kilitlenmesini engeller.
    // Ekran ayrıldığında eski ayara döner.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val max = duration.coerceAtLeast(1L).toFloat()

    LaunchedEffect(controller) {
        while (true) {
            position = controller.currentPosition.coerceAtLeast(0L)
            duration = controller.duration.coerceAtLeast(0L)
            isPlaying = controller.isPlaying
            // Player yeni konuma gelince bekleyen sarma hedefini bırak.
            val pending = pendingSeek
            if (pending != null && abs(position - pending) < 1000L) {
                pendingSeek = null
            }
            delay(250)
        }
    }

    // Player hiç konum bildirmezse bile çubuk sonsuza kadar takılı kalmasın.
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(2000)
            pendingSeek = null
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
                            seekBase = controller.currentPosition.coerceAtLeast(0L)
                            seekTarget = null
                        },
                        onDragEnd = {
                            if (mode == MODE_SEEK) {
                                seekTarget?.let {
                                    pendingSeek = it
                                    controller.seekTo(it)
                                }
                            }
                            seekTarget = null
                            mode = MODE_NONE
                            gestureText = ""
                        },
                        onDragCancel = {
                            seekTarget = null
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
                                val dur = controller.duration.coerceAtLeast(0L)
                                val target = (seekBase + (accX * 200f).toLong()).coerceIn(0L, dur)
                                seekTarget = target
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
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = sliderDrag
                            ?: pendingSeek?.toFloat()
                            ?: position.toFloat().coerceAtMost(max),
                        onValueChange = { sliderDrag = it },
                        onValueChangeFinished = {
                            val d = sliderDrag
                            if (d != null) {
                                pendingSeek = d.toLong()
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
                            formatTime(sliderDrag?.toLong() ?: pendingSeek ?: position),
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
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPip) {
                            Icon(
                                Icons.Rounded.PictureInPicture,
                                contentDescription = "Küçük pencere",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = "Tam ekran",
                                tint = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
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
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VideoThumb(video, 112.dp, 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
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
