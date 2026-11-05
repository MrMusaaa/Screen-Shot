package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FrameVideoEncoder
 * 
 * Ardışık yakalanan sessiz ekran görüntüsü karelerini (JPEG dizisi)
 * donanımsal MediaCodec (H.264) ve MediaMuxer kullanarak
 * gerçek bir MP4 video dosyasına dönüştürür.
 * 
 * Telefonu kastırmamak için GPU hızlandırmalı InputSurface ve
 * donanım H264 encoder kullanır.
 */
object FrameVideoEncoder {

    private const val TAG = "FrameVideoEncoder"

    suspend fun encodeFramesToMp4(
        frameFiles: List<File>,
        outputFile: File,
        fps: Int = 5,
        targetWidth: Int = 720,
        targetHeight: Int = 1280
    ): Boolean = withContext(Dispatchers.IO) {
        if (frameFiles.isEmpty()) {
            Log.w(TAG, "Kodlanacak kare listesi boş.")
            return@withContext false
        }

        // H.264 standardı gereği genişlik ve yükseklik çift sayı olmalıdır
        val width = if (targetWidth % 2 == 0) targetWidth else targetWidth - 1
        val height = if (targetHeight % 2 == 0) targetHeight else targetHeight - 1

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 2 Mbps temiz görüntü
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 saniyede bir keyframe
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / fps
            var presentationTimeUs = 0L

            for (file in frameFiles) {
                if (!file.exists()) continue
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val scaledBitmap = if (rawBitmap.width != width || rawBitmap.height != height) {
                    val s = Bitmap.createScaledBitmap(rawBitmap, width, height, true)
                    rawBitmap.recycle()
                    s
                } else {
                    rawBitmap
                }

                // Kareyi donanımsal Surface üzerine çiz
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)
                scaledBitmap.recycle()

                // Kodlayıcıdan çıkan paketleri MediaMuxer'a aktar
                presentationTimeUs += frameDurationUs
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outputIndex >= 0) {
                        val encodedBuffer = codec.getOutputBuffer(outputIndex)
                        if (encodedBuffer != null && muxerStarted && bufferInfo.size > 0) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            bufferInfo.presentationTimeUs = presentationTimeUs
                            muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            // Kodlayıcıya akışın bittiğini bildir
            try {
                codec.signalEndOfInputStream()
            } catch (ignored: Exception) {}

            // Kalan tamponları boşalt
            var eos = false
            var drainTries = 0
            while (!eos && drainTries < 30) {
                drainTries++
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                } else if (outputIndex >= 0) {
                    val encodedBuffer = codec.getOutputBuffer(outputIndex)
                    if (encodedBuffer != null && muxerStarted && bufferInfo.size > 0) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eos = true
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }
            }

            Log.d(TAG, "Video başarıyla kodlandı: ${outputFile.name} (${frameFiles.size} kare, ${outputFile.length() / 1024} KB)")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Kareleri videoya dönüştürme hatası: ${e.message}", e)
            return@withContext false
        } finally {
            try {
                codec?.stop()
                codec?.release()
                inputSurface?.release()
                if (muxer != null) {
                    try { muxer.stop() } catch (ignored: Exception) {}
                    muxer.release()
                }
            } catch (ignored: Exception) {}
        }
    }
}
