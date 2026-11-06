package kz.vyrez.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RenderOpts(
    val bgType: String,      // green | color | blur | image
    val bgColor: Int,
    val bgImage: Uri?,
    val edge: Int,           // -3..3 пикселя маски
    val audio: Boolean,
    val maxSide: Int,
)

/** Сборка: фон + исходный кадр через маску, кодирование в mp4. */
class Renderer(
    private val ctx: Context,
    private val vs: VideoSource,
    private val src: Uri,
    private val maskFile: File,
    private val sw: Int,
    private val sh: Int,
    private val o: RenderOpts,
    private val out: File,
    private val prog: (Int, Int) -> Unit,
    private val cancel: () -> Boolean,
) {
    fun run() {
        val n = vs.frameCount
        val s = min(1f, o.maxSide / max(vs.width, vs.height).toFloat())
        val w = (vs.width * s).roundToInt() and 1.inv()
        val h = (vs.height * s).roundToInt() and 1.inv()
        val dst = Rect(0, 0, w, h)
        val bgImg = if (o.bgType == "image" && o.bgImage != null) loadCover(o.bgImage, w, h) else null
        val enc = Encoder(ctx, out, w, h, vs.fps, if (o.audio) src else null)
        try {
            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val oc = Canvas(outBmp)
            val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val fc = Canvas(fg)
            val alpha = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
            val rb = alpha.rowBytes
            val abuf = ByteArray(rb * sh)
            val mask = ByteArray(sw * sh)
            val pFilter = Paint(Paint.FILTER_BITMAP_FLAG)
            val pIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            val full: Byte = 0xFF.toByte()
            val zero: Byte = 0
            val flat = when (o.bgType) {
                "green" -> Color.rgb(0, 255, 0)
                else -> o.bgColor
            }
            var done = 0
            RandomAccessFile(maskFile, "r").use { raf ->
                var f = 0
                while (f < n) {
                    if (cancel()) throw CancelledException()
                    val frames = vs.frames(f, min(vs.chunk, n - f))
                    if (frames.isEmpty()) break
                    for ((k, fr) in frames.withIndex()) {
                        val fi = f + k
                        raf.seek(fi.toLong() * sw * sh)
                        raf.readFully(mask)
                        val m = Masks.morph(mask, sw, sh, o.edge)
                        for (y in 0 until sh) {
                            val r = y * sw
                            val q = y * rb
                            for (x in 0 until sw) abuf[q + x] = if (m[r + x].toInt() != 0) full else zero
                        }
                        alpha.copyPixelsFromBuffer(ByteBuffer.wrap(abuf))

                        when {
                            o.bgType == "image" && bgImg != null -> oc.drawBitmap(bgImg, 0f, 0f, null)
                            o.bgType == "blur" -> {
                                val a = Bitmap.createScaledBitmap(fr, max(1, w / 8), max(1, h / 8), true)
                                val b = Bitmap.createScaledBitmap(a, max(1, w / 32), max(1, h / 32), true)
                                oc.drawBitmap(b, null, dst, pFilter)
                                if (a !== fr) a.recycle()
                                if (b !== a) b.recycle()
                            }
                            else -> oc.drawColor(flat)
                        }
                        fg.eraseColor(Color.TRANSPARENT)
                        fc.drawBitmap(fr, null, dst, pFilter)
                        fc.drawBitmap(alpha, null, dst, pIn)
                        oc.drawBitmap(fg, 0f, 0f, null)
                        enc.write(outBmp)
                        fr.recycle()
                        prog(++done, n)
                        if (cancel()) {
                            for (q in k + 1 until frames.size) frames[q].recycle()
                            throw CancelledException()
                        }
                    }
                    f += frames.size
                }
            }
            enc.finish()
        } catch (e: Throwable) {
            enc.abort()
            throw e
        }
    }

    private fun loadCover(u: Uri, w: Int, h: Int): Bitmap? = try {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bo) }
        var sample = 1
        while (bo.outWidth / (sample * 2) >= w && bo.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val img = ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (img == null) null else {
            val sc = max(w / img.width.toFloat(), h / img.height.toFloat())
            val cw = (w / sc).roundToInt().coerceAtMost(img.width)
            val ch = (h / sc).roundToInt().coerceAtMost(img.height)
            val x0 = (img.width - cw) / 2
            val y0 = (img.height - ch) / 2
            val outB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(outB).drawBitmap(img, Rect(x0, y0, x0 + cw, y0 + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            img.recycle()
            outB
        }
    } catch (_: Exception) {
        null
    }
}
