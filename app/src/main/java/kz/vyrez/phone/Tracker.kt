package kz.vyrez.phone

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mediapipe.framework.image.MPImage
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ведёт объекты по видео. На каждом кадре нейросеть выделяет предмет под точками, точки затем
 * сдвигаются вслед за центром маски (с учётом скорости) и «прилипают» к маске, если соскочили.
 * Кадры, где вы ставили точки, задают точки заново. До первого кадра с точками идёт проход назад.
 * Результат: файл масок, sw*sh байт (0/1) на кадр.
 */
class Tracker(
    private val seg: Segmenter,
    private val vs: VideoSource,
    objs: List<Obj>,
    private val out: File,
    side: Int,
    private val prog: (Int, Int) -> Unit,
    private val cancel: () -> Boolean,
) {
    private class St {
        var pos: List<PointF> = emptyList()
        var neg: List<PointF> = emptyList()
        var last: PointF? = null
        var on = false
    }

    private val objs = objs.filter { o -> o.points.any { it.pos } }
    private val n = vs.frameCount
    val sw: Int
    val sh: Int
    private val byFrame: List<Map<Int, List<Pt>>> = this.objs.map { o -> o.points.groupBy { it.frame.coerceIn(0, n - 1) } }
    private val first: IntArray = this.objs.map { o ->
        o.points.filter { it.pos }.minOf { it.frame }.coerceIn(0, n - 1)
    }.toIntArray()

    init {
        val s = min(1f, side / max(vs.width, vs.height).toFloat())
        sw = max(16, (vs.width * s).roundToInt())
        sh = max(16, (vs.height * s).roundToInt())
    }

    private fun applyKey(j: Int, f: Int, s: St) {
        val kp = byFrame[j][f] ?: return
        val pos = kp.filter { it.pos }
        val neg = kp.filter { !it.pos }
        if (pos.isNotEmpty()) {
            s.pos = pos.map { PointF(it.x, it.y) }
            s.last = null
            s.on = true
        }
        if (neg.isNotEmpty()) s.neg = neg.map { PointF(it.x, it.y) }
    }

    private fun step(img: MPImage, s: St, comb: ByteArray) {
        val m = seg.objectMask(img, sw, sh, s.pos, s.neg) ?: return     // потеряли: точки остаются на месте
        for (i in comb.indices) if (m[i].toInt() != 0) comb[i] = 1
        val c = Masks.centroid(m, sw, sh) ?: return
        var pts = s.pos.map { Masks.snap(m, sw, sh, it, c) }
        val l = s.last
        if (l != null) {
            val dx = (c.x - l.x) * 0.8f
            val dy = (c.y - l.y) * 0.8f
            pts = pts.map { PointF((it.x + dx).coerceIn(0.002f, 0.998f), (it.y + dy).coerceIn(0.002f, 0.998f)) }
        }
        s.pos = pts
        s.last = c
    }

    private fun prep(fr: Bitmap): Bitmap {
        val b = if (fr.config != Bitmap.Config.ARGB_8888) fr.copy(Bitmap.Config.ARGB_8888, false) else fr
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        if (b !== fr && b !== s) b.recycle()
        return s
    }

    private fun one(fr: Bitmap, fi: Int, comb: ByteArray, js: List<Int>, st: Array<St>, skipFrom: Boolean) {
        val small = prep(fr)
        val img = seg.image(small)
        try {
            for (j in js) {
                if (skipFrom && fi >= first[j]) continue
                applyKey(j, fi, st[j])
                if (st[j].on && objs[j].inRange(fi)) step(img, st[j], comb)
            }
        } finally {
            try { img.close() } catch (_: Throwable) {}
            if (small !== fr) small.recycle()
            fr.recycle()
        }
    }

    fun run() {
        if (objs.isEmpty()) throw IllegalStateException("нет ни одной зелёной точки")
        val fs = sw * sh
        val minStart = first.min()
        val maxFirst = first.max()
        val total = (n - minStart) + maxFirst
        var done = 0
        val all = objs.indices.toList()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.setLength(n.toLong() * fs)

            // вперёд
            val st = Array(objs.size) { St() }
            var f = minStart
            while (f < n) {
                if (cancel()) throw CancelledException()
                val frames = vs.frames(f, min(vs.chunk, n - f))
                if (frames.isEmpty()) break
                for ((k, fr) in frames.withIndex()) {
                    val fi = f + k
                    val comb = ByteArray(fs)
                    one(fr, fi, comb, all, st, false)
                    raf.seek(fi.toLong() * fs)
                    raf.write(comb)
                    prog(++done, total)
                    if (cancel()) throw CancelledException()
                }
                f += frames.size
            }

            // назад: кадры до первой точки объекта
            val back = objs.indices.filter { first[it] > 0 }
            if (back.isNotEmpty()) {
                val st2 = Array(objs.size) { St() }
                for (j in back) applyKey(j, first[j], st2[j])
                var end = maxFirst - 1
                while (end >= 0) {
                    if (cancel()) throw CancelledException()
                    val start = max(0, end - vs.chunk + 1)
                    val frames = vs.frames(start, end - start + 1)
                    if (frames.isEmpty()) break
                    for (k in frames.indices.reversed()) {
                        val fi = start + k
                        val comb = ByteArray(fs)
                        raf.seek(fi.toLong() * fs)
                        raf.readFully(comb)
                        one(frames[k], fi, comb, back, st2, true)
                        raf.seek(fi.toLong() * fs)
                        raf.write(comb)
                        prog(++done, total)
                        if (cancel()) {
                            for (q in 0 until k) frames[q].recycle()
                            throw CancelledException()
                        }
                    }
                    end = start - 1
                }
            }
        }
    }
}
