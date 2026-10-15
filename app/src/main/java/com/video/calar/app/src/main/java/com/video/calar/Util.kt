package com.video.calar

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

const val MODE_NONE = 0
const val MODE_SEEK = 1
const val MODE_BRIGHTNESS = 2
const val MODE_VOLUME = 3

data class Video(
    val id: Long,
    val name: String,
    val size: Long,
    val duration: Long,
    val dateAdded: Long,
    val uri: Uri
)

enum class SortMode(val label: String) {
    NAME("Ada göre"),
    SIZE("Boyuta göre"),
    DATE("Tarihe göre")
}

object PlaylistStore {
    private const val PREFS = "playlists"
    private const val KEY_NAMES = "names"

    fun load(context: Context): Map<String, List<Long>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        val result = LinkedHashMap<String, List<Long>>()
        for (name in names.sorted()) {
            val raw = prefs.getString("p_$name", "") ?: ""
            val ids = raw.split(",").filter { it.isNotBlank() }.map { it.toLong() }
            result[name] = ids
        }
        return result
    }

    fun save(context: Context, data: Map<String, List<Long>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val old = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        for (name in old) {
            editor.remove("p_$name")
        }
        editor.putStringSet(KEY_NAMES, data.keys.toSet())
        for ((name, ids) in data) {
            editor.putString("p_$name", ids.joinToString(","))
        }
        editor.apply()
    }
}

fun Video.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
        .build()

fun loadVideos(context: Context): List<Video> {
    val result = mutableListOf<Video>()
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED
    )
    context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val rawName = c.getString(nameCol) ?: "Bilinmeyen"
            result.add(
                Video(
                    id = id,
                    name = rawName.substringBeforeLast('.'),
                    size = c.getLong(sizeCol),
                    duration = c.getLong(durCol),
                    dateAdded = c.getLong(dateCol),
                    uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    )
                )
            )
        }
    }
    return result
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String {
    return if (bytes >= 1_073_741_824L) {
        "%.1f GB".format(bytes / 1_073_741_824.0)
    } else {
        "%.0f MB".format(bytes / 1_048_576.0)
    }
}

fun openExternal(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Oynatıcı seç"))
}
