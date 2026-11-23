package com.offline.musicplayer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

internal class TrackPeakScanner(private val context: Context) {
    companion object {
        private const val TAG = "TrackPeakScanner"
        private const val MAX_METADATA_BLOCK_SIZE = 16 * 1024 * 1024
        private const val TIMEOUT_US = 10_000L
    }

    suspend fun findPeakDb(uri: Uri): Float? {
        val tags = readReplayGainTags(uri)
        tags.peakDb?.let { return it }
        if (tags.trackGainDb != null) {
            Log.d(TAG, "Found ReplayGain track gain without peak; scanning decoded peak for a safe ceiling")
        }
        return scanDecodedPeakDb(uri)
    }

    private fun readReplayGainTags(uri: Uri): ReplayGainTags {
        val tagsFromFile = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (input.readNBytesCompat(4).toString(Charsets.ISO_8859_1)) {
                    "fLaC" -> readFlacTags(input)
                    "ID3" -> readId3Tags(input)
                    "OggS" -> readOggTags(input)
                    else -> ReplayGainTags()
                }
            } ?: ReplayGainTags()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ReplayGain tags from $uri", e)
            ReplayGainTags()
        }
        if (tagsFromFile.peakDb != null) return tagsFromFile
        return tagsFromFile.merge(readExtractorTags(uri))
    }

    private fun readExtractorTags(uri: Uri): ReplayGainTags {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    for (key in format.keys) {
                        if (key.contains("replaygain", ignoreCase = true)) {
                            val value = runCatching { format.getString(key) }.getOrNull()
                                ?: continue
                            val parsed = ReplayGainTags.from(key, value)
                            if (parsed.peakDb != null || parsed.trackGainDb != null) {
                                return parsed
                            }
                        }
                    }
                }
            }
            ReplayGainTags()
        } catch (e: Exception) {
            Log.d(TAG, "Container metadata did not expose ReplayGain tags", e)
            ReplayGainTags()
        } finally {
            extractor.release()
        }
    }

    private fun readFlacTags(input: InputStream): ReplayGainTags {
        var tags = ReplayGainTags()
        var isLastBlock: Boolean
        do {
            val blockHeader = input.readNBytesCompat(4)
            if (blockHeader.size != 4) return tags
            isLastBlock = blockHeader[0].toInt() and 0x80 != 0
            val blockType = blockHeader[0].toInt() and 0x7f
            val blockSize = ((blockHeader[1].toInt() and 0xff) shl 16) or
                ((blockHeader[2].toInt() and 0xff) shl 8) or
                (blockHeader[3].toInt() and 0xff)
            if (blockSize > MAX_METADATA_BLOCK_SIZE) return tags
            if (blockType == 4) {
                tags = tags.merge(parseVorbisComments(input.readNBytesCompat(blockSize)))
            } else {
                input.skipFully(blockSize)
            }
        } while (!isLastBlock)
        return tags
    }

    private fun parseVorbisComments(data: ByteArray): ReplayGainTags {
        var offset = 0
        fun readLittleEndianInt(): Int? {
            if (offset + 4 > data.size) return null
            val value = (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
            offset += 4
            return value
        }

        val vendorLength = readLittleEndianInt() ?: return ReplayGainTags()
        if (vendorLength < 0 || offset + vendorLength > data.size) return ReplayGainTags()
        offset += vendorLength
        val commentCount = readLittleEndianInt()?.coerceIn(0, 100_000) ?: return ReplayGainTags()
        var tags = ReplayGainTags()
        repeat(commentCount) {
            val length = readLittleEndianInt() ?: return tags
            if (length < 0 || offset + length > data.size) return tags
            val comment = data.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            offset += length
            val separator = comment.indexOf('=')
            if (separator > 0) {
                tags = tags.merge(
                    ReplayGainTags.from(comment.substring(0, separator), comment.substring(separator + 1))
                )
            }
        }
        return tags
    }

    private fun readOggTags(input: InputStream): ReplayGainTags {
        var packetIndex = 0
        val packet = ByteArrayOutputStream()
        while (true) {
            val pageHeader = input.readNBytesCompat(23)
            if (pageHeader.size != 23) return ReplayGainTags()
            val segmentCount = pageHeader[22].toInt() and 0xff
            val lacing = input.readNBytesCompat(segmentCount)
            if (lacing.size != segmentCount) return ReplayGainTags()
            for (segmentLengthByte in lacing) {
                val segmentLength = segmentLengthByte.toInt() and 0xff
                if (packet.size() + segmentLength > MAX_METADATA_BLOCK_SIZE) {
                    return ReplayGainTags()
                }
                val segment = input.readNBytesCompat(segmentLength)
                if (segment.size != segmentLength) return ReplayGainTags()
                packet.write(segment)

                if (segmentLength < 255) {
                    if (packetIndex == 1) {
                        val commentPacket = packet.toByteArray()
                        val prefixLength = when {
                            commentPacket.startsWithBytes(byteArrayOf(3) + "vorbis".toByteArray()) -> 7
                            commentPacket.startsWithBytes("OpusTags".toByteArray()) -> 8
                            else -> return ReplayGainTags()
                        }
                        return parseVorbisComments(commentPacket.copyOfRange(prefixLength, commentPacket.size))
                    }
                    packetIndex++
                    packet.reset()
                }
            }
        }
    }

    private fun readId3Tags(input: InputStream): ReplayGainTags {
        val headerTail = input.readNBytesCompat(6)
        if (headerTail.size != 6) return ReplayGainTags()
        val majorVersion = headerTail[0].toInt() and 0xff
        val tagSize = readSynchsafeInt(headerTail, 2) ?: return ReplayGainTags()
        if (tagSize > MAX_METADATA_BLOCK_SIZE || majorVersion !in 2..4) return ReplayGainTags()
        val body = input.readNBytesCompat(tagSize)
        var offset = 0
        var tags = ReplayGainTags()
        val frameHeaderSize = if (majorVersion == 2) 6 else 10
        while (offset + frameHeaderSize <= body.size) {
            val frameIdLength = if (majorVersion == 2) 3 else 4
            val frameId = body.copyOfRange(offset, offset + frameIdLength)
                .toString(Charsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = if (majorVersion == 2) {
                ((body[offset + 3].toInt() and 0xff) shl 16) or
                    ((body[offset + 4].toInt() and 0xff) shl 8) or
                    (body[offset + 5].toInt() and 0xff)
            } else if (majorVersion == 4) {
                readSynchsafeInt(body, offset + 4) ?: break
            } else {
                ByteBuffer.wrap(body, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            }
            if (frameSize <= 0 || offset + frameHeaderSize + frameSize > body.size) break
            val isUserTextFrame = frameId == "TXXX" || frameId == "TXX"
            if (isUserTextFrame) {
                tags = tags.merge(parseUserTextFrame(body, offset + frameHeaderSize, frameSize))
            }
            offset += frameHeaderSize + frameSize
        }
        return tags
    }

    private fun parseUserTextFrame(body: ByteArray, start: Int, size: Int): ReplayGainTags {
        if (size <= 1) return ReplayGainTags()
        val encoding = body[start].toInt() and 0xff
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val terminatorWidth = if (encoding == 1 || encoding == 2) 2 else 1
        var valueStart = start + 1
        val end = start + size
        while (valueStart + terminatorWidth <= end) {
            if (body[valueStart].toInt() == 0 &&
                (terminatorWidth == 1 || body[valueStart + 1].toInt() == 0)
            ) {
                valueStart += terminatorWidth
                break
            }
            valueStart += 1
        }
        val descriptionEnd = valueStart - terminatorWidth
        val description = body.copyOfRange(start + 1, descriptionEnd.coerceAtLeast(start + 1))
            .toString(charset)
            .trim('\u0000', ' ')
        val value = body.copyOfRange(valueStart.coerceAtMost(end), end)
            .toString(charset)
            .trim('\u0000', ' ')
        return ReplayGainTags.from(description, value)
    }

    private suspend fun scanDecodedPeakDb(uri: Uri): Float? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var peak = 0.0
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder returned no input buffer")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: error("Decoder returned no output buffer")
                            peak = max(
                                peak,
                                measurePeak(
                                    outputBuffer,
                                    bufferInfo.offset,
                                    bufferInfo.size,
                                    pcmEncoding
                                )
                            )
                        }
                        outputEnded =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (peak <= 0.0) -120f else (20.0 * log10(peak)).toFloat()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not scan audio peak for $uri", e)
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: IllegalStateException) {
                // Decoder may not have reached the started state.
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun measurePeak(buffer: ByteBuffer, offset: Int, size: Int, pcmEncoding: Int): Double {
        val data = buffer.duplicate().order(ByteOrder.nativeOrder()).apply {
            position(offset)
            limit(offset + size)
        }
        return when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = data.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
                var peak = 0.0
                while (samples.hasRemaining()) {
                    peak = max(peak, abs(samples.get().toDouble()))
                }
                peak
            }
            android.media.AudioFormat.ENCODING_PCM_8BIT -> {
                var peak = 0.0
                while (data.hasRemaining()) {
                    peak = max(peak, abs((data.get().toInt() and 0xff) - 128) / 128.0)
                }
                peak
            }
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var peak = 0.0
                while (data.remaining() >= 3) {
                    var sample = (data.get().toInt() and 0xff) or
                        ((data.get().toInt() and 0xff) shl 8) or
                        ((data.get().toInt() and 0xff) shl 16)
                    if (sample and 0x800000 != 0) sample = sample or -0x1000000
                    peak = max(peak, abs(sample.toDouble()) / 8_388_608.0)
                }
                peak
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> {
                val samples = data.slice().order(ByteOrder.nativeOrder()).asIntBuffer()
                var peak = 0.0
                while (samples.hasRemaining()) {
                    peak = max(peak, abs(samples.get().toDouble() / 2_147_483_648.0))
                }
                peak
            }
            else -> {
                val samples = data.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                var peak = 0.0
                while (samples.hasRemaining()) {
                    peak = max(peak, abs(samples.get().toInt()) / 32768.0)
                }
                peak
            }
        }
    }

    private fun readSynchsafeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        return ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun InputStream.readNBytesCompat(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        return if (offset == length) result else result.copyOf(offset)
    }

    private fun InputStream.skipFully(length: Int) {
        var remaining = length.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() == -1) {
                return
            } else {
                remaining--
            }
        }
    }

    private data class ReplayGainTags(
        val peakDb: Float? = null,
        val trackGainDb: Float? = null
    ) {
        fun merge(other: ReplayGainTags) = ReplayGainTags(
            peakDb = peakDb ?: other.peakDb,
            trackGainDb = trackGainDb ?: other.trackGainDb
        )

        companion object {
            fun from(name: String, rawValue: String): ReplayGainTags {
                val value = rawValue.trim().removeSuffix("dB").trim().toFloatOrNull()
                    ?: return ReplayGainTags()
                return when (name.trim().uppercase()) {
                    "REPLAYGAIN_TRACK_PEAK" -> {
                        if (value <= 0f) ReplayGainTags()
                        else ReplayGainTags(peakDb = (20.0 * log10(value.toDouble())).toFloat())
                    }
                    "REPLAYGAIN_TRACK_GAIN" -> ReplayGainTags(trackGainDb = value)
                    else -> ReplayGainTags()
                }
            }
        }
    }
}
