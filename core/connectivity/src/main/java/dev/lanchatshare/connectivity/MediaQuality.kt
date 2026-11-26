package dev.lanchatshare.connectivity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.mediaStore by preferencesDataStore("media_quality")
private val standard = booleanPreferencesKey("standard")
fun Context.standardMedia() = mediaStore.data.map { it[standard] ?: false }
suspend fun Context.setStandardMedia(value: Boolean) { mediaStore.edit { it[standard] = value } }

internal fun Context.tinyPreview(uri: Uri): ByteArray? = try {
    val image = contentResolver.loadThumbnail(uri,Size(96,96),null)
    try {
        ByteArrayOutputStream().use { buffer ->
            image.compress(Bitmap.CompressFormat.JPEG,50,buffer)
            buffer.toByteArray().takeIf { it.size in 1..8192 }
        }
    } finally { image.recycle() }
} catch (_: Exception) { null }

internal fun Context.standardPhoto(uri: Uri, output: File) {
    val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width,info.size.height)
        if (longest > 1600) decoder.setTargetSize((info.size.width * 1600L / longest).toInt().coerceAtLeast(1),
            (info.size.height * 1600L / longest).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try { output.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG,82,it)) } }
    finally { image.recycle() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun Context.standardVideo(uri: Uri, output: File) = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine<Unit> { cont ->
        val job = Transformer.Builder(this@standardVideo).setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            }).build()
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { job.cancel() } }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(),
            listOf(Presentation.createForWidthAndHeight(1280,720,Presentation.LAYOUT_SCALE_TO_FIT)))).build()
        job.start(edited,output.path)
    }
}
