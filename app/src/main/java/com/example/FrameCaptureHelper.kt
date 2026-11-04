package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FrameCaptureHelper
 * 
 * Doğrudan Android Grafik Katmanı (SurfaceFlinger) tamponundan VirtualDisplay ve ImageReader
 * aracılığıyla İSTEK ÜZERİNE (On-Demand) en son ekran karesini yakalar.
 *
 * Gemini Asistan mimarisi:
 * Arka planda sürekli bir VirtualDisplay açık tutulmaz; sadece kullanıcı 'Ekranı Çek' dediğinde
 * ~50ms süreyle VirtualDisplay oluşturulur, taze kare yakalanır ve derhal serbest bırakılır.
 * Bu sayede:
 * 1. WhatsApp, Snapchat, Instagram gibi sohbet uygulamalarında 'Ekran görüntüsü alındı' veya
 *    ekran kaydı uyarıları asla sebepsiz yere tetiklenmez.
 * 2. Bellek tamponu (ImageReader) asla tıkanmaz veya eski karelerde donup kalmaz.
 */
object FrameCaptureHelper {

    private const val TAG = "FrameCaptureHelper"

    /**
     * İsteğe bağlı olarak VirtualDisplay ve ImageReader oluşturur, ilk taze kareyi bekler,
     * Bitmap'e dönüştürür, MediaStore'a kaydeder ve kaynakları derhal serbest bırakır.
     */
    suspend fun captureFrameOnDemand(
        mediaProjection: MediaProjection,
        context: Context,
        screenWidth: Int,
        screenHeight: Int,
        screenDensity: Int
    ): Uri? = withContext(Dispatchers.IO) {
        val handlerThread = HandlerThread("FrameCaptureThread").apply { start() }
        val backgroundHandler = Handler(handlerThread.looper)

        val imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        val deferredImage = CompletableDeferred<Image?>()
        var virtualDisplay: VirtualDisplay? = null

        try {
            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val img = reader.acquireLatestImage()
                    if (img != null && !deferredImage.isCompleted) {
                        deferredImage.complete(img)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Image edinilirken hata: ${e.message}", e)
                    if (!deferredImage.isCompleted) deferredImage.complete(null)
                }
            }, backgroundHandler)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture_OnDemand",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                backgroundHandler
            )

            // Ekran kartından taze kare gelmesi için azami 1500ms bekle (genelde 16-33ms içinde gelir)
            val image = withTimeoutOrNull(1500) {
                deferredImage.await()
            }

            if (image == null) {
                Log.w(TAG, "ImageReader tamponundan kare zaman aşımına uğradı.")
                return@withContext null
            }

            val planes = image.planes
            if (planes.isEmpty()) {
                Log.e(TAG, "Image planes boş.")
                image.close()
                return@withContext null
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bufferWidth = screenWidth + (if (pixelStride > 0) rowPadding / pixelStride else 0)
            val rawBitmap = Bitmap.createBitmap(
                bufferWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            rawBitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cleanBitmap = if (rowPadding > 0 || bufferWidth != screenWidth) {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                rawBitmap.recycle()
                cropped
            } else {
                rawBitmap
            }

            val uri = saveBitmapToGallery(context, cleanBitmap)
            cleanBitmap.recycle()
            return@withContext uri

        } catch (e: Exception) {
            Log.e(TAG, "captureFrameOnDemand hatası: ${e.message}", e)
            return@withContext null
        } finally {
            try {
                virtualDisplay?.release()
                imageReader.close()
                handlerThread.quitSafely()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Yakalanan Bitmap nesnesini cihazın Pictures/ScreenCaptures galerisine kaydeder.
     */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ScreenOverlay_$timeStamp.jpg"
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenCaptures")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (imageUri != null) {
            try {
                resolver.openOutputStream(imageUri)?.use { outputStream: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    outputStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }

                Log.d(TAG, "Ekran görüntüsü başarıyla kaydedildi: $imageUri")
                return imageUri
            } catch (e: Exception) {
                Log.e(TAG, "Bitmap kaydedilirken yazma hatası: ${e.message}", e)
                try {
                    resolver.delete(imageUri, null, null)
                } catch (ignored: Exception) {}
            }
        } else {
            // Legacy dosya kaydı fallback (API < 29)
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = File(picturesDir, "ScreenCaptures")
                if (!targetDir.exists()) targetDir.mkdirs()
                val file = File(targetDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
                return Uri.fromFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Legacy kayıt hatası: ${e.message}", e)
            }
        }
        return null
    }
}
