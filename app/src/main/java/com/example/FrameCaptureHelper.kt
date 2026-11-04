package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * aracılığıyla en son ekran karesini yakalar.
 * Sistem bazlı ekran görüntüsü tetikleyicilerini (Activity.ScreenCaptureCallback vb.) çalıştırmaz,
 * doğrudan bellek tamponundan (ImageReader) Bitmap oluşturur ve MediaStore'a kaydeder.
 */
object FrameCaptureHelper {

    private const val TAG = "FrameCaptureHelper"

    /**
     * ImageReader'dan en son kareyi alır, RowStride/PixelStride hesaplamasını yapar,
     * temiz bir Bitmap'e dönüştürür ve MediaStore'a JPEG olarak kaydeder.
     */
    suspend fun captureAndSaveFrame(
        imageReader: ImageReader,
        context: Context,
        screenWidth: Int,
        screenHeight: Int
    ): Uri? = withContext(Dispatchers.IO) {
        var image: Image? = null
        var bitmap: Bitmap? = null
        var cleanBitmap: Bitmap? = null

        try {
            // Tampondaki en güncel ekran karesini al (kare gelene kadar kısa aralıklarla bekle)
            var attempts = 0
            while (image == null && attempts < 15) {
                image = try {
                    imageReader.acquireLatestImage() ?: imageReader.acquireNextImage()
                } catch (e: Exception) {
                    null
                }
                if (image == null) {
                    kotlinx.coroutines.delay(30)
                    attempts++
                }
            }

            if (image == null) {
                Log.w(TAG, "ImageReader tamponunda okunabilir kare bulunamadı ($attempts deneme sonrası).")
                return@withContext null
            }

            val planes = image.planes
            if (planes.isEmpty()) {
                Log.e(TAG, "Image planes boş.")
                return@withContext null
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            // Bellek tamponu genişliğinde geçici Bitmap oluştur
            val bufferWidth = screenWidth + (if (pixelStride > 0) rowPadding / pixelStride else 0)
            bitmap = Bitmap.createBitmap(
                bufferWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )

            // Piksel verilerini bellek tamponundan kopyala
            bitmap.copyPixelsFromBuffer(buffer)

            // Eğer rowPadding varsa gerçek ekran çözünürlüğüne kırp
            cleanBitmap = if (rowPadding > 0 || bufferWidth != screenWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }

            // MediaStore'a kaydet
            return@withContext saveBitmapToGallery(context, cleanBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Kare yakalama veya kaydetme sırasında hata: ${e.message}", e)
            return@withContext null
        } finally {
            try {
                image?.close()
                if (bitmap != null && bitmap != cleanBitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Yakalanan Bitmap nesnesini cihazın Pictures/Screenshots galerisine kaydeder.
     */
    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri? {
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
