package com.video.calar

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Listenin sağ kenarında hızlı kaydırma çubuğu.
// Dokununca çıkar, sürükleyince listede o noktaya gider.
@Composable
fun BoxScope.FastScroller(listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var active by remember { mutableStateOf(false) }
    var trackHeight by remember { mutableFloatStateOf(0f) }

    val total = listState.layoutInfo.totalItemsCount
    val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val scrollable = total > visible
    val showThumb = scrollable && (active || listState.isScrollInProgress)

    val fraction = if (scrollable) {
        (listState.firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
    } else {
        0f
    }
    val minThumbPx = with(density) { 48.dp.toPx() }
    val thumbPx = if (total > 0) {
        (trackHeight * visible / total).coerceAtLeast(minThumbPx)
    } else {
        minThumbPx
    }
    val travelPx = (trackHeight - thumbPx).coerceAtLeast(0f)
    val thumbTopPx = travelPx * fraction
    val thumbHeightDp = with(density) { thumbPx.toDp() }

    fun jumpTo(y: Float) {
        if (trackHeight <= 0f || total <= 0) return
        val ratio = (y / trackHeight).coerceIn(0f, 1f)
        val index = (ratio * (total - 1)).roundToInt()
        scope.launch { listState.scrollToItem(index) }
    }

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(28.dp)
            .onSizeChanged { trackHeight = it.height.toFloat() }
            .pointerInput(total) {
                detectDragGestures(
                    onDragStart = { offset ->
                        active = true
                        jumpTo(offset.y)
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                    onDrag = { change, _ ->
                        change.consume()
                        jumpTo(change.position.y)
                    }
                )
            }
    ) {
        if (showThumb) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .width(6.dp)
                    .height(thumbHeightDp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.7f))
            )
        }
    }
}
