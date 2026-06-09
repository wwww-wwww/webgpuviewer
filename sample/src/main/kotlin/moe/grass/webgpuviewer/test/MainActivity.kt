package ca.mpreg.webgpuviewer.test

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ViewCompositionStrategy
import ca.mpreg.webgpuviewer.WebGpuImageViewer
import ca.mpreg.webgpuviewer.test.databinding.MainActivityBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stream = assets.open("ref.png")
        val bitmap = BitmapFactory.decodeStream(stream)

        val screenWidth = resources.displayMetrics.widthPixels

        binding.composeView1.apply {
            layoutParams = layoutParams.apply { width = screenWidth }
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WebGpuImageViewer(bitmap=bitmap)
            }
        }

    }
}
