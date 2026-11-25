package com.carstalker.app.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import com.carstalker.app.data.PdfPreview
import com.carstalker.app.ui.components.EmptyState
import com.carstalker.app.ui.theme.LocalStalkerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen preview of a generated PDF. Pages are rendered with [PdfRenderer] so the user can
 * read the report before deciding to share or print it.
 */
@Composable
fun PdfPreviewScreen(
    preview: PdfPreview,
    onClose: () -> Unit,
    onShare: () -> Boolean,
    onPrint: (Activity) -> Boolean,
) {
    val stalker = LocalStalkerColors.current
    val context = LocalContext.current
    val renderer = remember(preview.file) { runCatching { PdfPageRenderer(preview.file) }.getOrNull() }
    var actionError by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var currentPage by remember { mutableIntStateOf(0) }
    var resetZoomKey by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { currentPage = it }
    }
    DisposableEffect(renderer) {
        onDispose { renderer?.close() }
    }
    BackHandler(onBack = onClose)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // The preview covers the shell; swallow taps so nothing underneath reacts to them.
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close preview")
                }
                Column(Modifier.weight(1f)) {
                    Text("REPORT PREVIEW", style = MaterialTheme.typography.labelSmall, color = stalker.accent)
                    Text(
                        preview.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "VIN is masked unless enabled in Settings.",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                }
            }
            if (renderer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Page ${currentPage + 1} of ${renderer.pageCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stalker.muted,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem((currentPage - 1).coerceAtLeast(0))
                                }
                            },
                            enabled = currentPage > 0,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) { Text("Previous", style = MaterialTheme.typography.labelSmall) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem((currentPage + 1).coerceAtMost(renderer.pageCount - 1))
                                }
                            },
                            enabled = currentPage < renderer.pageCount - 1,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) { Text("Next", style = MaterialTheme.typography.labelSmall) }
                        TextButton(
                            onClick = { resetZoomKey++ },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) { Text("Fit", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val pageWidthPx = with(LocalDensity.current) { (maxWidth - 32.dp).roundToPx() }
                if (renderer == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = "Preview unavailable",
                            message = "The PDF could not be opened here, but it is still ready to share or print.",
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(renderer.pageCount) { index ->
                            PdfPageItem(renderer, index, pageWidthPx, resetZoomKey)
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            actionError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        actionError = if (onShare()) null else "No app is available to share this report."
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                Button(
                    onClick = {
                        val activity = context as? Activity
                        actionError = if (activity != null && onPrint(activity)) {
                            null
                        } else {
                            "No print service is available on this device."
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = stalker.accent, contentColor = Color.Black),
                ) {
                    Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Print")
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(renderer: PdfPageRenderer, index: Int, widthPx: Int, resetZoomKey: Int) {
    val stalker = LocalStalkerColors.current
    // Render the fit view above screen resolution so the first zoom step is not blurry.
    val renderWidthPx = remember(widthPx) { (widthPx * SHARP_RENDER_FACTOR).roundToInt() }
    var bitmap by remember(renderer, index, renderWidthPx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(renderer, index, renderWidthPx) { mutableStateOf(false) }
    LaunchedEffect(renderer, index, renderWidthPx) {
        val rendered = renderer.renderPage(index, renderWidthPx)
        bitmap = rendered
        failed = rendered == null
    }
    val page = bitmap

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 2.dp,
        ) {
            when {
                page != null -> PdfPageView(
                    renderer = renderer,
                    index = index,
                    bitmap = page,
                    contentDescription = "Report page ${index + 1}",
                    resetZoomKey = resetZoomKey,
                    modifier = Modifier.fillMaxWidth().aspectRatio(PdfPageRenderer.PAGE_ASPECT_RATIO),
                )
                failed -> Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(PdfPageRenderer.PAGE_ASPECT_RATIO),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Page ${index + 1} could not be rendered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(PdfPageRenderer.PAGE_ASPECT_RATIO),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = stalker.accent,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Page ${index + 1} of ${renderer.pageCount}",
            style = MaterialTheme.typography.labelSmall,
            color = stalker.muted,
        )
    }
}

/**
 * A page with pinch-to-zoom, one-finger panning while zoomed, and double-tap to toggle zoom.
 *
 * The fit image is only screen-resolution, so once a zoom or pan gesture settles the visible
 * region is re-rendered from the PDF at [DETAIL_SUPERSAMPLE] times the view resolution and
 * swapped in over the scaled image. Text therefore stays crisp without ever allocating a huge
 * full-page bitmap.
 */
@Composable
private fun PdfPageView(
    renderer: PdfPageRenderer,
    index: Int,
    bitmap: ImageBitmap,
    contentDescription: String,
    resetZoomKey: Int,
    modifier: Modifier = Modifier,
) {
    var pageZoom by remember { mutableStateOf(PageZoom()) }
    androidx.compose.runtime.LaunchedEffect(resetZoomKey) { pageZoom = PageZoom() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var detail by remember { mutableStateOf<ImageBitmap?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun animateTo(target: PageZoom) {
        animationJob?.cancel()
        detail = null
        animationJob = scope.launch {
            animatePageZoom(pageZoom, target) { pageZoom = it }
            detailRequest++
        }
    }

    // Re-render the visible region whenever a zoom/pan gesture or animation settles.
    LaunchedEffect(detailRequest, viewSize) {
        val zoom = pageZoom
        if (!zoom.isZoomed || viewSize.width == 0 || viewSize.height == 0) {
            detail = null
            detailLoading = false
            return@LaunchedEffect
        }
        delay(DETAIL_RENDER_DELAY_MS)
        detailLoading = true
        detail = renderer.renderRegion(
            index = index,
            scale = zoom.scale,
            offset = zoom.offset,
            viewSize = viewSize,
        )
        detailLoading = false
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectPageTransformGestures(
                    isZoomed = { pageZoom.isZoomed },
                    onTransform = { centroid, pan, zoom ->
                        animationJob?.cancel()
                        detail = null
                        pageZoom = pageZoom.transformed(centroid, pan, zoom, viewSize)
                    },
                    onGestureEnd = { detailRequest++ },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (pageZoom.isZoomed) {
                            animateTo(PageZoom())
                        } else {
                            animateTo(pageZoom.transformed(tap, Offset.Zero, DOUBLE_TAP_PAGE_ZOOM, viewSize))
                        }
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pageZoom.scale
                    scaleY = pageZoom.scale
                    translationX = pageZoom.offset.x
                    translationY = pageZoom.offset.y
                },
        )
        detail?.let { sharp ->
            // The crop is exactly the visible region, so it replaces it one-to-one on screen.
            Image(
                bitmap = sharp,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (detailLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(16.dp),
                strokeWidth = 2.dp,
                color = LocalStalkerColors.current.accent,
            )
        }
        if (pageZoom.isZoomed) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { animateTo(PageZoom()) },
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.62f),
                contentColor = Color.White,
            ) {
                Text(
                    "${(pageZoom.scale * 100).roundToInt()}% · Reset",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Scale and pan of one page, with the gesture maths kept out of the composable. */
internal data class PageZoom(
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    val isZoomed: Boolean get() = scale > 1.001f

    /**
     * Applies a gesture update while keeping the content under [centroid] pinned. Both the zoom
     * level and the pan offset are clamped to the page bounds.
     */
    fun transformed(centroid: Offset, pan: Offset, zoom: Float, viewSize: IntSize): PageZoom {
        if (viewSize.width == 0 || viewSize.height == 0) return this
        if (!centroid.isFiniteOffset() || !pan.isFiniteOffset() || !zoom.isFinite()) return this
        val newScale = (scale * zoom).coerceIn(MIN_PAGE_ZOOM, MAX_PAGE_ZOOM)
        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
        val candidate = centroid + pan - center - (centroid - center - offset) * (newScale / scale)
        val maxX = viewSize.width * (newScale - 1f) / 2f
        val maxY = viewSize.height * (newScale - 1f) / 2f
        return PageZoom(
            scale = newScale,
            offset = Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY)),
        )
    }
}

/**
 * Like [androidx.compose.foundation.gestures.detectTransformGestures], but only claims single-finger
 * drags once the page is zoomed, so the parent list still handles scrolling at 1x.
 */
private suspend fun PointerInputScope.detectPageTransformGestures(
    isZoomed: () -> Boolean,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pastTouchSlop = false
        var multiTouch = false
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        do {
            val event = awaitPointerEvent()
            multiTouch = multiTouch || event.changes.count { it.pressed } >= 2
            if (!multiTouch && !isZoomed()) continue

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastTouchSlop) {
                // Individual touch events are tiny; accumulate them until the gesture is real.
                accumulatedZoom *= zoomChange
                accumulatedPan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                if (zoomMotion > viewConfiguration.touchSlop ||
                    accumulatedPan.getDistance() > viewConfiguration.touchSlop
                ) {
                    pastTouchSlop = true
                }
            }
            if (pastTouchSlop && (zoomChange != 1f || panChange != Offset.Zero)) {
                onTransform(event.calculateCentroid(useCurrent = false), panChange, zoomChange)
                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
            }
        } while (event.changes.any { it.pressed })
        onGestureEnd()
    }
}

private suspend fun animatePageZoom(from: PageZoom, to: PageZoom, onFrame: (PageZoom) -> Unit) {
    animate(0f, 1f, animationSpec = tween(durationMillis = 220)) { fraction, _ ->
        onFrame(
            PageZoom(
                scale = lerpFloat(from.scale, to.scale, fraction),
                offset = lerpOffset(from.offset, to.offset, fraction),
            ),
        )
    }
}

private fun Offset.isFiniteOffset(): Boolean = x.isFinite() && y.isFinite()

private const val MIN_PAGE_ZOOM = 1f
private const val MAX_PAGE_ZOOM = 4f
private const val DOUBLE_TAP_PAGE_ZOOM = 2.5f

/** Render the fit image above screen resolution so the first zoom step is not blurry. */
private const val SHARP_RENDER_FACTOR = 1.5f

/** Supersampling for the sharp crop of the visible region. */
private const val DETAIL_SUPERSAMPLE = 1.5f

/** Wait for a gesture or animation to settle before rendering the sharp crop. */
private const val DETAIL_RENDER_DELAY_MS = 160L

/**
 * Renders pages from a generated PDF. [PdfRenderer] is not thread-safe, so renders are serialized
 * through [mutex] and [close] waits for any in-flight render before releasing the document.
 */
internal class PdfPageRenderer(file: File) : AutoCloseable {
    private val mutex = Mutex()
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        throw error
    }
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap? = mutex.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val heightPx = (widthPx * page.height / page.width.toFloat()).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                } finally {
                    page.close()
                }
            }.getOrNull()
        }
    }

    /**
     * Renders the part of the page visible at [scale]/[offset] into a tile that covers exactly the
     * page view. The tile is [DETAIL_SUPERSAMPLE] times the view resolution, so text stays sharp
     * while zoomed without ever allocating a full-page high-resolution bitmap.
     */
    suspend fun renderRegion(
        index: Int,
        scale: Float,
        offset: Offset,
        viewSize: IntSize,
    ): ImageBitmap? = mutex.withLock {
        if (closed || viewSize.width == 0 || viewSize.height == 0 || scale <= 1f) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val widthPx = (viewSize.width * DETAIL_SUPERSAMPLE).roundToInt()
                    val heightPx = (viewSize.height * DETAIL_SUPERSAMPLE).roundToInt()
                    // Page coordinates are points; the view shows the page fit to its width.
                    val fitX = page.width / viewSize.width.toFloat()
                    val fitY = page.height / viewSize.height.toFloat()
                    val centerX = viewSize.width / 2f
                    val centerY = viewSize.height / 2f
                    val left = fitX * (centerX - centerX / scale - offset.x / scale)
                    val top = fitY * (centerY - centerY / scale - offset.y / scale)
                    val visibleWidth = viewSize.width * fitX / scale
                    val visibleHeight = viewSize.height * fitY / scale
                    val pxPerPointX = widthPx / visibleWidth
                    val pxPerPointY = heightPx / visibleHeight
                    val matrix = Matrix().apply {
                        postScale(pxPerPointX, pxPerPointY)
                        postTranslate(-left * pxPerPointX, -top * pxPerPointY)
                    }
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                } finally {
                    page.close()
                }
            }.getOrNull()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // A page render may still be running on the IO dispatcher; let it finish first.
        runBlocking { mutex.withLock { } }
        renderer.close()
        descriptor.close()
    }

    companion object {
        /** A4 proportions, used as a placeholder while a page renders. */
        const val PAGE_ASPECT_RATIO = 595f / 842f
    }
}
