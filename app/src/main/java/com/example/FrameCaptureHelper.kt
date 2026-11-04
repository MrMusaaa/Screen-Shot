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
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * FrameCaptureHelper
 * 
 * Gemini Asistan Mimarisi:
 * 1. Asla kamuya açık MediaStore (Pictures/Screenshots) veritabanına doğrudan kayıt YAPMAZ.
 *    Instagram, Snapchat, WhatsApp vb. uygulamalar MediaStore'u ContentObserver ile dinleyerek
 *    "ekran görüntüsü alındı" uyarısı verir. Özel uygulama dizini (sandbox) kullanıldığında
 *    hiçbir dış uygulama bu kaydı göremez veya algılayamaz.
 * 2. Arka planda sürekli VirtualDisplay çalıştırmaz. Sadece 'Ekranı Çek' denildiğinde ~30ms
 *    için oluşturup derhal kapatır.
 */
object FrameCaptureHelper {

    private const val TAG = "FrameCaptureHelper"

    /**
     * İsteğe bağlı olarak VirtualDisplay ve ImageReader oluşturur, ilk taze kareyi bekler,
     * temiz Bitmap'e dönüştürür ve UYGULAMANIN ÖZEL ALANINA (Sandbox) kaydeder.
     * Karşı tarafa veya harici uygulamalara kesinlikle bildirim gitmez.
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

            // MediaStore'a değil, uygulamanın kendi gizli kum havuzuna (sandbox) kaydet!
            val uri = saveBitmapToPrivateStorage(context, cleanBitmap)
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
     * Bitmap nesnesini uygulamanın özel sandbox alanına kaydeder.
     * Bu alan işletim sisteminin MediaStore dizinlerinden tamamen izoledir.
     * Bu sayede Snapchat / Instagram / WhatsApp / Bankacılık uygulamaları
     * ContentObserver ile ekran görüntüsünü ASLA tespit edemez.
     */
    fun saveBitmapToPrivateStorage(context: Context, bitmap: Bitmap): Uri? {
        try {
            val capturesDir = File(context.filesDir, "captures").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "cap_${System.currentTimeMillis()}.jpg"
            val targetFile = File(capturesDir, fileName)

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
                out.flush()
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, targetFile)
            Log.d(TAG, "Özel alana gizlice kaydedildi (Sıfır bildirim): $uri")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Gizli kayıt hatası: ${e.message}", e)
            return null
        }
    }

    /**
     * Kullanıcı isteğe bağlı olarak görseli cihazın ana genel galerisine aktarmak isterse kullanılır.
     */
    fun exportToPublicGallery(context: Context, sourceFile: File): Uri? {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenCaptures")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                resolver.openOutputStream(imageUri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                return imageUri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kamu galerisine aktarma hatası: ${e.message}", e)
        }
        return null
    }
}
