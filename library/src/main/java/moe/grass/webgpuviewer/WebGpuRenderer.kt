package moe.grass.webgpuviewer

import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUComputePassDescriptor
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.helper.WebGpu
import androidx.webgpu.helper.createGpuTexture
import androidx.webgpu.helper.createWebGpu
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class WebGpuRenderer {
    private lateinit var gpu: WebGpu
    private lateinit var pipeline: GPUComputePipeline

    var tilesize = 4096

    var width: Int = 0
    var height: Int = 0

    var image_width: Int = 0
    var image_height: Int = 0

    var min_scale: Float = 0f

    class Mipmap(
        val scale: Float,
        val tiles: List<List<GPUTexture>>,
        val width: Int,
        val height: Int
    ) {
        constructor(scale: Float, tiles: List<List<GPUTexture>>) : this(
            scale,
            tiles,
            tiles[0].size,
            tiles.size
        )
    }

    var mipmaps: MutableList<Mipmap> = mutableListOf()

    var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(32)
    private lateinit var buffer: GPUBuffer

    var scale: Float = 1f
    var x: Float = 0f
    var y: Float = 0f

    private fun initPipeline(device: GPUDevice) {
        val shaderModule = device.createShaderModule(
            GPUShaderModuleDescriptor(shaderSourceWGSL = GPUShaderSourceWGSL(WebGpuRendererShader.shader))
        )

        pipeline =
            device.createComputePipeline(GPUComputePipelineDescriptor(GPUComputeState(shaderModule)))
    }

    fun createTiles(device: GPUDevice, image: Bitmap): MutableList<MutableList<GPUTexture>> {
        Log.i("webgpuviewer", "createtiles " + image.width + " " + image.height)
        val tiles: MutableList<MutableList<GPUTexture>> = mutableListOf()
        for (y in 0 until image.height step tilesize) {
            val col: MutableList<GPUTexture> = mutableListOf()
            tiles.add(col)
            for (x in 0 until image.width step tilesize) {
                Log.i("webgpuviewer", "createtiles " + x + " " + y)
                val width = min(tilesize, image.width - x)
                val height = min(tilesize, image.height - y)
                val cropped = Bitmap.createBitmap(image, x, y, width, height)
                val texture = cropped.createGpuTexture(device)
                col.add(texture)
            }
        }
        return tiles
    }

    suspend fun init(image: Bitmap, surface: Surface, width: Int, height: Int) {
        gpu = createWebGpu(surface)
        val device = gpu.device

        this.width = width
        this.height = height

        val ratiox = width.toFloat() / image.width.toFloat()
        val ratioy = height.toFloat() / image.height.toFloat()

        if (this.image_width == 0) {
            this.x = 0f
            this.y = 0f
            this.min_scale = max(0.01f, min(ratiox, ratioy))
            this.scale = this.min_scale
        }

        this.image_width = image.width
        this.image_height = image.height

        initPipeline(device)

        gpu.webgpuSurface.configure(
            GPUSurfaceConfiguration(
                device,
                width,
                height,
                TextureFormat.RGBA8Unorm,
                usage = TextureUsage.StorageBinding or TextureUsage.RenderAttachment
            )
        )

        buffer = device.createBuffer(
            GPUBufferDescriptor(
                size = 32,
                usage = BufferUsage.CopyDst or BufferUsage.Uniform
            )
        )
        byteBuffer.order(ByteOrder.nativeOrder())

        mipmaps.forEach { it.tiles.flatten().forEach { it.destroy() } }
        mipmaps.clear()
        mipmaps.add(Mipmap(1f, createTiles(device, image)))

        var scale = 1f
        while (image_width * scale > 512 && image_height * scale > 512) {
            scale /= 2

            if (image_width * scale < 4096 && image_height * scale < 4096) {
                val texture = device.createTexture(
                    GPUTextureDescriptor(
                        size = GPUExtent3D(
                            (image_width * scale).toInt(),
                            (image_height * scale).toInt(),
                            1
                        ),
                        usage = TextureUsage.TextureBinding or TextureUsage.StorageBinding or TextureUsage.RenderAttachment,
                        format = TextureFormat.RGBA8Unorm
                    )
                )
                render(mipmaps[mipmaps.size - 1], texture, 0f, 0f, scale)
                mipmaps.add(Mipmap(scale, listOf(listOf(texture))))
            } else {
                val im = ImageUtil.resize(
                    image,
                    (image_width * scale).toInt(),
                    (image_height * scale).toInt()
                )
                mipmaps.add(Mipmap(scale, createTiles(device, im)))
            }


        }
    }

    fun render() {
        if (!::gpu.isInitialized) {
            Log.w("webgpuviewer", "GPU not initialized")
            return
        }

        var level = floor(log2(1 / scale)).toInt()
        level = max(min(level, mipmaps.size - 1), 0)
        render(mipmaps[level], gpu.webgpuSurface.getCurrentTexture().texture, x, y, scale)
        gpu.webgpuSurface.present()
    }

    fun render(mipmap: Mipmap, dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (!::gpu.isInitialized) {
            Log.w("webgpuviewer", "GPU not initialized")
            return
        }

        val commandEncoder = gpu.device.createCommandEncoder()

        var vx = round((-x * dst.width + 0.5 * image_width) * mipmap.scale / tilesize).toInt() - 1
        vx = min(vx, mipmap.width - 2)
        vx = max(vx, 0)
        var vy = round((-y * dst.height + 0.5 * image_height) * mipmap.scale / tilesize).toInt() - 1
        vy = min(vy, mipmap.height - 2)
        vy = max(vy, 0)

        byteBuffer.putFloat(
            0,
            (0.5f / scale + x) * mipmap.scale + (vx * tilesize - (mipmap.scale * image_width) / 2f) / dst.width
        )
        byteBuffer.putFloat(
            4,
            (0.5f / scale + y) * mipmap.scale + (vy * tilesize - (mipmap.scale * image_height) / 2f) / dst.height
        )
        byteBuffer.putFloat(8, scale / mipmap.scale)
        byteBuffer.putFloat(12, tilesize.toFloat())
        byteBuffer.putFloat(16, mipmap.width.toFloat())
        byteBuffer.putFloat(20, mipmap.height.toFloat())
        gpu.device.queue.writeBuffer(buffer, 0, byteBuffer)

        val vx1 = if (mipmap.width > 1) vx + 1 else vx
        val vy1 = if (mipmap.height > 1) vy + 1 else vy

        val textures = arrayOf(
            mipmap.tiles[vy][vx],
            mipmap.tiles[vy][vx1],
            mipmap.tiles[vy1][vx],
            mipmap.tiles[vy1][vx1],
        ).mapIndexed { index, texture ->
            GPUBindGroupEntry(binding = 2 + index, textureView = texture.createView())
        }

        val pass = commandEncoder.beginComputePass(GPUComputePassDescriptor())
        pass.setPipeline(pipeline)
        pass.setBindGroup(
            0, gpu.device.createBindGroup(
                GPUBindGroupDescriptor(
                    layout = pipeline.getBindGroupLayout(0),
                    entries = arrayOf(
                        GPUBindGroupEntry(
                            binding = 0,
                            textureView = dst.createView()
                        ),
                        GPUBindGroupEntry(
                            binding = 1,
                            buffer = buffer
                        )
                    ).plus(textures)
                )
            )
        )
        pass.dispatchWorkgroups(ceil(dst.width / 16.0).toInt(), ceil(dst.height / 16.0).toInt())
        pass.end()
        gpu.device.queue.submit(arrayOf(commandEncoder.finish()))
    }

    fun cleanup() {
        if (::gpu.isInitialized) {
            gpu.close()
        }
        mipmaps.forEach { it.tiles.flatten().forEach { it.destroy() } }
        mipmaps.clear()
        buffer.destroy()
    }
}
