package moe.grass.webgpuviewer

import android.graphics.Bitmap
import android.graphics.Point
import android.util.Log
import android.view.Surface
import androidx.webgpu.BufferUsage
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.DeviceLostException
import androidx.webgpu.FeatureLevel
import androidx.webgpu.GPU.createInstance
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUInstanceDescriptor
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUVertexState
import androidx.webgpu.LoadOp
import androidx.webgpu.PrimitiveTopology.Companion.TriangleList
import androidx.webgpu.StoreOp
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.WebGpuRuntimeException
import androidx.webgpu.helper.Util.windowFromSurface
import androidx.webgpu.helper.createGpuTexture
import androidx.webgpu.helper.initLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object webgpu {
    var instance: GPUInstance? = null
    var adapter: GPUAdapter? = null
    var device: GPUDevice? = null
    var pipeline: GPURenderPipeline? = null
}

class WebGpuRenderer {
    private lateinit var surface: GPUSurface

    var tilesize = 4096

    var width: Int = 0
    var height: Int = 0

    var image_width: Int = 0
    var image_height: Int = 0

    var minScale: Float = 0f
    var ratiox: Float = 0f
    var ratioy: Float = 0f

    var ready = false

    class Mipmap(
        val image_width: Int,
        val image_height: Int,
        val scale: Float,
        val tilesCols: Int,
        val tilesRows: Int,
        val tilesize: Int,
    ) {
        var actualTextures: MutableList<GPUTexture> = mutableListOf()
        var tiles: MutableList<GPUTexture> = mutableListOf()
        private lateinit var byteBuffer: ByteBuffer
        var tilesWidth: Int = 0
        var tilesHeight: Int = 0

        var x: Int = -1
        var y: Int = -1

        constructor(
            image_width: Int,
            image_height: Int,
            scale: Float,
            texture: GPUTexture,
            tilesize: Int,
        ) : this(
            image_width = image_width,
            image_height = image_height,
            scale = scale,
            tilesCols = 1,
            tilesRows = 1,
            tilesize = tilesize,
        ) {
            actualTextures.add(texture)

            repeat(4) {
                tiles.add(texture)
            }

            tilesWidth = image_width
            tilesHeight = image_height
        }

        companion object {
            suspend operator fun invoke(
                device: GPUDevice,
                image: Bitmap,
                scale: Float,
                tilesize: Int,
                generateAll: Boolean,
            ): Mipmap {
                val mipmap = Mipmap(
                    image_width = image.width,
                    image_height = image.height,
                    scale = scale,
                    tilesCols = ceil(image.width.toFloat() / tilesize).toInt()
                        .coerceAtMost(if (generateAll) Int.MAX_VALUE else 2),
                    tilesRows = ceil(image.height.toFloat() / tilesize).toInt()
                        .coerceAtMost(if (generateAll) Int.MAX_VALUE else 2),
                    tilesize = tilesize,
                )

                mipmap.tilesWidth = min(mipmap.image_width, tilesize * mipmap.tilesCols)
                mipmap.tilesHeight = min(mipmap.image_height, tilesize * mipmap.tilesRows)

                if (generateAll) {
                    for (r in 0 until mipmap.tilesRows) {
                        val textureHeight =
                            min((r + 1) * tilesize, mipmap.image_height) - (r * tilesize)
                        for (c in 0 until mipmap.tilesCols) {
                            Log.i("webgpuviewer", "Create tile " + c + " " + r)
                            val textureWidth =
                                min((c + 1) * tilesize, mipmap.image_width) - (c * tilesize)
                            val cropped = withContext(Dispatchers.Default) {
                                Bitmap.createBitmap(
                                    image,
                                    c * tilesize,
                                    r * tilesize,
                                    textureWidth,
                                    textureHeight
                                )
                            }
                            val texture = cropped.createGpuTexture(device)
                            mipmap.actualTextures.add(texture)
                        }
                    }
                    mipmap.x = 0
                    mipmap.y = 0
                } else {
                    for (r in 0 until mipmap.tilesRows) {
                        val textureHeight =
                            min((r + 1) * tilesize, mipmap.image_height) - (r * tilesize)
                        for (c in 0 until mipmap.tilesCols) {
                            val textureWidth =
                                min((c + 1) * tilesize, mipmap.image_width) - (c * tilesize)
                            Log.i("Renderer", "Create texture $r $c $textureWidth $textureHeight")
                            mipmap.actualTextures.add(
                                device.createTexture(
                                    GPUTextureDescriptor(
                                        usage = TextureUsage.CopyDst or TextureUsage.TextureBinding,
                                        size = GPUExtent3D(textureWidth, textureHeight, 1),
                                        format = TextureFormat.RGBA8Unorm
                                    )
                                )
                            )
                        }
                    }

                    mipmap.loadImage(image)
                }

                for (r in 0 until 2) {
                    val row = r.coerceAtMost(mipmap.tilesRows - 1) * mipmap.tilesCols
                    for (c in 0 until 2) {
                        val i = row + c.coerceAtMost(mipmap.tilesCols - 1)
                        mipmap.tiles.add(mipmap.actualTextures[i])
                    }
                }

                return mipmap
            }
        }

        suspend fun loadImage(image: Bitmap) {
            withContext(Dispatchers.Default) {
                byteBuffer = ByteBuffer.allocateDirect(image.byteCount)
                byteBuffer.order(ByteOrder.nativeOrder())
                image.copyPixelsToBuffer(byteBuffer)
            }

            load(webgpu.device!!, x, y, true)
        }

        fun load(device: GPUDevice, centerX: Int, centerY: Int, force: Boolean = false) {
            val s = tilesize.toFloat() / 2
            var x = (round(centerX / s) * s).toInt()
            var y = (round(centerY / s) * s).toInt()
            x = (x - tilesWidth / 2).coerceIn(0, image_width - tilesWidth)
            y = (y - tilesHeight / 2).coerceIn(0, image_height - tilesHeight)

            if (force || this.x == x && this.y == y) {
                return
            }

            this.x = x
            this.y = y

            Log.i("Renderer", "load $scale $x $y $tilesCols $tilesRows")

            for (r in 0 until tilesRows) {
                val ty = y + r * actualTextures[0].height

                for (c in 0 until tilesCols) {
                    val texture = actualTextures[r * tilesCols + c]
                    val tx = x + c * actualTextures[0].width
                    val tw = min(texture.width, image_width - tx)
                    val th = min(texture.height, image_height - ty)

                    Log.i(
                        "Renderer",
                        "Copy $tx $ty ${ty * image_width + tx} ${tw} ${th} ${image_width} ${image_height} ${texture.width} ${texture.height}"
                    )

                    device.queue.writeTexture(
                        dataLayout =
                            GPUTexelCopyBufferLayout(
                                offset = (ty * image_width + tx) * 4L,
                                bytesPerRow = image_width * 4,
                                rowsPerImage = th,
                            ),
                        data = byteBuffer,
                        destination = GPUTexelCopyTextureInfo(texture = texture),
                        writeSize = GPUExtent3D(tw, th)
                    )
                }
            }
        }

        fun get(device: GPUDevice, centerX: Int, centerY: Int): Point {
            if (tilesCols == 1 && tilesRows == 1) {
                return Point(0, 0)
            }

            load(device, centerX, centerY)

            val cX = centerX.toFloat()
            val cY = centerY.toFloat()

            val c = (cX / tilesize).toInt()
            val tX = when {
                c >= tilesCols - 1 -> tilesCols - 2
                c <= 0 -> 0
                else -> {
                    val xCenterRight = if (c + 1 == tilesCols - 1) {
                        ((tilesCols - 1) * tilesize + image_width) * 0.5
                    } else {
                        (c + 1.5) * tilesize
                    }

                    if (cX - (c - 0.5) * tilesize < xCenterRight - cX) c - 1 else c
                }
            }.coerceIn(0, tilesRows - 1)

            val r = (cY / tilesize).toInt()
            val tY = when {
                r >= tilesRows - 1 -> tilesRows - 2
                r <= 0 -> 0
                else -> {
                    val yCenterBottom = if (r + 1 == tilesRows - 1) {
                        ((tilesRows - 1) * tilesize + image_height) * 0.5
                    } else {
                        (r + 1.5) * tilesize
                    }

                    if (cY - (r - 0.5) * tilesize < yCenterBottom - cY) r - 1 else r
                }
            }.coerceIn(0, tilesRows - 1)

            for (r in 0 until 2) {
                val row = (tY + r).coerceAtMost(tilesRows - 1) * tilesCols
                for (c in 0 until 2) {
                    val i = row + (tX + c).coerceAtMost(tilesCols - 1)
                    tiles[r * 2 + c] = actualTextures[i]
                }
            }

            return Point(x + tX * tiles[0].width, y + tY * tiles[0].height)
        }
    }

    var mipmaps: MutableList<Mipmap> = mutableListOf()

    var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(32)
    private lateinit var buffer: GPUBuffer

    var scale: Float = 1f
    var x: Float = 0f
    var y: Float = 0f

    suspend fun init(image: Bitmap, surface: Surface, width: Int, height: Int) {
        initLibrary()

        if (webgpu.instance == null) {
            webgpu.instance = createInstance(GPUInstanceDescriptor())
            webgpu.adapter =
                webgpu.instance!!.requestAdapter(GPURequestAdapterOptions(featureLevel = FeatureLevel.Compatibility))
            webgpu.device = webgpu.adapter!!.requestDevice(
                GPUDeviceDescriptor(
                    deviceLostCallback = defaultDeviceLostCallback,
                    deviceLostCallbackExecutor = Executor(Runnable::run),
                    uncapturedErrorCallback = defaultUncapturedErrorCallback,
                    uncapturedErrorCallbackExecutor = Executor(Runnable::run),
                )
            )

            val shaderModule = webgpu.device!!.createShaderModule(
                GPUShaderModuleDescriptor(
                    shaderSourceWGSL = GPUShaderSourceWGSL(
                        WebGpuRendererShader.shader
                    )
                )
            )

            webgpu.pipeline = webgpu.device!!.createRenderPipeline(
                GPURenderPipelineDescriptor(
                    vertex = GPUVertexState(shaderModule, entryPoint = "vs_main"),
                    fragment = GPUFragmentState(
                        shaderModule,
                        entryPoint = "fs_main",
                        targets = arrayOf(GPUColorTargetState(format = TextureFormat.RGBA8Unorm))
                    ),
                    primitive = GPUPrimitiveState(topology = TriangleList),
                )
            )
        }

        this.surface = surface.let {
            webgpu.instance!!.createSurface(
                GPUSurfaceDescriptor(
                    surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(
                        windowFromSurface(it)
                    )
                )
            )
        }

        this.surface.configure(
            GPUSurfaceConfiguration(
                webgpu.device!!,
                width,
                height,
                TextureFormat.RGBA8Unorm,
                TextureUsage.RenderAttachment
            )
        )

        val device = webgpu.device!!

        this.width = width
        this.height = height

        ratiox = width.toFloat() / image.width.toFloat()
        ratioy = height.toFloat() / image.height.toFloat()

        if (this.image_width == 0) {
            this.x = 0f
            this.y = 0f
            this.minScale = min(1f, max(0.01f, min(ratiox, ratioy)))
            this.scale = this.minScale
        }

        this.image_width = image.width
        this.image_height = image.height
        byteBuffer.order(ByteOrder.nativeOrder())

        buffer = device.createBuffer(
            GPUBufferDescriptor(
                size = 32,
                usage = BufferUsage.CopyDst or BufferUsage.Uniform
            )
        )

        mipmaps.forEach { it.tiles.forEach { it.destroy() } }
        mipmaps.clear()
        mipmaps.add(Mipmap(device, image, 1f, tilesize, true))

        Log.i("Renderer", "Create mipmaps")

        var scale = 1f

        while (image_width * scale > tilesize || image_height * scale > tilesize) {
            scale /= 2
            Log.i(
                "Renderer",
                "Create mipmap using CPU ${scale} ${image_width * scale} ${image_height * scale}"
            )
            val im = withContext(Dispatchers.Default) {
                ImageUtil.resize(
                    image,
                    (image_width * scale).toInt(),
                    (image_height * scale).toInt()
                )
            }
            mipmaps.add(Mipmap(device, im, scale, tilesize, true))
        }

        while (image_width * scale > width && image_height * scale > width) {
            scale /= 2
            Log.i(
                "Renderer",
                "Create mipmap using shader ${scale} ${image_width * scale} ${image_height * scale}"
            )
            val texture = device.createTexture(
                GPUTextureDescriptor(
                    size = GPUExtent3D(
                        (image_width * scale).toInt(),
                        (image_height * scale).toInt(),
                        1
                    ),
                    usage = TextureUsage.TextureBinding or TextureUsage.RenderAttachment,
                    format = TextureFormat.RGBA8Unorm
                )
            )
            render(mipmaps[mipmaps.size - 1], texture, 0f, 0f, scale)
            mipmaps.add(Mipmap(texture.width, texture.height, scale, texture, tilesize))
        }

        Log.i("Renderer", "Finished create mipmaps")

        ready = true
    }

    fun render() {
        if (mipmaps.size == 0) {
            return
        }

        var level = floor(log2(1 / scale)).toInt()
        level = max(min(level, mipmaps.size - 1), 0)
        render(mipmaps[level], surface.getCurrentTexture().texture, x, y, scale)
        surface.present()
    }

    fun render(mipmap: Mipmap, dst: GPUTexture, x: Float, y: Float, scale: Float) {
        val commandEncoder = webgpu.device!!.createCommandEncoder()

        val vx = round(((-x * width / image_width + 0.5) * mipmap.image_width)).toInt()
        val vy = round(((-y * height / image_height + 0.5) * mipmap.image_height)).toInt()

        val pos = mipmap.get(webgpu.device!!, vx, vy)

        byteBuffer.putFloat(
            0,
            (0.5f / scale + x) * mipmap.scale + (pos.x - 0.5f * mipmap.image_width) / dst.width
        )
        byteBuffer.putFloat(
            4,
            (0.5f / scale + y) * mipmap.scale + (pos.y - 0.5f * mipmap.image_height) / dst.height
        )
        byteBuffer.putFloat(8, scale / mipmap.scale)
        byteBuffer.putFloat(12, tilesize.toFloat())
        byteBuffer.putFloat(16, mipmap.tilesCols.toFloat())
        byteBuffer.putFloat(20, mipmap.tilesRows.toFloat())
        byteBuffer.putFloat(24, dst.width.toFloat())
        byteBuffer.putFloat(28, dst.height.toFloat())
        webgpu.device!!.queue.writeBuffer(buffer, 0, byteBuffer)

        val pass = commandEncoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = dst.createView(), loadOp = LoadOp.Clear, storeOp = StoreOp.Store,
                        clearValue = GPUColor(0.0, 0.0, 0.0, 1.0)
                    )
                )
            )
        )

        pass.setPipeline(webgpu.pipeline!!)
        pass.setBindGroup(
            0, webgpu.device!!.createBindGroup(
                GPUBindGroupDescriptor(
                    layout = webgpu.pipeline!!.getBindGroupLayout(0),
                    entries = arrayOf(
                        GPUBindGroupEntry(
                            binding = 0,
                            buffer = buffer
                        ),
                    ).plus(mipmap.tiles.mapIndexed { i, value ->
                        GPUBindGroupEntry(
                            binding = 1 + i,
                            textureView = value.createView()
                        )
                    })
                )
            )
        )
        pass.draw(3)
        pass.end()
        webgpu.device!!.queue.submit(arrayOf(commandEncoder.finish()))
    }

    fun cleanup() {
        surface.close()
        buffer.close()
        mipmaps.forEach { it.tiles.forEach { it.destroy() } }
        mipmaps.clear()
    }
}

private val defaultUncapturedErrorCallback
    get(): UncapturedErrorCallback {
        return UncapturedErrorCallback { _, type, message ->
            throw WebGpuRuntimeException.create(type, message)
        }
    }

private val defaultDeviceLostCallback
    get(): DeviceLostCallback {
        return DeviceLostCallback { device, reason, message ->
            throw DeviceLostException(device, reason, message)
        }
    }
