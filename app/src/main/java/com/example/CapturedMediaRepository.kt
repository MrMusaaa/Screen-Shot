package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CapturedMediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateAdded: Long,
    val sizeBytes: Long,
    val file: File? = null
)

object CapturedMediaRepository {
    private const val TAG = "CapturedMediaRepo"

    suspend fun loadRecentCaptures(context: Context): List<CapturedMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CapturedMediaItem>()

        try {
            val capturesDir = File(context.filesDir, "captures")
            if (capturesDir.exists()) {
                val files = capturesDir.listFiles() ?: emptyArray()
                val authority = "${context.packageName}.fileprovider"

                for (file in files) {
                    if (file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".mp4"))) {
                        val isVideo = file.name.endsWith(".mp4")
                        val uri = try {
                            FileProvider.getUriForFile(context, authority, file)
                        } catch (e: Exception) {
                            Uri.fromFile(file)
                        }
                        items.add(
                            CapturedMediaItem(
                                id = file.lastModified(),
                                uri = uri,
                                name = file.name,
                                isVideo = isVideo,
                                dateAdded = file.lastModified() / 1000,
                                sizeBytes = file.length(),
                                file = file
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Medyalar yüklenirken hata: ${e.message}", e)
        }

        return@withContext items.sortedByDescending { it.dateAdded }
    }

    suspend fun deleteItem(context: Context, item: CapturedMediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            if (item.file != null && item.file.exists()) {
                item.file.delete()
            } else {
                context.contentResolver.delete(item.uri, null, null) > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Medya silinemedi: ${e.message}", e)
            false
        }
    }
}
