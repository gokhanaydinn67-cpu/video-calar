package com.video.calar

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun reloadVideos(context: Context): List<Video> =
    withContext(Dispatchers.IO) { loadVideos(context) }

// Dosya adını değiştirir, uzantıyı korur.
fun renameVideo(context: Context, uri: Uri, newBase: String) {
    val resolver = context.contentResolver
    val current = resolver.query(
        uri,
        arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: return
    val ext = current.substringAfterLast('.', "")
    val finalName = if (ext.isEmpty()) newBase else "$newBase.$ext"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
    }
    resolver.update(uri, values, null, null)
}

fun shareVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Paylaş"))
}
