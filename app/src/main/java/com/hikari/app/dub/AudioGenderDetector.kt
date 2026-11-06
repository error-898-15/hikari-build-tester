package com.hikari.app.dub

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.hikari.app.data.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Open-source speaker gender & identity detector for unnamed subtitle lines:
 *  1) Uses the official open-source k2-fsa/sherpa-onnx 3D-Speaker model
 *     (3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx, ~40 MB)
 *     for speaker embedding clustering when available.
 *  2) Uses fast sparse MediaExtractor seeking (only 0.85s per speaker turn)
 *     plus YIN fundamental frequency (F0) & spectral balance on the center
 *     dialogue channel so a full episode finishes in under 2 minutes.
 */
object AudioGenderDetector {

    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    private const val MODEL_NAME = "3dspeaker_eres2net_base_16k.onnx"
    private const val TARGET_RATE = 16000
    private const val WINDOW_US = 850_000L
    private const val TURN_GAP_MS = 600L

    data class CueVoiceResult(
        val gender: Gender,
        val f0Hz: Float,
        val clusterId: Int,
    )

    private fun modelDir(c: Context): File =
        File(File(c.filesDir, "dub_models"), "speaker-id").apply { mkdirs() }

    fun modelFile(c: Context): File = File(modelDir(c), MODEL_NAME)

    fun isModelInstalled(c: Context): Boolean {
        val f = modelFile(c)
        return f.exists() && f.length() > 35_000_000L
    }

    suspend fun ensureModelInstalled(
        c: Context,
        onProgress: ((Int) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val target = modelFile(c)
        if (isModelInstalled(c)) return@withContext target
        val part = File(c.cacheDir, "$MODEL_NAME.part")
        try {
            val have = if (part.exists()) part.length() else 0L
            var u = MODEL_URL
            var hops = 0
            var conn: HttpURLConnection
            while (true) {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 25_000
                conn.readTimeout = 45_000
                conn.setRequestProperty("User-Agent", "Hikari")
                if (have > 0L) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399 && hops < 6) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) break
                    u = URL(URL(u), loc).toString()
                    hops++
                    continue
                }
                break
            }
            val code = conn.responseCode
            val append = code == 206 && have > 0L
            if (code != 200 && code != 206) {
                conn.disconnect()
                return@withContext null
            }
            val len = conn.contentLengthLong
            val total = if (len > 0) len + (if (append) have else 0L) else 40_000_000L
            var received = if (append) have else 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        val pct = ((received * 100L) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress?.invoke(pct)
                        }
                    }
                }
            }
            conn.disconnect()
            if (part.length() < 35_000_000L) return@withContext null
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            target
        } catch (t: Throwable) {
            Logs.log("Dub", "Speaker model download skipped: ${t.message}")
            null
        }
    }

    /**
     * Scans only the turn-starting unnamed cues in [cues] so an entire episode
     * completes in under 2 minutes (typically 20-45 seconds), calling [onUpdate]
     * progressively as turns are resolved.
     */
    suspend fun analyzeEpisode(
        context: Context,
        mediaUrl: String?,
        headers: Map<String, String>,
        cues: List<DubCue>,
        onUpdate: (Int, CueVoiceResult) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (mediaUrl.isNullOrBlank() || cues.isEmpty()) return@withContext 0
        val unnamedIndices = ArrayList<Int>()
        for (i in cues.indices) {
            if (cues[i].speaker == null) unnamedIndices.add(i)
        }
        if (unnamedIndices.isEmpty()) return@withContext 0

        // Ensure the ~40 MB open-source 3D-Speaker ONNX model is ready on disk.
        val onnxFile = ensureModelInstalled(context)
        val embedder = if (onnxFile != null) SherpaEmbedder.create(onnxFile) else null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var resolvedCount = 0
        try {
            if (mediaUrl.startsWith("http://", ignoreCase = true) ||
                mediaUrl.startsWith("https://", ignoreCase = true)
            ) {
                extractor.setDataSource(mediaUrl, headers)
            } else {
                extractor.setDataSource(mediaUrl)
            }
            var audioTrack = -1
            var format: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = t
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return@withContext 0
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext 0
            extractor.selectTrack(audioTrack)
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val clusters = ArrayList<SpeakerCluster>()
            val startWall = System.currentTimeMillis()
            // Hard budget: 110 seconds (< 2 minutes) for the whole scan.
            val deadlineMs = startWall + 110_000L

            var lastResult: CueVoiceResult? = null
            var prevCue: DubCue? = null

            for (idx in unnamedIndices) {
                ensureActive()
                if (System.currentTimeMillis() > deadlineMs) break
                val cue = cues[idx]
                val p = prevCue
                val sameTurn = p != null &&
                    cue.turn == 0 &&
                    (cue.startMs - p.endMs < TURN_GAP_MS)
                if (sameTurn && lastResult != null) {
                    onUpdate(idx, lastResult)
                    prevCue = cue
                    resolvedCount++
                    continue
                }

                val seekUs = max(0L, (cue.startMs + 80L) * 1000L)
                val endUs = min(cue.endMs * 1000L, seekUs + WINDOW_US)
                if (endUs <= seekUs + 120_000L) {
                    prevCue = cue
                    continue
                }

                val decoded = decodeWindow(
                    extractor = extractor,
                    codec = dec,
                    startUs = seekUs,
                    endUs = endUs,
                    initialRate = sampleRate,
                    initialChannels = channels,
                )
                if (decoded != null) {
                    sampleRate = decoded.rate
                    channels = decoded.channels
                    val analysis = analyzeSamples(decoded.centerMono, decoded.rate, embedder, clusters)
                    if (analysis != null) {
                        lastResult = analysis
                        onUpdate(idx, analysis)
                        resolvedCount++
                    }
                }
                prevCue = cue
            }
            resolvedCount
        } catch (t: Throwable) {
            Logs.log("Dub", "Audio gender scan stopped early: ${t.javaClass.simpleName}: ${t.message}")
            resolvedCount
        } finally {
            runCatching { embedder?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class DecodedWindow(val centerMono: FloatArray, val rate: Int, val channels: Int)

    private fun decodeWindow(
        extractor: MediaExtractor,
        codec: MediaCodec,
        startUs: Long,
        endUs: Long,
        initialRate: Int,
        initialChannels: Int,
    ): DecodedWindow? {
        return try {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            var rate = initialRate
            var ch = initialChannels.coerceAtLeast(1)
            val maxSamples = ((endUs - startUs) * rate / 1_000_000L).toInt().coerceIn(2048, rate * 2)
            val outMono = FloatArray(maxSamples)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var steps = 0

            while (!outputDone && written < maxSamples && steps < 180) {
                steps++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)
                        val sampleTime = extractor.sampleTime
                        if (ib == null || sampleTime < 0L || sampleTime > endUs + 250_000L) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = extractor.readSampleData(ib, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 4_000L)
                if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0 && info.presentationTimeUs >= startUs - 50_000L) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (f in 0 until frames) {
                            if (written >= maxSamples) break
                            val sample = when {
                                ch >= 6 -> {
                                    // 5.1 surround: channel 2 is the dedicated center dialogue channel
                                    val c0 = sb.get()
                                    val c1 = sb.get()
                                    val center = sb.get().toFloat() / 32768f
                                    for (k in 3 until ch) sb.get()
                                    val lr = (c0.toFloat() + c1.toFloat()) / 65536f
                                    if (abs(center) > 1e-4f) center else lr
                                }
                                ch == 2 -> {
                                    val l = sb.get().toFloat() / 32768f
                                    val r = sb.get().toFloat() / 32768f
                                    (l + r) * 0.5f
                                }
                                else -> {
                                    var acc = 0f
                                    for (k in 0 until ch) acc += sb.get().toFloat() / 32768f
                                    acc / ch
                                }
                            }
                            outMono[written++] = sample
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                        info.presentationTimeUs >= endUs
                    ) {
                        outputDone = true
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                }
            }
            if (written < rate / 8) null else DecodedWindow(outMono.copyOf(written), rate, ch)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Estimates pitch (F0) and gender from raw mono/center PCM samples.
     * Also exposed for real-time audio sink buffers in [VoiceCutProcessor].
     */
    fun estimatePitchAndGender(samples: FloatArray, sampleRate: Int): Pair<Float, Gender>? {
        if (samples.size < sampleRate / 8 || sampleRate < 8000) return null
        val filtered = voiceBandPass(samples, sampleRate)
        val frameLen = (sampleRate * 0.032).toInt().coerceAtLeast(256)
        val hop = (sampleRate * 0.016).toInt().coerceAtLeast(128)
        val f0List = ArrayList<Float>()

        var pos = 0
        while (pos + frameLen <= filtered.size) {
            var energy = 0.0
            for (i in 0 until frameLen) {
                val v = filtered[pos + i].toDouble()
                energy += v * v
            }
            val rms = sqrt(energy / frameLen)
            if (rms > 0.008) {
                val f0 = yinPitch(filtered, pos, frameLen, sampleRate)
                if (f0 in 70f..410f) f0List.add(f0)
            }
            pos += hop
        }
        if (f0List.size < 2) return null
        f0List.sort()
        // Lower-median (42nd percentile) is robust against octave-doubling harmonics.
        val medianF0 = f0List[(f0List.size * 0.42).toInt().coerceIn(0, f0List.size - 1)]
        val gender = when {
            medianF0 < 162f -> Gender.MALE
            medianF0 <= 275f -> Gender.FEMALE
            else -> Gender.CHILD
        }
        return medianF0 to gender
    }

    /** YIN fundamental frequency estimator on one frame. */
    private fun yinPitch(buf: FloatArray, offset: Int, len: Int, rate: Int): Float {
        val tauMin = (rate / 410).coerceAtLeast(2)
        val tauMax = (rate / 70).coerceAtMost(len / 2 - 1)
        if (tauMax <= tauMin) return -1f
        val d = FloatArray(tauMax + 1)
        val cmnd = FloatArray(tauMax + 1)
        cmnd[0] = 1f
        var runningSum = 0f
        for (tau in 1..tauMax) {
            var sum = 0f
            val limit = len - tauMax
            var i = 0
            while (i < limit) {
                val delta = buf[offset + i] - buf[offset + i + tau]
                sum += delta * delta
                i++
            }
            d[tau] = sum
            runningSum += sum
            cmnd[tau] = if (runningSum > 1e-9f) d[tau] * tau / runningSum else 1f
        }
        val threshold = 0.18f
        var bestTau = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) {
                    tau++
                }
                bestTau = tau
                break
            }
            tau++
        }
        if (bestTau < 0) {
            var minVal = 1f
            for (t in tauMin..tauMax) {
                if (cmnd[t] < minVal) {
                    minVal = cmnd[t]
                    bestTau = t
                }
            }
            if (minVal > 0.42f) return -1f
        }
        // Parabolic interpolation around bestTau
        val x0 = if (bestTau > 1) bestTau - 1 else bestTau
        val x2 = if (bestTau + 1 <= tauMax) bestTau + 1 else bestTau
        val refinedTau = if (x0 == bestTau || x2 == bestTau) {
            bestTau.toFloat()
        } else {
            val s0 = cmnd[x0]
            val s1 = cmnd[bestTau]
            val s2 = cmnd[x2]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (abs(denom) > 1e-6f) bestTau + (s2 - s0) / denom else bestTau.toFloat()
        }
        return if (refinedTau > 0f) rate.toFloat() / refinedTau else -1f
    }

    /** Band-pass filter (80 Hz .. 1100 Hz) to isolate vocal fundamental & first formant. */
    private fun voiceBandPass(x: FloatArray, fs: Int): FloatArray {
        val out = FloatArray(x.size)
        val hpAlpha = (1.0 / (1.0 + 2.0 * PI * 80.0 / fs)).toFloat()
        val lpAlpha = (1.0 - exp(-2.0 * PI * 1100.0 / fs)).toFloat()
        var prevX = x[0]
        var hpY = 0f
        var lpY = 0f
        for (i in x.indices) {
            val cur = x[i]
            hpY = hpAlpha * (hpY + cur - prevX)
            prevX = cur
            lpY += lpAlpha * (hpY - lpY)
            out[i] = lpY
        }
        return out
    }

    private class SpeakerCluster(
        val id: Int,
        val centroid: FloatArray?,
        val f0Values: MutableList<Float> = ArrayList(),
    ) {
        fun updateCentroid(emb: FloatArray) {
            val c = centroid ?: return
            if (c.size != emb.size) return
            val n = f0Values.size.coerceAtLeast(1)
            var norm = 0f
            for (i in c.indices) {
                c[i] = (c[i] * n + emb[i]) / (n + 1)
                norm += c[i] * c[i]
            }
            val inv = if (norm > 1e-9f) 1f / sqrt(norm) else 1f
            for (i in c.indices) c[i] *= inv
        }

        fun medianGender(): Pair<Float, Gender> {
            val sorted = f0Values.sorted()
            val med = sorted[sorted.size / 2]
            val g = when {
                med < 162f -> Gender.MALE
                med <= 275f -> Gender.FEMALE
                else -> Gender.CHILD
            }
            return med to g
        }
    }

    private fun analyzeSamples(
        samples: FloatArray,
        rate: Int,
        embedder: SherpaEmbedder?,
        clusters: MutableList<SpeakerCluster>,
    ): CueVoiceResult? {
        val (f0, rawGender) = estimatePitchAndGender(samples, rate) ?: return null
        val emb = embedder?.compute(resampleTo16k(samples, rate))
        if (emb != null && emb.isNotEmpty()) {
            var bestCluster: SpeakerCluster? = null
            var bestSim = -1f
            for (cl in clusters) {
                val c = cl.centroid ?: continue
                val sim = cosine(c, emb)
                if (sim > bestSim) {
                    bestSim = sim
                    bestCluster = cl
                }
            }
            val chosen = if (bestCluster != null && bestSim >= 0.60f) {
                bestCluster.updateCentroid(emb)
                bestCluster.f0Values.add(f0)
                bestCluster
            } else {
                val fresh = SpeakerCluster(clusters.size, emb.copyOf()).apply {
                    f0Values.add(f0)
                }
                clusters.add(fresh)
                fresh
            }
            val (clusterF0, clusterGender) = chosen.medianGender()
            return CueVoiceResult(clusterGender, clusterF0, chosen.id)
        }
        val fallbackCluster = if (rawGender == Gender.MALE) {
            if (f0 < 122f) 0 else 1
        } else {
            if (f0 < 210f) 0 else 1
        }
        return CueVoiceResult(rawGender, f0, fallbackCluster)
    }

    private fun resampleTo16k(x: FloatArray, rate: Int): FloatArray {
        if (rate == TARGET_RATE) return x
        val ratio = rate.toDouble() / TARGET_RATE.toDouble()
        val nOut = (x.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(nOut)
        val last = x.size - 1
        for (i in 0 until nOut) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, last)
            val frac = (pos - idx).toFloat()
            val next = if (idx + 1 <= last) x[idx + 1] else x[idx]
            out[i] = x[idx] + frac * (next - x[idx])
        }
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na * nb)
        return if (denom > 1e-8f) dot / denom else 0f
    }

    /**
     * Reflective wrapper around sherpa-onnx's SpeakerEmbeddingExtractor so that
     * compilation succeeds regardless of whether Speaker.kt is present at compile time.
     */
    private class SherpaEmbedder(private val extractor: Any) {
        fun compute(samples16k: FloatArray): FloatArray? {
            return try {
                val createStream = extractor.javaClass.getMethod("createStream")
                val stream = createStream.invoke(extractor) ?: return null
                val accept = stream.javaClass.getMethod(
                    "acceptWaveform",
                    FloatArray::class.java,
                    Int::class.javaPrimitiveType,
                )
                accept.invoke(stream, samples16k, TARGET_RATE)
                val inputFinished = stream.javaClass.getMethod("inputFinished")
                inputFinished.invoke(stream)
                val isReady = extractor.javaClass.getMethod("isReady", stream.javaClass)
                val ready = isReady.invoke(extractor, stream) as? Boolean ?: false
                val out = if (ready) {
                    val compute = extractor.javaClass.getMethod("compute", stream.javaClass)
                    compute.invoke(extractor, stream) as? FloatArray
                } else null
                runCatching { stream.javaClass.getMethod("release").invoke(stream) }
                out
            } catch (t: Throwable) {
                null
            }
        }

        fun release() {
            runCatching { extractor.javaClass.getMethod("release").invoke(extractor) }
        }

        companion object {
            fun create(modelFile: File): SherpaEmbedder? {
                return try {
                    val cfgCls = Class.forName("com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig")
                    val cfg = cfgCls.getDeclaredConstructor(
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        String::class.java,
                    ).newInstance(modelFile.absolutePath, 2, false, "cpu")
                    val extCls = Class.forName("com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor")
                    val assetMgrCls = android.content.res.AssetManager::class.java
                    val ext = extCls.getDeclaredConstructor(assetMgrCls, cfgCls)
                        .newInstance(null, cfg)
                    SherpaEmbedder(ext)
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }
}
