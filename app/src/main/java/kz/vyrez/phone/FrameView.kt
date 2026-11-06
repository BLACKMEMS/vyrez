package kz.vyrez.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

class DrawPt(val x: Float, val y: Float, val pos: Boolean, val color: Int, val active: Boolean)

/** Кадр с масками и точками. Тап = точка, два пальца = зум и сдвиг, один палец при зуме = сдвиг. */
class FrameView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
    var trackOverlay: Bitmap? = null
    var previewOverlay: Bitmap? = null
    var points: List<DrawPt> = emptyList()
    var hint = "Нажмите «Видео» и выберите ролик"
    var onTap: ((Float, Float) -> Unit)? = null

    private var zoom = 1f
    private var ox = 0f
    private var oy = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var multi = false
    private var moved = false
    private var lfx = 0f
    private var lfy = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val dens = ctx.resources.displayMetrics.density
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF808795.toInt(); textSize = 16 * dens; textAlign = Paint.Align.CENTER
    }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(det: ScaleGestureDetector): Boolean {
            lfx = det.focusX; lfy = det.focusY
            return true
        }

        override fun onScale(det: ScaleGestureDetector): Boolean {
            val nz = (zoom * det.scaleFactor).coerceIn(1f, 10f)
            val ef = nz / zoom
            ox = det.focusX - (lfx - ox) * ef
            oy = det.focusY - (lfy - oy) * ef
            zoom = nz
            lfx = det.focusX; lfy = det.focusY
            if (zoom <= 1.001f) { ox = 0f; oy = 0f }
            invalidate()
            return true
        }
    })

    fun resetZoom() {
        zoom = 1f; ox = 0f; oy = 0f
        invalidate()
    }

    private fun imgRect(): RectF? {
        val b = bitmap ?: return null
        if (width == 0 || height == 0) return null
        val s = min(width / b.width.toFloat(), height / b.height.toFloat())
        val bw = b.width * s
        val bh = b.height * s
        val l = (width - bw) / 2
        val t = (height - bh) / 2
        return RectF(l * zoom + ox, t * zoom + oy, (l + bw) * zoom + ox, (t + bh) * zoom + oy)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1C1E22.toInt())
        val b = bitmap
        val r = imgRect()
        if (b == null || r == null) {
            c.drawText(hint, width / 2f, height / 2f, pText)
            return
        }
        c.drawBitmap(b, null, r, pBmp)
        trackOverlay?.let { c.drawBitmap(it, null, r, pBmp) }
        previewOverlay?.let { c.drawBitmap(it, null, r, pBmp) }
        val rad = 8 * dens
        for (p in points) {
            val cx = r.left + p.x * r.width()
            val cy = r.top + p.y * r.height()
            pFill.color = if (p.pos) 0xFF3BE07A.toInt() else 0xFFFF4D4D.toInt()
            c.drawCircle(cx, cy, rad, pFill)
            pStroke.strokeWidth = (if (p.active) 2.5f else 2f) * dens
            pStroke.color = if (p.active) Color.WHITE else p.color
            c.drawCircle(cx, cy, rad, pStroke)
            pStroke.color = Color.BLACK
            pStroke.strokeWidth = 1.8f * dens
            c.drawLine(cx - rad * 0.5f, cy, cx + rad * 0.5f, cy, pStroke)
            if (p.pos) c.drawLine(cx, cy - rad * 0.5f, cx, cy + rad * 0.5f, pStroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                multi = false; moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_MOVE -> if (!multi && e.pointerCount == 1) {
                if (abs(e.x - downX) + abs(e.y - downY) > slop) moved = true
                if (moved && zoom > 1.001f) {
                    ox += e.x - lastX; oy += e.y - lastY
                    invalidate()
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!multi && !moved) {
                val r = imgRect()
                if (r != null && r.contains(e.x, e.y)) {
                    onTap?.invoke((e.x - r.left) / r.width(), (e.y - r.top) / r.height())
                }
            }
        }
        return true
    }
}

/** Шкала времени: где стоят точки (цвет объекта) и текущий кадр. Тап или протяжка = перейти. */
class MarksView(ctx: Context) : View(ctx) {
    var n = 0
    var cur = 0
    var marks: List<Triple<Int, Int, Boolean>> = emptyList()    // кадр, цвет, активный объект
    var ranges: List<Triple<Int, Int, Int>> = emptyList()       // начало, конец, цвет
    var onSeek: ((Int) -> Unit)? = null
    private val dens = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad get() = 14 * dens

    private fun xOf(f: Int): Float = pad + (width - 2 * pad) * f / (n - 1).coerceAtLeast(1).toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF25282D.toInt())
        if (n <= 1) return
        val cy = height / 2f
        p.color = 0xFF454A54.toInt()
        c.drawRect(pad, cy - 1.5f * dens, width - pad, cy + 1.5f * dens, p)
        for ((a, b, col) in ranges) {
            p.color = (col and 0x00FFFFFF) or 0x55000000
            c.drawRect(xOf(a), cy - 4 * dens, xOf(b), cy + 4 * dens, p)
        }
        for ((f, col, act) in marks) {
            p.color = col
            c.drawCircle(xOf(f), cy, (if (act) 4.5f else 3f) * dens, p)
        }
        p.color = Color.WHITE
        val x = xOf(cur)
        c.drawRect(x - 1.2f * dens, 0f, x + 1.2f * dens, height.toFloat(), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (n <= 1) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val t = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
            onSeek?.invoke((t * (n - 1)).toInt())
        }
        return true
    }
}
