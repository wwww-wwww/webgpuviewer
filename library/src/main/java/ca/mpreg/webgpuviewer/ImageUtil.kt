package ca.mpreg.webgpuviewer

import android.graphics.Bitmap

object ImageUtil {
    init {
        System.loadLibrary("resize")
    }

    external fun resizeLinearAreaNative(srcBitmap: Bitmap, dstBitmap: Bitmap)

    fun resize(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        if (source.config != Bitmap.Config.ARGB_8888) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }.let {
            resizeLinearAreaNative(it, output)
        }
        return output
    }
}
