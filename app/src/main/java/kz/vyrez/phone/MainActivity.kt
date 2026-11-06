package kz.vyrez.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        const val REQ_VIDEO = 1
        const val REQ_BG = 2
        val PAL = intArrayOf(
            0xFF3BE07A.toInt(), 0xFF47A8FF.toInt(), 0xFFFFD24A.toInt(), 0xFFFF6BD6.toInt(),
            0xFFFF9A3C.toInt(), 0xFF3DDCC3.toInt(), 0xFFB57BFF.toInt(), 0xFFFF5F6D.toInt(),
        )
        val C_BASE = 0xFF25282D.toInt()
        val C_RAIL = 0xFF2C3036.toInt()
        val C_RAIL2 = 0xFF353A42.toInt()
        val C_LINE = 0xFF454A54.toInt()
        val C_INK = 0xFFE9ECF0.toInt()
        val C_INK3 = 0xFF9AA1AD.toInt()
        val C_AMBER = 0xFFF4B63F.toInt()
        val C_AMBER_INK = 0xFF241A04.toInt()
        val C_GREEN = 0xFF2E9E5B.toInt()
        val C_RED = 0xFFC2453B.toInt()
        val TRACK_COLOR = 0x6647A8FF
    }

    private val bg = Executors.newSingleThreadExecutor()          // нейросеть, трекинг, сборка
    private val frameExec = Executors.newSingleThreadExecutor()   // кадры для просмотра
    private val ui = Handler(Looper.getMainLooper())
    private var seg: Segmenter? = null                            // только в потоке bg
    private var uiSrc: VideoSource? = null                        // только в потоке frameExec

    private var uri: Uri? = null
    private var videoKey: String? = null
    private var nFrames = 0
    private var fps = 30f
    private var cur = 0
    private var frameBmp: Bitmap? = null
    private val objs = mutableListOf<Obj>()
    private var act = 0
    private var posMode = true
    @Volatile private var running = false
    @Volatile private var cancel = false
    @Volatile private var frameReq = 0
    @Volatile private var prevReq = 0
    @Volatile private var lastProg = 0L
    private var trackSig: String? = null
    private var trackW = 0
    private var trackH = 0
    private lateinit var maskFile: File
    private var lastOut: Uri? = null

    // настройки
    private var bgType = "green"
    private var bgColor = Color.GREEN
    private var bgImage: Uri? = null
    private var edge = 0
    private var quality = 768
    private var audio = true
    private var maxSide = 1920

    // вид
    private lateinit var fv: FrameView
    private lateinit var marks: MarksView
    private lateinit var frameTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var posBtn: Button
    private lateinit var negBtn: Button
    private lateinit var objRow: LinearLayout
    private lateinit var trackBtn: Button
    private lateinit var goBtn: Button
    private lateinit var actRow: LinearLayout
    private lateinit var progRow: LinearLayout
    private lateinit var pb: ProgressBar
    private lateinit var progTv: TextView
    private lateinit var doneRow: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ жизненный цикл
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maskFile = File(filesDir, "masks.bin")
        window.statusBarColor = C_RAIL
        window.navigationBarColor = C_RAIL
        loadSettings()
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) openVideo(u)
        } else if (i.action == Intent.ACTION_VIEW) {
            i.data?.let { openVideo(it) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (running) {
            toast("Идёт обработка. Сначала нажмите «Отмена» или дождитесь конца.")
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!running) {
            bg.execute { seg?.close(); seg = null }
            frameExec.execute { uiSrc?.close(); uiSrc = null }
        }
    }

    // ------------------------------------------------------------------ интерфейс
    private fun btn(text: String, accent: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (accent) C_AMBER_INK else C_INK)
        if (accent) setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (accent) C_AMBER else C_RAIL2)
            setStroke(dp(1), if (accent) C_AMBER else C_LINE)
        }
        stateListAnimator = null
        minHeight = 0; minimumHeight = dp(42); minWidth = 0; minimumWidth = dp(44)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        setOnClickListener { onClick() }
    }

    private fun paint(b: Button, fill: Int, stroke: Int) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(fill); setStroke(dp(2), stroke)
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(3), dp(5), dp(3))
    }

    private fun add(parent: LinearLayout, v: View, weight: Float = 0f) {
        val lp = LinearLayout.LayoutParams(if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.setMargins(dp(3), dp(2), dp(3), dp(2))
        parent.addView(v, lp)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C_BASE) }

        val top = row().apply { setBackgroundColor(C_RAIL) }
        add(top, btn("Видео") { pickVideo() })
        add(top, btn("⚙") { settingsDialog() })
        add(top, btn("⤢") { fv.resetZoom() })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, 0, 0) }
        frameTv = TextView(this).apply { setTextColor(C_AMBER); textSize = 13f; typeface = Typeface.MONOSPACE }
        statusTv = TextView(this).apply { setTextColor(C_INK3); textSize = 12f; maxLines = 2 }
        info.addView(frameTv)
        info.addView(statusTv)
        add(top, info, 1f)
        root.addView(top)

        fv = FrameView(this)
        fv.onTap = { x, y -> onTap(x, y) }
        root.addView(fv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val sr = row()
        add(sr, btn("◀•") { jumpPoint(-1) })
        add(sr, btn("◀") { setFrame(cur - 1) })
        marks = MarksView(this)
        marks.onSeek = { f -> if (f != cur) setFrame(f) }
        val mlp = LinearLayout.LayoutParams(0, dp(36), 1f)
        sr.addView(marks, mlp)
        add(sr, btn("▶") { setFrame(cur + 1) })
        add(sr, btn("•▶") { jumpPoint(1) })
        root.addView(sr)

        val mr = row()
        posBtn = btn("＋ Оставить") { setMode(true) }
        negBtn = btn("－ Убрать") { setMode(false) }
        add(mr, posBtn, 1f)
        add(mr, negBtn, 1f)
        add(mr, btn("↶") { undo() })
        add(mr, btn("Очистить") { clearFrame() })
        root.addView(mr)

        objRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(5), 0, dp(5), 0)
            addView(objRow)
        })

        actRow = row()
        trackBtn = btn("Проверить трекинг") { startJob(false) }
        goBtn = btn("Обработать", accent = true) { startJob(true) }
        add(actRow, trackBtn, 1f)
        add(actRow, goBtn, 1f)
        root.addView(actRow)

        progRow = row().apply { visibility = View.GONE; setBackgroundColor(C_RAIL) }
        pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        progTv = TextView(this).apply { setTextColor(C_INK); textSize = 13f }
        add(progRow, pb, 1f)
        add(progRow, progTv)
        add(progRow, btn("Отмена") { cancel = true; progTv.text = "отменяю…" })
        root.addView(progRow)

        doneRow = row().apply { visibility = View.GONE }
        add(doneRow, btn("▶ Открыть видео", accent = true) { openResult() }, 1f)
        add(doneRow, btn("Поделиться") { shareResult() }, 1f)
        add(doneRow, btn("✕") { doneRow.visibility = View.GONE })
        root.addView(doneRow)

        setContentView(root)
        setMode(true)
        renderObjs()
        frameTv.text = "Вырез по точкам"
        status("Выберите режим «Оставить» или «Убрать» и тапайте по кадру. Двумя пальцами: зум.")
    }

    private fun status(s: String) { statusTv.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun setMode(p: Boolean) {
        posMode = p
        paint(posBtn, if (p) C_GREEN else C_RAIL2, if (p) Color.WHITE else C_LINE)
        paint(negBtn, if (!p) C_RED else C_RAIL2, if (!p) Color.WHITE else C_LINE)
    }

    private fun renderObjs() {
        objRow.removeAllViews()
        objs.forEachIndexed { k, o ->
            val cnt = o.points.size
            val rng = if (o.from >= 0 || o.to >= 0) " [${if (o.from >= 0) o.from else 0}–${if (o.to >= 0) o.to else "конец"}]" else ""
            val b = btn("● ${k + 1}: $cnt$rng") { act = k; renderObjs(); redraw(); refreshPreview() }
            b.setTextColor(o.color)
            paint(b, if (k == act) 0xFF41464F.toInt() else C_RAIL2, if (k == act) C_AMBER else C_LINE)
            add(objRow, b)
        }
        add(objRow, btn("+ объект") { if (nFrames > 0) addObj() })
        add(objRow, btn("виден с кадра") { objs.getOrNull(act)?.let { it.from = cur; if (it.to in 0 until cur) it.to = -1; pointsChanged() } })
        add(objRow, btn("до кадра") { objs.getOrNull(act)?.let { it.to = cur; if (it.from > cur) it.from = -1; pointsChanged() } })
        add(objRow, btn("весь ролик") { objs.getOrNull(act)?.let { it.from = -1; it.to = -1; pointsChanged() } })
        add(objRow, btn("удалить объект") { deleteObj() })
        updateMarks()
    }

    private fun addObj() {
        objs.add(Obj(PAL[objs.size % PAL.size]))
        act = objs.size - 1
        renderObjs(); redraw(); refreshPreview()
    }

    private fun deleteObj() {
        if (objs.isEmpty()) return
        objs.removeAt(act)
        if (objs.isEmpty()) objs.add(Obj(PAL[0]))
        act = act.coerceAtMost(objs.size - 1)
        pointsChanged()
    }

    private fun updateMarks() {
        if (!::marks.isInitialized) return
        marks.n = nFrames
        marks.cur = cur
        val ms = ArrayList<Triple<Int, Int, Boolean>>()
        val rs = ArrayList<Triple<Int, Int, Int>>()
        objs.forEachIndexed { k, o ->
            o.points.map { it.frame }.distinct().forEach { f -> ms.add(Triple(f, o.color, k == act)) }
            if (k == act && (o.from >= 0 || o.to >= 0) && nFrames > 0) {
                rs.add(Triple(if (o.from >= 0) o.from else 0, if (o.to >= 0) o.to else nFrames - 1, o.color))
            }
        }
        marks.marks = ms
        marks.ranges = rs
        marks.invalidate()
    }

    private fun redraw() {
        fv.points = objs.flatMapIndexed { k, o ->
            o.points.filter { it.frame == cur }.map { DrawPt(it.x, it.y, it.pos, o.color, k == act) }
        }
        fv.invalidate()
    }

    private fun timecode(f: Int): String {
        val t = f / fps
        val m = (t / 60).toInt()
        return String.format(Locale.US, "%02d:%05.2f", m, t - m * 60)
    }

    // ------------------------------------------------------------------ видео и кадры
    private fun pickVideo() {
        if (running) { toast("Идёт обработка"); return }
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(i, REQ_VIDEO)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_VIDEO)
        }
    }

    private fun pickBg() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, REQ_BG) } catch (_: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_BG)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_VIDEO) openVideo(u)
        if (requestCode == REQ_BG) {
            bgImage = u; bgType = "image"; saveSettings()
            toast("Фон-картинка выбрана")
        }
    }

    private fun displayName(u: Uri): String = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun openVideo(u: Uri) {
        if (running) { toast("Идёт обработка"); return }
        status("Открываю видео…")
        frameExec.execute {
            try {
                val vs = VideoSource(this, u)
                val name = displayName(u)
                uiSrc?.close()
                uiSrc = vs
                val n = vs.frameCount
                val f = vs.fps
                val key = "$name:$n:${vs.width}x${vs.height}"
                ui.post {
                    uri = u; nFrames = n; fps = f; videoKey = key
                    trackSig = null
                    frameBmp = null
                    fv.bitmap = null; fv.trackOverlay = null; fv.previewOverlay = null
                    doneRow.visibility = View.GONE
                    objs.clear(); act = 0
                    val restored = restorePoints()
                    if (objs.isEmpty()) objs.add(Obj(PAL[0]))
                    fv.resetZoom()
                    renderObjs()
                    status(if (restored) "Точки с прошлого раза восстановлены." else
                        "Поставьте зелёную точку на то, что оставить. Красной уберите лишнее.")
                    setFrame(0)
                }
            } catch (e: Exception) {
                ui.post { status(""); toast("Не удалось открыть видео: ${e.message}") }
            }
        }
    }

    private fun setFrame(i: Int) {
        if (nFrames == 0) return
        cur = i.coerceIn(0, nFrames - 1)
        frameTv.text = "кадр $cur / ${nFrames - 1}   ${timecode(cur)}"
        updateMarks()
        redraw()
        val req = ++frameReq
        val idx = cur
        val showTrack = trackSig != null && !running && maskFile.exists()
        val tw = trackW
        val th = trackH
        frameExec.execute {
            if (req != frameReq) return@execute
            val b = uiSrc?.frame(idx)
            var ov: Bitmap? = null
            if (showTrack && tw > 0 && th > 0) {
                try {
                    val m = ByteArray(tw * th)
                    RandomAccessFile(maskFile, "r").use { raf -> raf.seek(idx.toLong() * tw * th); raf.readFully(m) }
                    ov = Masks.overlay(m, tw, th, TRACK_COLOR)
                } catch (_: Exception) {
                }
            }
            ui.post {
                if (req == frameReq) {
                    frameBmp = b
                    fv.bitmap = b
                    fv.trackOverlay = ov
                    fv.previewOverlay = null
                    fv.invalidate()
                    refreshPreview()
                }
            }
        }
    }

    private fun jumpPoint(dir: Int) {
        val fs = objs.flatMap { o -> o.points.map { it.frame } }.distinct().sorted()
        val t = if (dir > 0) fs.firstOrNull { it > cur } else fs.lastOrNull { it < cur }
        if (t == null) toast("Больше кадров с точками нет") else setFrame(t)
    }

    // ------------------------------------------------------------------ точки
    private fun onTap(x: Float, y: Float) {
        if (running || nFrames == 0) return
        val o = objs.getOrNull(act) ?: return
        o.points.add(Pt(cur, x, y, posMode))
        pointsChanged()
    }

    private fun undo() {
        val o = objs.getOrNull(act) ?: return
        val i = o.points.indexOfLast { it.frame == cur }
        if (i < 0) { toast("На этом кадре у объекта ${act + 1} точек нет"); return }
        o.points.removeAt(i)
        pointsChanged()
    }

    private fun clearFrame() {
        val o = objs.getOrNull(act) ?: return
        o.points.removeAll { it.frame == cur }
        pointsChanged()
    }

    private fun pointsChanged() {
        savePoints()
        renderObjs()
        redraw()
        refreshPreview()
        if (trackSig != null && trackSig != signature()) status("Точки изменены: снова нажмите «Проверить трекинг».")
    }

    private fun signature(): String =
        objs.filter { o -> o.points.any { it.pos } }.joinToString("|") { o ->
            "${o.from},${o.to}:" + o.points.joinToString(";") { "${it.frame},${it.x},${it.y},${it.pos}" }
        } + "#$quality"

    /** Быстрый предпросмотр маски активного объекта на текущем кадре (зелёная заливка). */
    private fun refreshPreview() {
        if (running) return
        val o = objs.getOrNull(act) ?: return
        val b = frameBmp ?: return
        val pts = o.points.filter { it.frame == cur }
        if (pts.none { it.pos }) {
            if (fv.previewOverlay != null) { fv.previewOverlay = null; fv.invalidate() }
            return
        }
        val req = ++prevReq
        val color = (o.color and 0x00FFFFFF) or 0x77000000
        status("считаю маску…")
        bg.execute {
            if (req != prevReq) return@execute
            try {
                val s = seg ?: Segmenter(this).also { seg = it }
                val sc = minOf(1f, 640f / max(b.width, b.height))
                val w = max(16, (b.width * sc).roundToInt())
                val h = max(16, (b.height * sc).roundToInt())
                val src = if (b.config != Bitmap.Config.ARGB_8888) b.copy(Bitmap.Config.ARGB_8888, false) else b
                val small = Bitmap.createScaledBitmap(src, w, h, true)
                val img = s.image(small)
                val m = s.objectMask(img, w, h, pts.filter { it.pos }.map { PointF(it.x, it.y) },
                    pts.filter { !it.pos }.map { PointF(it.x, it.y) })
                try { img.close() } catch (_: Throwable) {}
                val ov = m?.let { Masks.overlay(it, w, h, color) }
                ui.post {
                    if (req == prevReq) {
                        fv.previewOverlay = ov
                        fv.invalidate()
                        status(if (m == null) "Под точкой ничего не нашлось, поставьте точку точнее." else "")
                    }
                }
            } catch (e: Throwable) {
                ui.post { status(""); toast("Ошибка нейросети: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ обработка
    private fun postProgress(label: String, d: Int, t: Int) {
        val now = System.currentTimeMillis()
        if (now - lastProg < 150 && d < t) return
        lastProg = now
        ui.post {
            pb.progress = (d * 1000L / max(1, t)).toInt()
            progTv.text = "$label ${d * 100 / max(1, t)}%"
        }
    }

    private fun startJob(render: Boolean) {
        val u = uri
        if (u == null) { toast("Сначала выберите видео"); return }
        if (running) return
        val list = objs.filter { o -> o.points.any { it.pos } }.map { it.copyDeep() }
        if (list.isEmpty()) { toast("Поставьте хотя бы одну зелёную точку на том, что нужно оставить"); return }
        val sig = signature()
        val reuse = trackSig == sig && maskFile.exists() && trackW > 0
        val opts = RenderOpts(bgType, bgColor, bgImage, edge, audio, maxSide)
        val q = quality
        var tw = trackW
        var th = trackH
        running = true
        cancel = false
        prevReq++
        if (!reuse) trackSig = null
        fv.trackOverlay = null; fv.previewOverlay = null; fv.invalidate()
        actRow.visibility = View.GONE
        doneRow.visibility = View.GONE
        progRow.visibility = View.VISIBLE
        pb.progress = 0
        progTv.text = "запуск…"
        status(if (render) "Не сворачивайте приложение, пока идёт обработка." else "Трекинг по всему видео…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bg.execute {
            var vs: VideoSource? = null
            try {
                vs = VideoSource(this, u)
                val s = seg ?: Segmenter(this).also { seg = it }
                if (!reuse) {
                    val t = Tracker(s, vs, list, maskFile, q, { d, tot -> postProgress("Трекинг", d, tot) }, { cancel })
                    tw = t.sw; th = t.sh
                    t.run()
                    val w2 = tw
                    val h2 = th
                    ui.post { trackSig = sig; trackW = w2; trackH = h2 }
                }
                if (render) {
                    val tmp = File(cacheDir, "render.mp4")
                    Renderer(this, vs, u, maskFile, tw, th, opts, tmp, { d, tot -> postProgress("Сборка", d, tot) }, { cancel }).run()
                    val saved = saveToGallery(tmp)
                    tmp.delete()
                    ui.post {
                        lastOut = saved
                        doneRow.visibility = View.VISIBLE
                        toast("Готово! Сохранено в Галерею: Movies/Vyrez")
                    }
                } else {
                    ui.post {
                        toast("Трекинг готов. Листайте кадры: синяя маска = результат. Где ошибка, добавьте точки на этом кадре и проверьте снова.")
                    }
                }
            } catch (e: CancelledException) {
                ui.post { toast("Отменено") }
            } catch (e: Throwable) {
                ui.post { toast("Ошибка: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                vs?.close()
                ui.post {
                    running = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    progRow.visibility = View.GONE
                    actRow.visibility = View.VISIBLE
                    status("")
                    setFrame(cur)
                }
            }
        }
    }

    private fun saveToGallery(f: File): Uri {
        val name = "cut_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Vyrez")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("не удалось создать файл в галерее")
        (contentResolver.openOutputStream(out) ?: throw IOException("нет доступа к галерее")).use { os ->
            f.inputStream().use { it.copyTo(os) }
        }
        cv.clear()
        cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(out, cv, null, null)
        return out
    }

    private fun openResult() {
        val u = lastOut ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            toast("Откройте Галерею → Movies/Vyrez")
        }
    }

    private fun shareResult() {
        val u = lastOut ?: return
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Поделиться"))
    }

    // ------------------------------------------------------------------ настройки
    private fun settingsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(C_INK); textSize = 15f; setPadding(0, dp(14), 0, dp(4)) }
        fun radio(t: String, id: Int, on: Boolean) = RadioButton(this).apply { this.id = id; text = t; setTextColor(C_INK); isChecked = on }

        box.addView(label("Фон"))
        val types = listOf("green" to "Зелёный (для хромакея)", "color" to "Свой цвет", "blur" to "Размытый исходный", "image" to "Картинка")
        val rg = RadioGroup(this)
        types.forEachIndexed { i, (k, t) -> rg.addView(radio(t, 100 + i, bgType == k)) }
        box.addView(rg)
        val hex = EditText(this).apply {
            setText(String.format("#%06X", bgColor and 0xFFFFFF)); setTextColor(C_INK); hint = "#RRGGBB"
        }
        box.addView(hex)
        box.addView(btn(if (bgImage == null) "Выбрать картинку фона" else "Картинка выбрана ✓ (сменить)") { pickBg() })

        val edgeLbl = label("Край маски: ${edgeText(edge)}")
        box.addView(edgeLbl)
        val sb = SeekBar(this).apply {
            max = 6; progress = edge + 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { edgeLbl.text = "Край маски: ${edgeText(p - 3)}" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(sb)

        box.addView(label("Точность трекинга"))
        val qs = listOf(512 to "Быстро", 768 to "Обычно", 1024 to "Точнее (медленнее)")
        val qg = RadioGroup(this)
        qs.forEachIndexed { i, (v, t) -> qg.addView(radio(t, 200 + i, quality == v)) }
        box.addView(qg)

        box.addView(label("Размер готового видео"))
        val ss = listOf(1280 to "720p", 1920 to "1080p", 3840 to "Как в оригинале (до 4K, медленно)")
        val sg = RadioGroup(this)
        ss.forEachIndexed { i, (v, t) -> sg.addView(radio(t, 300 + i, maxSide == v)) }
        box.addView(sg)

        val au = CheckBox(this).apply { text = "Сохранить звук"; isChecked = audio; setTextColor(C_INK) }
        box.addView(au)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Готово") { _, _ ->
                types.getOrNull(rg.checkedRadioButtonId - 100)?.let { bgType = it.first }
                try { bgColor = Color.parseColor(hex.text.toString().trim()) } catch (_: Exception) { toast("Цвет не понял, нужен вид #00FF00") }
                edge = sb.progress - 3
                qs.getOrNull(qg.checkedRadioButtonId - 200)?.let { quality = it.first }
                ss.getOrNull(sg.checkedRadioButtonId - 300)?.let { maxSide = it.first }
                audio = au.isChecked
                if (bgType == "image" && bgImage == null) toast("Выберите картинку фона")
                saveSettings()
                if (trackSig != null && trackSig != signature()) status("Точность изменена: трекинг посчитается заново.")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun edgeText(e: Int) = when {
        e < 0 -> "уже на ${-e}"
        e > 0 -> "шире на $e"
        else -> "как есть"
    }

    private fun prefs() = getSharedPreferences("vyrez", MODE_PRIVATE)

    private fun saveSettings() {
        prefs().edit()
            .putString("bgType", bgType).putInt("bgColor", bgColor).putInt("edge", edge)
            .putInt("quality", quality).putInt("maxSide", maxSide).putBoolean("audio", audio)
            .apply()
    }

    private fun loadSettings() {
        val p = prefs()
        bgType = p.getString("bgType", "green") ?: "green"
        if (bgType == "image") bgType = "green"            // картинку надо выбрать заново
        bgColor = p.getInt("bgColor", Color.GREEN)
        edge = p.getInt("edge", 0)
        quality = p.getInt("quality", 768)
        maxSide = p.getInt("maxSide", 1920)
        audio = p.getBoolean("audio", true)
    }

    private fun savePoints() {
        val k = videoKey ?: return
        val arr = JSONArray()
        for (o in objs) {
            val jo = JSONObject()
            jo.put("c", o.color); jo.put("f", o.from); jo.put("t", o.to)
            val ps = JSONArray()
            for (p in o.points) ps.put(JSONArray().put(p.frame).put(p.x.toDouble()).put(p.y.toDouble()).put(if (p.pos) 1 else 0))
            jo.put("p", ps)
            arr.put(jo)
        }
        getSharedPreferences("points", MODE_PRIVATE).edit().putString(k, arr.toString()).apply()
    }

    private fun restorePoints(): Boolean {
        val k = videoKey ?: return false
        val s = getSharedPreferences("points", MODE_PRIVATE).getString(k, null) ?: return false
        return try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val jo = arr.getJSONObject(i)
                val o = Obj(jo.getInt("c"))
                o.from = jo.optInt("f", -1); o.to = jo.optInt("t", -1)
                val ps = jo.getJSONArray("p")
                for (j in 0 until ps.length()) {
                    val a = ps.getJSONArray(j)
                    o.points.add(Pt(a.getInt(0), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getInt(3) == 1))
                }
                objs.add(o)
            }
            objs.any { it.points.isNotEmpty() }
        } catch (_: Exception) {
            objs.clear()
            false
        }
    }
}
