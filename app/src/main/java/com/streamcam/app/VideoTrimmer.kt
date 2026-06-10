package com.streamcam.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoTrimmer {

    private const val TAG = "VideoTrimmer"
    private const val TIMEOUT_US = 10_000L

    fun trim(
        inputPath: String,
        outputPath: String,
        startSec: Float,
        endSec: Float,
        onProgress: (Int) -> Unit,
    ): String? {
        val startUs = (startSec * 1_000_000).toLong()
        val endUs = (endSec * 1_000_000).toLong()

        Log.i(TAG, "trim: input=$inputPath start=${startUs}us end=${endUs}us")

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackList = mutableListOf<Int>()
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackList.add(i)
                    Log.i(TAG, "Selected track $i: $mime")
                }
            }
            if (trackList.isEmpty()) { extractor.release(); return null }

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            var firstVideoTrack = -1

            for (ti in trackList) {
                val fmt = extractor.getTrackFormat(ti)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (firstVideoTrack < 0) firstVideoTrack = ti
                    val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        fmt.getInteger(MediaFormat.KEY_ROTATION)
                    } else {
                        0
                    }
                    Log.i(TAG, "Video track $ti rotation: $rotation width=${fmt.getInteger(MediaFormat.KEY_WIDTH)} height=${fmt.getInteger(MediaFormat.KEY_HEIGHT)}")
                }
                val mi = muxer.addTrack(fmt)
                trackMap[ti] = mi
            }
            muxer.start()

            // Write codec config data before any media samples
            for (ti in trackList) {
                val fmt = extractor.getTrackFormat(ti)
                val mi = trackMap[ti] ?: continue
                val csd0 = fmt.getByteBuffer("csd-0")
                val csd1 = fmt.getByteBuffer("csd-1")
                if (csd0 != null) {
                    val bufInfo = MediaCodec.BufferInfo()
                    bufInfo.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    bufInfo.presentationTimeUs = 0
                    bufInfo.size = csd0.remaining()
                    muxer.writeSampleData(mi, csd0, bufInfo)
                    Log.i(TAG, "Wrote csd-0 for track $ti (${bufInfo.size} bytes)")
                }
                if (csd1 != null) {
                    val bufInfo = MediaCodec.BufferInfo()
                    bufInfo.flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    bufInfo.presentationTimeUs = 0
                    bufInfo.size = csd1.remaining()
                    muxer.writeSampleData(mi, csd1, bufInfo)
                    Log.i(TAG, "Wrote csd-1 for track $ti (${bufInfo.size} bytes)")
                }
            }

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufInfo = MediaCodec.BufferInfo()
            val durationUs = endUs - startUs
            var samples = 0
            var lastPct = 0

            while (true) {
                if (!extractor.advance()) break
                val ti = extractor.sampleTrackIndex
                if (ti !in trackMap) continue
                val pts = extractor.sampleTime
                if (pts >= endUs) break

                val sampleSize = extractor.sampleSize.toInt()
                if (sampleSize <= 0) continue

                val mi = trackMap[ti]!!
                val buf = ByteBuffer.allocate(sampleSize)
                val n = extractor.readSampleData(buf, 0)
                if (n <= 0) continue
                bufInfo.apply {
                    size = n
                    presentationTimeUs = pts - startUs
                    flags = extractor.sampleFlags
                }
                muxer.writeSampleData(mi, buf, bufInfo)
                samples++

                if (ti == firstVideoTrack && durationUs > 0) {
                    val pct = ((pts - startUs).toFloat() / durationUs * 90).toInt().coerceIn(0, 90) + 5
                    if (pct > lastPct) { lastPct = pct; onProgress(pct) }
                }
            }

            extractor.release()
            muxer.stop(); muxer.release()

            Log.i(TAG, "Trim done: $samples samples")
            onProgress(100)
            return outputPath
        } catch (e: Exception) {
            Log.e(TAG, "Trim failed", e)
            try { File(outputPath).delete() } catch (_: Exception) {}
            return null
        }
    }

    fun trimReverse(
        inputPath: String,
        outputPath: String,
        startSec: Float,
        endSec: Float,
        onProgress: (Int) -> Unit,
    ): String? {
        val startUs = (startSec * 1_000_000).toLong()
        val endUs = (endSec * 1_000_000).toLong()

        Log.i(TAG, "trimReverse: input=$inputPath start=${startUs}us end=${endUs}us")

        try {
            onProgress(5)
            val video = decodeVideo(inputPath, startUs, endUs) ?: return null
            onProgress(30)

            val audio = decodeAudio(inputPath, startUs, endUs)
            onProgress(40)

            encodeReversed(outputPath, video, audio) { p ->
                onProgress(40 + (p * 55 / 100))
            }
            onProgress(100)
            Log.i(TAG, "Reverse done: ${video.frames.size} frames")
            return outputPath
        } catch (e: Exception) {
            Log.e(TAG, "Reverse trim failed", e)
            try { File(outputPath).delete() } catch (_: Exception) {}
            return null
        }
    }

    // ---- internal data ----

    private class DecodedVideo(
        val frames: List<Frame>,
        val width: Int,
        val height: Int,
        val mime: String,
        val rotation: Int,
    )

    private class Frame(
        val data: ByteArray,
        val size: Int,
        val ptsUs: Long,
        val isKey: Boolean,
    )

    private class DecodedAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

    // ---- decode video ----

    private fun decodeVideo(path: String, startUs: Long, endUs: Long): DecodedVideo? {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var ti = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { ti = i; fmt = f; break }
        }
        if (ti < 0 || fmt == null) { ex.release(); return null }

        ex.selectTrack(ti); ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, null, null, 0); dec.start()

        val frames = mutableListOf<Frame>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var done = false

        while (!done) {
            if (!inputDone) {
                val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = dec.getInputBuffer(idx)!!
                    val n = ex.readSampleData(buf, 0); val t = ex.sampleTime
                    if (n < 0 || t >= endUs) {
                        dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val fl = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        dec.queueInputBuffer(idx, 0, n, t, fl)
                        ex.advance()
                    }
                }
            }
            val oi = dec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (inputDone) done = true }
                oi >= 0 -> {
                    if (info.size > 0 && info.presentationTimeUs in startUs until endUs) {
                        val ob = dec.getOutputBuffer(oi)!!
                        val d = ByteArray(info.size)
                        ob.position(info.offset); ob.get(d, 0, info.size)
                        frames.add(Frame(d, info.size, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0))
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }

        dec.stop(); dec.release(); ex.release()
        val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        Log.i(TAG, "Decoded ${frames.size} video frames (rotation=$rotation)")
        if (frames.isEmpty()) return null
        return DecodedVideo(frames.reversed(), fmt.getInteger(MediaFormat.KEY_WIDTH), fmt.getInteger(MediaFormat.KEY_HEIGHT), mime, rotation)
    }

    // ---- decode audio ----

    private fun decodeAudio(path: String, startUs: Long, endUs: Long): DecodedAudio? {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        var ti = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; fmt = f; break }
        }
        if (ti < 0 || fmt == null) { ex.release(); return null }

        ex.selectTrack(ti); ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, null, null, 0); dec.start()

        val chunks = mutableListOf<ByteArray>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var done = false

        while (!done) {
            if (!inputDone) {
                val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = dec.getInputBuffer(idx)!!
                    val n = ex.readSampleData(buf, 0); val t = ex.sampleTime
                    if (n < 0 || t >= endUs) {
                        dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(idx, 0, n, t, 0)
                        ex.advance()
                    }
                }
            }
            val oi = dec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (inputDone) done = true }
                oi >= 0 -> {
                    if (info.size > 0) {
                        val ob = dec.getOutputBuffer(oi)!!
                        val d = ByteArray(info.size)
                        ob.position(info.offset); ob.get(d, 0, info.size)
                        chunks.add(d)
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }

        dec.stop(); dec.release(); ex.release()
        if (chunks.isEmpty()) return null

        val total = chunks.sumOf { it.size }
        val pcm = ByteArray(total)
        var off = 0
        for (c in chunks) { System.arraycopy(c, 0, pcm, off, c.size); off += c.size }

        // Reverse 16-bit samples
        val rev = ByteArray(total)
        for (i in 0 until total step 2) {
            val src = total - 2 - i
            if (src >= 0) { rev[i] = pcm[src]; rev[i + 1] = pcm[src + 1] }
        }

        Log.i(TAG, "Decoded ${total}B PCM audio")
        return DecodedAudio(rev, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }

    // ---- encode reversed ----

    private fun encodeReversed(path: String, vid: DecodedVideo, aud: DecodedAudio?, onProgress: (Int) -> Unit) {
        val mux = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val vFmt = MediaFormat.createVideoFormat(vid.mime, vid.width, vid.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if (vid.rotation != 0) setInteger(MediaFormat.KEY_ROTATION, vid.rotation)
        }

        val vEnc = MediaCodec.createEncoderByType(vid.mime)
        vEnc.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        vEnc.start()
        var vTi = -1; var muxStarted = false
        var fi = 0; var vDone = false; var vInDone = false
        val vbi = MediaCodec.BufferInfo()

        val aEnc = if (aud != null) {
            val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, aud.sampleRate, aud.channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ae.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ae.start()
            ae
        } else null
        var aTi = -1; var aDone = aud == null; var aInDone = aud == null

        var aPcmOff = 0; val aPcm = aud?.pcm

        while (!vDone || !aDone) {
            // Video input
            if (!vInDone && fi < vid.frames.size) {
                val idx = vEnc.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val f = vid.frames[fi]
                    val buf = vEnc.getInputBuffer(idx)!!
                    buf.clear(); buf.put(f.data, 0, f.size)
                    vEnc.queueInputBuffer(idx, 0, f.size, f.ptsUs, if (f.isKey) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                    fi++
                }
            } else if (!vInDone) {
                val idx = vEnc.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) { vEnc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); vInDone = true }
            }

            // Video output
            val vs = vEnc.dequeueOutputBuffer(vbi, TIMEOUT_US)
            when {
                vs == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { vTi = mux.addTrack(vEnc.outputFormat); if (aTi >= 0 || aDone) { mux.start(); muxStarted = true } }
                vs >= 0 -> {
                    if (vbi.size > 0 && vTi >= 0) {
                        if (!muxStarted && (aTi >= 0 || aDone)) { mux.start(); muxStarted = true }
                        if (muxStarted) mux.writeSampleData(vTi, vEnc.getOutputBuffer(vs)!!, vbi)
                    }
                    vEnc.releaseOutputBuffer(vs, false)
                    if (vbi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) vDone = true
                }
            }

            // Audio
            if (aEnc != null && !aDone) {
                if (!aInDone && aPcm != null) {
                    val idx = aEnc.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = aEnc.getInputBuffer(idx)!!
                        val chunk = minOf(aPcm.size - aPcmOff, buf.capacity())
                        if (chunk <= 0) {
                            aEnc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            aInDone = true
                        } else {
                            buf.clear(); buf.put(aPcm, aPcmOff, chunk)
                            aEnc.queueInputBuffer(idx, 0, chunk, 0, 0)
                            aPcmOff += chunk
                        }
                    }
                }
                val as_ = aEnc.dequeueOutputBuffer(vbi, TIMEOUT_US)
                when {
                    as_ == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { aTi = mux.addTrack(aEnc.outputFormat); if (vTi >= 0 && !muxStarted) { mux.start(); muxStarted = true } }
                    as_ >= 0 -> {
                        if (vbi.size > 0 && aTi >= 0 && muxStarted) mux.writeSampleData(aTi, aEnc.getOutputBuffer(as_)!!, vbi)
                        aEnc.releaseOutputBuffer(as_, false)
                        if (vbi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) aDone = true
                    }
                }
            }

            if (vid.frames.isNotEmpty()) {
                onProgress((fi * 100 / vid.frames.size).coerceIn(0, 100))
            }
        }

        vEnc.stop(); vEnc.release()
        aEnc?.stop(); aEnc?.release()
        if (muxStarted) mux.stop()
        mux.release()
    }
}
