package moe.grass.webgpuviewer.test

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ViewCompositionStrategy
import moe.grass.webgpuviewer.WebGpuImageViewer
import moe.grass.webgpuviewer.test.databinding.MainActivityBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stream = assets.open("8192.webp")
        val bitmap = BitmapFactory.decodeStream(stream)

        binding.composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WebGpuImageViewer(bitmap)
            }
        }
    }
}
