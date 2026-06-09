package ca.mpreg.webgpuviewer

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AbstractComposeView

open class WebGpuImageView(context: Context, attrs: AttributeSet? = null) :
    AbstractComposeView(context, attrs) {
    var renderer: WebGpuRenderer? = null

    var startFitWidth: Boolean = true
    var startFitHeight: Boolean = true
    var zoomStartPosition: Offset = Offset.Zero

    constructor(
        context: Context,
        startFitWidth: Boolean,
        startFitHeight: Boolean,
        zoomStartPosition: Offset
    ) : this(context) {
        this.startFitWidth = startFitWidth
        this.startFitHeight = startFitHeight
        this.zoomStartPosition = zoomStartPosition
    }

    protected val content = mutableStateOf<(@Composable () -> Unit)?>(null)

    @Composable
    override fun Content() {
        content.value?.invoke()
    }

    open fun init(bitmap: Bitmap) {
        renderer?.cleanup()
        renderer = WebGpuRenderer()
        this.content.value = {
            WebGpuImageViewer(
                renderer = renderer!!,
                bitmap = bitmap,
                startFitWidth = startFitWidth,
                startFitHeight = startFitHeight,
                zoomStartPosition = zoomStartPosition,
            )
        }
    }
}

class WebGpuImageWindowView(context: Context, attrs: AttributeSet? = null) :
    WebGpuImageView(context, attrs) {

    override fun init(bitmap: Bitmap) {
        renderer?.cleanup()
        renderer = WebGpuRenderer()
        this.content.value = {
            WebGpuImageWindow(renderer = renderer!!, bitmap = bitmap)
        }
    }
}
