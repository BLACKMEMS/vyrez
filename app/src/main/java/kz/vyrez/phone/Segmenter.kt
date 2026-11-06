package kz.vyrez.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter

/**
 * Нейросеть MediaPipe «Magic Touch»: по точке на кадре выделяет предмет под ней.
 * Оставить = объединение масок зелёных точек. Убрать = из результата вычитается то, что под красной точкой.
 * Работает на видеокарте телефона, при ошибке переключается на процессор.
 */
class Segmenter(private val ctx: Context) {
    private var gpu = true
    private var seg: InteractiveSegmenter = make(true)

    private fun make(useGpu: Boolean): InteractiveSegmenter {
        val base = BaseOptions.builder()
            .setModelAssetPath("magic_touch.tflite")
            .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
            .build()
        val opts = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(base)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        return try {
            InteractiveSegmenter.createFromOptions(ctx, opts)
        } catch (e: Throwable) {
            if (!useGpu) throw e
            gpu = false
            make(false)
        }
    }

    fun image(b: Bitmap): MPImage = BitmapImageBuilder(b).build()

    private fun raw(img: MPImage, x: Float, y: Float): Triple<ByteArray, Int, Int>? {
        val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(x, y))
        val res = try {
            seg.segment(img, roi)
        } catch (e: Throwable) {
            if (!gpu) throw e
            try { seg.close() } catch (_: Throwable) {}
            gpu = false
            seg = make(false)
            seg.segment(img, roi)
        }
        val opt = res.categoryMask()
        if (!opt.isPresent) return null
        val m = opt.get()
        val w = m.width
        val h = m.height
        val bb = ByteBufferExtractor.extract(m)
        bb.rewind()
        val arr = ByteArray(w * h)
        bb.get(arr, 0, minOf(arr.size, bb.remaining()))
        return Triple(arr, w, h)
    }

    /** Маска предмета под одной точкой (0/1, размер w*h) или null. */
    fun pointMask(img: MPImage, w: Int, h: Int, x: Float, y: Float): ByteArray? {
        val r = raw(img, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) ?: return null
        var arr = r.first
        if (r.second != w || r.third != h) arr = Masks.resize(arr, r.second, r.third, w, h)
        val px = (x * (w - 1)).toInt().coerceIn(0, w - 1)
        val py = (y * (h - 1)).toInt().coerceIn(0, h - 1)
        // какое значение маски означает «выбрано»: то, что под точкой (большинство в квадрате 5x5)
        val counts = HashMap<Byte, Int>()
        for (dy in -2..2) for (dx in -2..2) {
            val xx = (px + dx).coerceIn(0, w - 1)
            val yy = (py + dy).coerceIn(0, h - 1)
            val v = arr[yy * w + xx]
            counts[v] = (counts[v] ?: 0) + 1
        }
        val lab = counts.maxByOrNull { it.value }?.key ?: return null
        // старт заливки: ближайший к точке пиксель с этим значением
        var seed = -1
        var bd = Int.MAX_VALUE
        for (dy in -2..2) for (dx in -2..2) {
            val xx = (px + dx).coerceIn(0, w - 1)
            val yy = (py + dy).coerceIn(0, h - 1)
            if (arr[yy * w + xx] == lab && dx * dx + dy * dy < bd) { bd = dx * dx + dy * dy; seed = yy * w + xx }
        }
        if (seed < 0) return null
        // берём только связную область вокруг точки
        val out = ByteArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = seed
        out[seed] = 1
        while (head < tail) {
            val i = queue[head++]
            val x0 = i % w
            val y0 = i / w
            if (x0 > 0) { val j = i - 1; if (out[j].toInt() == 0 && arr[j] == lab) { out[j] = 1; queue[tail++] = j } }
            if (x0 < w - 1) { val j = i + 1; if (out[j].toInt() == 0 && arr[j] == lab) { out[j] = 1; queue[tail++] = j } }
            if (y0 > 0) { val j = i - w; if (out[j].toInt() == 0 && arr[j] == lab) { out[j] = 1; queue[tail++] = j } }
            if (y0 < h - 1) { val j = i + w; if (out[j].toInt() == 0 && arr[j] == lab) { out[j] = 1; queue[tail++] = j } }
        }
        if (tail > w * h * 0.85) return null          // выделился весь кадр: точка «соскочила» на фон
        return out
    }

    /** Маска объекта: зелёные точки складываются, красные вычитаются. */
    fun objectMask(img: MPImage, w: Int, h: Int, pos: List<PointF>, neg: List<PointF>): ByteArray? {
        var acc: ByteArray? = null
        for (p in pos) {
            val a0 = acc
            if (a0 != null && a0[Masks.idx(p, w, h)].toInt() != 0) continue   // точка уже внутри маски
            val m = pointMask(img, w, h, p.x, p.y) ?: continue
            if (a0 == null) acc = m else for (i in a0.indices) if (m[i].toInt() != 0) a0[i] = 1
        }
        val a = acc ?: return null
        for (p in neg) {
            val m = pointMask(img, w, h, p.x, p.y) ?: continue
            if (pos.any { q -> m[Masks.idx(q, w, h)].toInt() != 0 }) continue      // это сам объект, не трогаем
            for (i in a.indices) if (m[i].toInt() != 0) a[i] = 0
        }
        return a
    }

    fun close() {
        try { seg.close() } catch (_: Throwable) {}
    }
}
