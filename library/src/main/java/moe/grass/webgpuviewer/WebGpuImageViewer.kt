package moe.grass.webgpuviewer

import android.graphics.Bitmap
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

@Composable
fun WebGpuImageViewer(
    bitmap: Bitmap,
    doubleTapScale: Float = LocalView.current.resources.displayMetrics.densityDpi / 200f,
    maxScale: Float = LocalView.current.resources.displayMetrics.densityDpi / 100f,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val renderer = remember { WebGpuRenderer() }
    val renderChannel = remember { Channel<Float>(Channel.CONFLATED) }
    val scope = rememberCoroutineScope()
    val animationJob = remember { mutableStateOf<Job?>(null) }
    val fling = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    AndroidExternalSurface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { // double tap drag
                if (!renderer.ready) {
                    return@pointerInput
                }

                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val firstDown = awaitFirstDown(pass = PointerEventPass.Initial)
                    animationJob.value?.cancel()

                    waitForCleanUp(firstDown.id, doubleTapTimeout, touchSlop)
                        ?: return@awaitEachGesture

                    val velocityTracker = VelocityTracker()
                    val secondDown = waitForDown(doubleTapTimeout) ?: return@awaitEachGesture
                    velocityTracker.addPointerInputChange(secondDown)

                    // cancel the reset from the third event
                    animationJob.value?.cancel()

                    val dragPointerId = secondDown.id

                    val originalScale = renderer.scale
                    val originalX = renderer.x
                    val originalY = renderer.y
                    var totalDeltaY = 0f

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change =
                            event.changes.firstOrNull { it.id == dragPointerId && it.positionChanged() }

                        if (change == null || change.changedToUp() || change.isConsumed) {
                            break
                        }

                        velocityTracker.addPointerInputChange(change)

                        val pan = event.calculatePan()
                        totalDeltaY += pan.y
                        if (totalDeltaY != 0f) {
                            view.parent?.requestDisallowInterceptTouchEvent(true)

                            val px = secondDown.position.x / renderer.width - 0.5f
                            val py = secondDown.position.y / renderer.height - 0.5f

                            val new_scale =
                                originalScale * 10f.pow(2 * totalDeltaY / renderer.height)

                            renderer.scale = new_scale
                            val diff = 1 / renderer.scale - 1 / originalScale

                            renderer.x = originalX + px * diff
                            renderer.y = originalY + py * diff
                            renderChannel.trySend(0f)
                            change.consume()
                        }
                    }

                    val velocity = velocityTracker.calculateVelocity()
                    if (abs(velocity.y) > 200 &&
                        (velocity.y < 0 && renderer.scale > renderer.min_scale ||
                                velocity.y > 0 && renderer.scale < maxScale)
                    ) { // fling zoom
                        animationJob.value = scope.launch {
                            val animation = Animatable(0f)
                            animation.snapTo(0f)
                            animation.animateDecay(velocity.y, exponentialDecay()) {
                                val px = secondDown.position.x / renderer.width - 0.5f
                                val py = secondDown.position.y / renderer.height - 0.5f

                                val new_scale =
                                    originalScale * 10f.pow(2 * (totalDeltaY + value) / renderer.height)

                                renderer.scale = new_scale.coerceIn(renderer.min_scale, maxScale)
                                val diff = 1 / renderer.scale - 1 / originalScale

                                val x = originalX + px * diff
                                val y = originalY + py * diff

                                val max_x = max(
                                    0f,
                                    (renderer.image_width.toFloat() / renderer.width - 1 / renderer.scale) / 2
                                )
                                val max_y = max(
                                    0f,
                                    (renderer.image_height.toFloat() / renderer.height - 1 / renderer.scale) / 2
                                )

                                renderer.x = x.coerceIn(-max_x, max_x)
                                renderer.y = y.coerceIn(-max_y, max_y)
                                renderChannel.trySend(0f)
                            }
                        }
                    } else {
                        val targetScale = renderer.scale.coerceIn(renderer.min_scale, maxScale)
                        val max_x = max(
                            0f,
                            (renderer.image_width.toFloat() / renderer.width - 1 / targetScale) / 2
                        )
                        val max_y = max(
                            0f,
                            (renderer.image_height.toFloat() / renderer.height - 1 / targetScale) / 2
                        )
                        val targetX = renderer.x.coerceIn(-max_x, max_x)
                        val targetY = renderer.y.coerceIn(-max_y, max_y)

                        val startScale = renderer.scale
                        val startX = renderer.x
                        val startY = renderer.y

                        animationJob.value = scope.launch {
                            animate(0f, 1f, animationSpec = tween(300)) { value, _ ->
                                renderer.scale = startScale + (targetScale - startScale) * value
                                renderer.x = startX + (targetX - startX) * value
                                renderer.y = startY + (targetY - startY) * value
                                renderChannel.trySend(0f)
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) { // double tap
                if (!renderer.ready) {
                    return@pointerInput
                }
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val firstDown = awaitFirstDown(pass = PointerEventPass.Initial)
                    waitForCleanUp(firstDown.id, doubleTapTimeout, touchSlop)
                        ?: return@awaitEachGesture
                    val secondDown = waitForDown(doubleTapTimeout) ?: return@awaitEachGesture
                    waitForCleanUp(secondDown.id, doubleTapTimeout, touchSlop)
                        ?: return@awaitEachGesture

                    // cancel the reset from the third event
                    animationJob.value?.cancel()

                    val startScale = renderer.scale
                    val startX = renderer.x
                    val startY = renderer.y

                    var targetScale: Float
                    var targetX: Float
                    var targetY: Float

                    if (renderer.scale == renderer.min_scale) {
                        targetScale = max(doubleTapScale, renderer.min_scale)
                        val diff = 1 / targetScale - 1 / renderer.scale

                        targetX =
                            renderer.x + (secondDown.position.x / renderer.width - 0.5f) * diff
                        targetY =
                            renderer.y + (secondDown.position.y / renderer.height - 0.5f) * diff
                        val max_x = max(
                            0f,
                            (renderer.image_width.toFloat() / renderer.width - 1 / targetScale) / 2
                        )
                        val max_y = max(
                            0f,
                            (renderer.image_height.toFloat() / renderer.height - 1 / targetScale) / 2
                        )
                        targetX = targetX.coerceIn(-max_x, max_x)
                        targetY = targetY.coerceIn(-max_y, max_y)
                    } else {
                        targetX = 0f
                        targetY = 0f
                        targetScale = renderer.min_scale
                    }

                    animationJob.value = scope.launch {
                        animate(0f, 1f, animationSpec = tween(300)) { value, _ ->
                            renderer.scale = startScale + (targetScale - startScale) * value
                            renderer.x = startX + (targetX - startX) * value
                            renderer.y = startY + (targetY - startY) * value
                            renderChannel.trySend(0f)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                if (!renderer.ready) {
                    return@pointerInput
                }
                awaitEachGesture {
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop

                    var moved = false
                    var dragOnly = true

                    val velocityTracker = VelocityTracker()

                    val firstDown =
                        awaitFirstDown(requireUnconsumed = true)
                    velocityTracker.addPointerInputChange(firstDown)
                    var lastMoveTime = firstDown.uptimeMillis
                    var lastEventTime: Long = firstDown.uptimeMillis

                    if (renderer.scale > renderer.min_scale) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val change = event.changes[0]
                            lastEventTime = change.uptimeMillis

                            if (event.changes.size > 1) {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                dragOnly = false
                            }

                            if (dragOnly) {
                                if (change.positionChanged()) {
                                    lastMoveTime = change.uptimeMillis
                                }
                                velocityTracker.addPointerInputChange(change)
                            }

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange

                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - zoom) * centroidSize
                                val panMotion = pan.getDistance()

                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                }
                            } else {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val new_scale = renderer.scale * zoomChange
                                    val diff = 1 / new_scale - 1 / renderer.scale

                                    var x =
                                        renderer.x + (panChange.x / renderer.width) / renderer.scale
                                    var y =
                                        renderer.y + (panChange.y / renderer.height) / renderer.scale

                                    x += (centroid.x / renderer.width - 0.5f) * diff
                                    y += (centroid.y / renderer.height - 0.5f) * diff

                                    val max_x = max(
                                        0f,
                                        (renderer.image_width.toFloat() / renderer.width - 1 / new_scale) / 2
                                    )
                                    val max_y = max(
                                        0f,
                                        (renderer.image_height.toFloat() / renderer.height - 1 / new_scale) / 2
                                    )

                                    if (renderer.scale != new_scale) {
                                        moved = true
                                    }
                                    if (view.scrollable(true) &&
                                        x.coerceIn(-max_x, max_x) != renderer.x
                                    ) {
                                        moved = true
                                    }
                                    if (view.scrollable(false) &&
                                        x.coerceIn(-max_y, max_y) != renderer.y
                                    ) {
                                        moved = true
                                    }

                                    if (moved) {
                                        renderer.scale = new_scale
                                        renderer.x = x
                                        renderer.y = y
                                    }

                                    renderChannel.trySend(0f)
                                }

                                if (!moved) {
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                        }

                        if (moved) {
                            event.changes.forEach {
                                if (it.positionChanged()) {
                                    it.consume()
                                }
                            }
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    } while (!canceled && event.changes.any { it.pressed })

                    val velocity = velocityTracker.calculateVelocity()
                    if (
                        moved && dragOnly && (lastEventTime - lastMoveTime) < 100 &&
                        (abs(velocity.x) > 400 || abs(velocity.y) > 400)
                    ) {
                        animationJob.value = scope.launch {
                            fling.snapTo(Offset.Zero)
                            var lastOffset = Offset.Zero
                            fling.animateDecay(Offset(velocity.x, velocity.y), exponentialDecay()) {
                                val delta = value - lastOffset
                                lastOffset = value
                                val dx = (delta.x / renderer.width) / renderer.scale
                                val dy = (delta.y / renderer.height) / renderer.scale
                                val max_x = max(
                                    0f,
                                    (renderer.image_width.toFloat() / renderer.width - 1 / renderer.scale) / 2
                                )
                                val max_y = max(
                                    0f,
                                    (renderer.image_height.toFloat() / renderer.height - 1 / renderer.scale) / 2
                                )
                                renderer.x = (renderer.x + dx).coerceIn(-max_x, max_x)
                                renderer.y = (renderer.y + dy).coerceIn(-max_y, max_y)
                                renderChannel.trySend(0f)
                            }
                        }
                    } else {
                        val targetScale = renderer.scale.coerceIn(renderer.min_scale, maxScale)
                        val max_x = max(
                            0f,
                            (renderer.image_width.toFloat() / renderer.width - 1 / targetScale) / 2
                        )
                        val max_y = max(
                            0f,
                            (renderer.image_height.toFloat() / renderer.height - 1 / targetScale) / 2
                        )
                        val targetX = renderer.x.coerceIn(-max_x, max_x)
                        val targetY = renderer.y.coerceIn(-max_y, max_y)

                        val startScale = renderer.scale
                        val startX = renderer.x
                        val startY = renderer.y

                        animationJob.value = scope.launch {
                            animate(0f, 1f, animationSpec = tween(300)) { value, _ ->
                                renderer.scale = startScale + (targetScale - startScale) * value
                                renderer.x = startX + (targetX - startX) * value
                                renderer.y = startY + (targetY - startY) * value
                                renderChannel.trySend(0f)
                            }
                        }
                    }
                }
            },
    ) {
        onSurface { surface, width, height ->
            try {
                renderer.init(bitmap, surface, width, height)
                renderer.render()

                renderChannel.receiveAsFlow().collect {
                    renderer.render()
                }
            } finally {
                animationJob.value?.cancel()
                renderer.cleanup()
            }
        }
    }
}

fun View.scrollable(horizontal: Boolean): Boolean {
    var p = parent
    while (p != null && p is View) {
        if (horizontal && (p.canScrollHorizontally(1) || p.canScrollHorizontally(-1))) return true
        if (!horizontal && (p.canScrollVertically(1) || p.canScrollVertically(-1))) return true
        p = p.parent
    }
    return false
}

private suspend fun AwaitPointerEventScope.waitForCleanUp(
    pointerId: PointerId,
    timeout: Long,
    touchSlop: Float
): PointerEvent? = try {
    withTimeout(timeout) {
        var acc = Offset.Zero

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: return@withTimeout null
            acc += event.calculatePan()
            if (acc.getDistance() > touchSlop) {
                return@withTimeout null
            }
            if (change.changedToUp()) {
                return@withTimeout event
            }
        }
    }
} catch (e: TimeoutCancellationException) {
    null
} as PointerEvent?

private suspend fun AwaitPointerEventScope.waitForDown(timeout: Long) =
    try {
        withTimeout(timeout) {
            var down = awaitPointerEvent().changes.firstOrNull { it.pressed }
            while (down == null) {
                down = awaitPointerEvent().changes.firstOrNull { it.pressed }
            }
            down
        }
    } catch (e: TimeoutCancellationException) {
        null
    }
