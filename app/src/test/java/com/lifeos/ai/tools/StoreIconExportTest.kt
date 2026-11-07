package com.lifeos.ai.tools

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.lifeos.ai.R
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Renders the real adaptive-icon vector layers (with Robolectric's native Skia graphics) into:
 *  - `app/src/main/ic_launcher-playstore.png` — 512x512 Google Play listing icon (full square;
 *    Play applies its own corner mask), and
 *  - `<iconPreviewDir>/icon-preview.png` — circle / squircle / themed previews (optional).
 *
 * Skipped by default. Run with:
 * ./gradlew :app:testDebugUnitTest --tests "*StoreIconExportTest" -PexportIcons=true
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreIconExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun drawable(id: Int): Drawable = ContextCompat.getDrawable(context, id)!!.mutate()

    /**
     * Draws background + foreground so that the central [visibleDp] of the 108dp adaptive canvas
     * fills a [size]x[size] square (launchers show the central 72dp).
     */
    private fun drawLayers(canvas: Canvas, size: Int, visibleDp: Float, monochromeTint: Int? = null, monoBg: Int? = null) {
        val full = Math.round(size * 108f / visibleDp)
        val offset = (size - full) / 2
        if (monochromeTint != null) {
            canvas.drawColor(monoBg ?: Color.BLACK)
            drawable(R.drawable.ic_launcher_monochrome).apply {
                setTint(monochromeTint)
                setBounds(offset, offset, offset + full, offset + full)
                draw(canvas)
            }
            return
        }
        listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach { id ->
            drawable(id).apply {
                setBounds(offset, offset, offset + full, offset + full)
                draw(canvas)
            }
        }
    }

    @Test
    fun exportStoreIcon() {
        assumeTrue("Set -PexportIcons=true to export icons", System.getProperty("lifeos.exportIcons") == "true")

        // 1) Play Store icon: show slightly more than the launcher's 72dp so the mark sits within
        //    Play's keyline area once the store applies its rounded mask.
        val play = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        drawLayers(Canvas(play), 512, visibleDp = 84f)
        val playFile = File("src/main/ic_launcher-playstore.png")
        writePng(play, playFile)
        println("Wrote ${playFile.absolutePath}")

        // 2) Preview sheet (optional output dir).
        val dir = System.getProperty("lifeos.iconPreviewDir").orEmpty()
        if (dir.isNotBlank()) {
            val tile = 256
            val gap = 48
            val width = gap + 3 * (tile + gap)
            val height = tile + 2 * gap
            val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            c.drawColor(Color.parseColor("#2A2F3A"))
            val masks: List<(RectF) -> Path> = listOf(
                { r -> Path().apply { addOval(r, Path.Direction.CW) } },
                { r -> Path().apply { addRoundRect(r, r.width() * 0.3f, r.height() * 0.3f, Path.Direction.CW) } },
                { r -> Path().apply { addOval(r, Path.Direction.CW) } },
            )
            masks.forEachIndexed { i, mask ->
                val left = (gap + i * (tile + gap)).toFloat()
                val rect = RectF(left, gap.toFloat(), left + tile, (gap + tile).toFloat())
                val layer = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
                val lc = Canvas(layer)
                if (i == 2) drawLayers(lc, tile, 72f, monochromeTint = Color.parseColor("#A8C8FF"), monoBg = Color.parseColor("#14273F"))
                else drawLayers(lc, tile, 72f)
                c.save()
                c.clipPath(mask(rect))
                c.drawBitmap(layer, left, gap.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                c.restore()
            }
            val previewFile = File(dir, "icon-preview.png")
            writePng(sheet, previewFile)
            println("Wrote ${previewFile.absolutePath}")
        }
    }

    /**
     * Minimal RGBA PNG encoder (java.util.zip only) so the output doesn't depend on
     * Bitmap.compress support in the test runtime.
     */
    private fun writePng(bitmap: Bitmap, file: File) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h) // non-premultiplied ARGB
        val raw = ByteArrayOutputStream(h * (1 + w * 4))
        for (y in 0 until h) {
            raw.write(0) // filter: none
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                raw.write(p shr 16 and 0xFF); raw.write(p shr 8 and 0xFF); raw.write(p and 0xFF); raw.write(p ushr 24)
            }
        }
        val idat = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(9)).use { it.write(raw.toByteArray()) }
        }.toByteArray()
        val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h)
            .put(8).put(6).put(0).put(0).put(0).array() // 8-bit RGBA, no interlace
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
            fun chunk(type: String, data: ByteArray) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                out.writeInt(data.size)
                out.write(typeBytes)
                out.write(data)
                out.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
            }
            chunk("IHDR", ihdr)
            chunk("IDAT", idat)
            chunk("IEND", ByteArray(0))
        }
    }
}
