package com.example

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CapturedMediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateAdded: Long,
    val sizeBytes: Long
)

object CapturedMediaRepository {
    private const val TAG = "CapturedMediaRepo"

    suspend fun loadRecentCaptures(context: Context): List<CapturedMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CapturedMediaItem>()

        try {
            // 1. Ekran Görüntülerini Sorgula
            val imageProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            val imageSelection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val imageArgs = arrayOf("ScreenOverlay_%")

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                imageSelection,
                imageArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val date = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    items.add(CapturedMediaItem(id, uri, name, false, date, size))
                }
            }

            // 2. Video Kayıtlarını Sorgula
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE
            )
            val videoSelection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val videoArgs = arrayOf("ScreenRecord_%")

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                videoSelection,
                videoArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val date = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    items.add(CapturedMediaItem(id, uri, name, true, date, size))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Medyalar yüklenirken hata: ${e.message}", e)
        }

        return@withContext items.sortedByDescending { it.dateAdded }
    }

    suspend fun deleteItem(context: Context, item: CapturedMediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(item.uri, null, null)
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Medya silinemedi: ${e.message}", e)
            false
        }
    }
}
