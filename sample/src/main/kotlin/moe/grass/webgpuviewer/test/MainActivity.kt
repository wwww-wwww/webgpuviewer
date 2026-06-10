package ca.mpreg.webgpuviewer.test

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ViewCompositionStrategy
import ca.mpreg.webgpuviewer.test.databinding.MainActivityBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stream = assets.open("ref.png")
        var bitmap = BitmapFactory.decodeStream(stream)

        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels

        binding.composeView1.apply {
            layoutParams.width = width
            layoutParams.height = height
            init(bitmap)
        }

        binding.composeView2.apply {
            layoutParams.width = width
            layoutParams.height = height
            init(bitmap)
        }
    }
}
