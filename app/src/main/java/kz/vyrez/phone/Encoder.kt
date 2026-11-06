package kz.vyrez.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** H.264 через аппаратный кодировщик телефона + звук из исходника (копируется как есть). */
class Encoder(ctx: Context, private val file: File, private val w: Int, private val h: Int, private val fps: Float, audioSrc: Uri?) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var vTrack = -1
    private var aTrack = -1
    private var started = false
    private var posted = 0
    private var written = 0
    private val info = MediaCodec.BufferInfo()
    private val dst = Rect(0, 0, w, h)
    private var ext: MediaExtractor? = null
    private var aIndex = -1
    private var aFormat: MediaFormat? = null
    private var released = false

    init {
        val bitrate = (w.toLong() * h * fps * 0.25).toLong().coerceIn(4_000_000L, 60_000_000L).toInt()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        if (audioSrc != null) {
            try {
                val e = MediaExtractor()
                e.setDataSource(ctx, audioSrc, null)
                for (i in 0 until e.trackCount) {
                    val f = e.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        aIndex = i
                        aFormat = f
                        break
                    }
                }
                if (aIndex >= 0) ext = e else e.release()
            } catch (_: Exception) {
                aFormat = null
            }
        }
    }

    fun write(b: Bitmap) {
        val c = surface.lockHardwareCanvas()
        try {
            c.drawBitmap(b, null, dst, null)
        } finally {
            surface.unlockCanvasAndPost(c)
        }
        posted++
        drain(false)
    }

    private fun drain(end: Boolean) {
        val deadline = System.currentTimeMillis() + if (end) 15_000 else 4_000
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end && written >= posted - 3) return
                if (System.currentTimeMillis() > deadline) return
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) {
                    vTrack = muxer.addTrack(codec.outputFormat)
                    val af = aFormat
                    if (af != null) aTrack = try { muxer.addTrack(af) } catch (_: Exception) { -1 }
                    muxer.start()
                    started = true
                }
            } else if (idx >= 0) {
                val buf = codec.getOutputBuffer(idx)
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buf != null && info.size > 0 && !config && started) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    info.presentationTimeUs = (written * 1_000_000.0 / fps).toLong()
                    muxer.writeSampleData(vTrack, buf, info)
                    written++
                }
                codec.releaseOutputBuffer(idx, false)
                if (eos) return
            }
        }
    }

    fun finish() {
        codec.signalEndOfInputStream()
        drain(true)
        if (!started || written == 0) {
            abort()
            throw IOException("кодировщик не выдал ни одного кадра")
        }
        val e = ext
        if (e != null && aTrack >= 0) {
            try {
                e.selectTrack(aIndex)
                e.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val buf = ByteBuffer.allocateDirect(1 shl 21)
                val ai = MediaCodec.BufferInfo()
                val maxUs = (written * 1_000_000.0 / fps).toLong()
                while (true) {
                    val sz = e.readSampleData(buf, 0)
                    if (sz < 0) break
                    val t = e.sampleTime
                    if (t > maxUs) break
                    val key = (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ai.set(0, sz, t, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(aTrack, buf, ai)
                    e.advance()
                }
            } catch (_: Exception) {
            }
        }
        var err: Exception? = null
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        try { muxer.stop() } catch (x: Exception) { err = x }
        try { muxer.release() } catch (_: Exception) {}
        try { ext?.release() } catch (_: Exception) {}
        released = true
        if (err != null) throw IOException("не удалось записать видео. Попробуйте выключить звук в настройках", err)
    }

    fun abort() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        try { if (started) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        try { ext?.release() } catch (_: Exception) {}
        file.delete()
    }
}
