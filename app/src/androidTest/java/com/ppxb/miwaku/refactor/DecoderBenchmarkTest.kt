package com.ppxb.miwaku.refactor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.size.Size
import com.ppxb.miwaku.ui.screen.reader.ScrambleDecoder
import com.ppxb.miwaku.ui.screen.reader.ScrambleTransformation
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated decoder comparison, not a whole-app speed claim. */
@RunWith(AndroidJUnit4::class)
class DecoderBenchmarkTest {
    @Test fun compareOriginalBitmapPipelineWithStripRegions() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = TestLibraryContext(base)
        val report = JSONArray()
        try {
            for (height in listOf(1080, 16_000)) for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
                val file = File(context.cacheDir, "input-" + height + "-" + format.name)
                val source = Bitmap.createBitmap(720, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(source)
                val paint = Paint()
                for (y in 0 until height step 80) {
                    paint.color = Color.rgb((y * 3) % 256, (y * 7 + 80) % 256, (y * 11 + 30) % 256)
                    canvas.drawRect(0f, y.toFloat(), 720f, (y + 80).toFloat(), paint)
                }
                file.outputStream().use { source.compress(format, 90, it) }
                source.recycle()
                val oldTimes = mutableListOf<Double>()
                val newTimes = mutableListOf<Double>()
                var originalBitmapBytes = 0L
                var newBitmapBytes = 0L
                var strategy = ""
                repeat(12) { iteration ->
                    var start = System.nanoTime()
                    val input = checkNotNull(BitmapFactory.decodeFile(file.path))
                    val output = ScrambleTransformation(10).transform(input, Size.ORIGINAL)
                    val oldElapsed = (System.nanoTime() - start) / 1_000_000.0
                    originalBitmapBytes = input.allocationByteCount.toLong() + output.allocationByteCount
                    val width = output.width
                    val outHeight = output.height
                    input.recycle(); output.recycle()
                    start = System.nanoTime()
                    val decoder = ScrambleDecoder(file, 10, 1080)
                    val decoded = (decoder.decode().image as BitmapImage).bitmap
                    newBitmapBytes = decoder.peakBitmapBytes
                    strategy = decoder.strategy
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    assertEquals(width, decoded.width)
                    assertEquals(outHeight, decoded.height)
                    decoded.recycle()
                    if (iteration >= 2) { oldTimes += oldElapsed; newTimes += elapsed }
                }
                oldTimes.sort(); newTimes.sort()
                report.put(JSONObject().put("format", format.name).put("width", 720).put("height", height)
                    .put("samples", oldTimes.size).put("oldP50Ms", oldTimes[oldTimes.size / 2])
                    .put("newP50Ms", newTimes[newTimes.size / 2]).put("oldP95Ms", oldTimes.last())
                    .put("newP95Ms", newTimes.last()).put("oldBitmapBytes", originalBitmapBytes).put("newBitmapBytes", newBitmapBytes)
                    .put("strategy", strategy).put("heapLimitBytes", Runtime.getRuntime().maxMemory()))
            }
            File(base.getExternalFilesDir(null), "decode-benchmark.json").writeText(report.toString(2))
        } finally { context.recycle() }
    }
}
