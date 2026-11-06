package kz.vyrez.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.Closeable

/** Чтение кадров видео. Не потокобезопасно: на каждый поток свой экземпляр. */
class VideoSource(ctx: Context, uri: Uri) : Closeable {
    private val mmr = MediaMetadataRetriever()
    private val rawW: Int
    private val rawH: Int
    private val rotation: Int
    val frameCount: Int
    val durationMs: Long
    val fps: Float
    val width: Int
    val height: Int
    /** Сколько полных кадров брать за раз (чтобы не забить память на 4K). */
    val chunk: Int

    init {
        mmr.setDataSource(ctx, uri)
        rawW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        rawH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        frameCount = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (frameCount <= 0 || rawW <= 0 || rawH <= 0) {
            mmr.release()
            throw IllegalArgumentException("не удалось прочитать видео")
        }
        fps = if (durationMs > 0) (frameCount * 1000f / durationMs).coerceIn(1f, 240f) else 30f
        val swap = rotation == 90 || rotation == 270
        width = if (swap) rawH else rawW
        height = if (swap) rawW else rawH
        chunk = (200_000_000L / (width.toLong() * height * 4)).toInt().coerceIn(3, 24)
    }

    private fun fix(b: Bitmap): Bitmap {
        if ((rotation == 90 || rotation == 270) && rawW != rawH && b.width == rawW && b.height == rawH) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            return r
        }
        return b
    }

    fun frame(i: Int): Bitmap? = try {
        mmr.getFrameAtIndex(i.coerceIn(0, frameCount - 1))?.let { fix(it) }
    } catch (e: Exception) {
        null
    }

    fun frames(start: Int, count: Int): List<Bitmap> {
        val c = minOf(count, frameCount - start)
        if (c <= 0 || start < 0) return emptyList()
        return try {
            mmr.getFramesAtIndex(start, c).map { fix(it) }
        } catch (e: Exception) {
            val out = ArrayList<Bitmap>()
            for (i in start until start + c) {
                val b = try { mmr.getFrameAtIndex(i) } catch (_: Exception) { null } ?: break
                out.add(fix(b))
            }
            out
        }
    }

    override fun close() {
        try { mmr.release() } catch (_: Exception) {}
    }
}
