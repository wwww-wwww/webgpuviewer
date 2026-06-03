package moe.grass.webgpuviewer

import android.graphics.Bitmap
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import java.lang.Math.pow
import kotlin.math.log10

@Composable
fun WebGpuImageViewer(
    bitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    val renderer = remember { WebGpuRenderer() }
    val renderChannel = remember { Channel<Float>(Channel.CONFLATED) }

    AndroidExternalSurface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val y = pow(10.0, log10(renderer.scale) + dragAmount.y / 1000.0)
                    renderChannel.trySend(y.toFloat())
                }
            },
    ) {
        onSurface { surface, width, height ->
            try {
                renderer.init(bitmap, surface, width, height)

                renderer.render()

                renderChannel.consumeAsFlow().collect { nextScale ->
                    renderer.updateUniforms(0f, 0f, nextScale)
                    renderer.render()
                }
            } finally {
                renderer.cleanup()
            }
        }
    }
}
