package kz.vyrez.phone

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.roundToInt

/** Точка: кадр, координаты 0..1, pos=true оставить / false убрать. */
data class Pt(val frame: Int, val x: Float, val y: Float, val pos: Boolean)

/** Объект: свои точки и диапазон кадров, где он существует (-1 = без ограничения). */
class Obj(var color: Int) {
    val points = mutableListOf<Pt>()
    var from = -1
    var to = -1

    fun inRange(f: Int) = (from < 0 || f >= from) && (to < 0 || f <= to)

    fun copyDeep(): Obj = Obj(color).also { it.points.addAll(points); it.from = from; it.to = to }
}

class CancelledException : RuntimeException("Отменено")

/** Маски хранятся как ByteArray w*h со значениями 0/1. */
object Masks {
    fun idx(p: PointF, w: Int, h: Int): Int {
        val x = (p.x * (w - 1)).roundToInt().coerceIn(0, w - 1)
        val y = (p.y * (h - 1)).roundToInt().coerceIn(0, h - 1)
        return y * w + x
    }

    fun resize(src: ByteArray, sw: Int, sh: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val sy = (y.toLong() * sh / h).toInt().coerceIn(0, sh - 1)
            for (x in 0 until w) {
                val sx = (x.toLong() * sw / w).toInt().coerceIn(0, sw - 1)
                out[y * w + x] = src[sy * sw + sx]
            }
        }
        return out
    }

    fun centroid(m: ByteArray, w: Int, h: Int): PointF? {
        var sx = 0L
        var sy = 0L
        var c = 0L
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) if (m[r + x].toInt() != 0) {
                sx += x; sy += y; c++
            }
        }
        if (c == 0L) return null
        return PointF(sx.toFloat() / c / (w - 1), sy.toFloat() / c / (h - 1))
    }

    /** Если точка вне маски, переносит её на ближайший пиксель маски и чуть внутрь (к центру). */
    fun snap(m: ByteArray, w: Int, h: Int, p: PointF, c: PointF): PointF {
        val px = (p.x * (w - 1)).roundToInt().coerceIn(0, w - 1)
        val py = (p.y * (h - 1)).roundToInt().coerceIn(0, h - 1)
        if (m[py * w + px].toInt() != 0) return p
        var best = -1
        var bd = Long.MAX_VALUE
        for (y in 0 until h) {
            val r = y * w
            val dy = (y - py).toLong()
            for (x in 0 until w) if (m[r + x].toInt() != 0) {
                val dx = (x - px).toLong()
                val d = dx * dx + dy * dy
                if (d < bd) { bd = d; best = r + x }
            }
        }
        if (best < 0) return p
        val bx = (best % w).toFloat() / (w - 1)
        val by = (best / w).toFloat() / (h - 1)
        return PointF(bx + (c.x - bx) * 0.2f, by + (c.y - by) * 0.2f)
    }

    /** edge > 0 расширить маску на edge пикселей, edge < 0 сузить. */
    fun morph(m: ByteArray, w: Int, h: Int, edge: Int): ByteArray {
        if (edge == 0) return m
        val grow = edge > 0
        var a = m
        repeat(abs(edge)) {
            val b = ByteArray(w * h)
            for (y in 0 until h) {
                val y0 = if (y > 0) y - 1 else 0
                val y1 = if (y < h - 1) y + 1 else h - 1
                for (x in 0 until w) {
                    val x0 = if (x > 0) x - 1 else 0
                    val x1 = if (x < w - 1) x + 1 else w - 1
                    var v = if (grow) 0 else 1
                    loop@ for (yy in y0..y1) {
                        val r = yy * w
                        for (xx in x0..x1) {
                            val q = a[r + xx].toInt()
                            if (grow && q != 0) { v = 1; break@loop }
                            if (!grow && q == 0) { v = 0; break@loop }
                        }
                    }
                    b[y * w + x] = v.toByte()
                }
            }
            a = b
        }
        return a
    }

    fun overlay(m: ByteArray, w: Int, h: Int, color: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) if (m[i].toInt() != 0) px[i] = color
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
