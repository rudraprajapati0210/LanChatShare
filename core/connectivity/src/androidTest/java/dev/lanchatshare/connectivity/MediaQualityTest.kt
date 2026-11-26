package dev.lanchatshare.connectivity

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MediaQualityTest {
    @Test fun originalPreservesBytesAndStandardDownsizes() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = c.contentResolver
        val id = UUID.randomUUID().toString()
        val store = TrustStore(c,id)
        val peer = TrustedPeer(UUID.randomUUID().toString(),"Media test",0,"fixture")
        val originalPreference = c.standardMedia().first()
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put("_display_name","$id.png"); put("mime_type","image/png")
        }))
        try {
            store.dao.save(peer)
            val bitmap = Bitmap.createBitmap(1920,960,Bitmap.Config.ARGB_8888)
            try { resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
            val originalHash = fileHash(resolver.openInputStream(uri)!!)
            c.setStandardMedia(false)
            store.transfers.add(listOf(peer),uri)
            val original = store.transfers.all.first().single()
            assertEquals(originalHash,original.hash)
            c.setStandardMedia(true)
            store.transfers.add(listOf(peer),uri)
            val small = store.transfers.all.first().single { it.mime == "image/jpeg" }
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(store.transfers.file(small).path,dimensions)
            assertEquals(1600,dimensions.outWidth)
            assertEquals(800,dimensions.outHeight)
            assertNotEquals(original.hash,small.hash)
        } finally {
            c.setStandardMedia(originalPreference)
            store.transfers.all.first().forEach { store.transfers.file(it).delete(); store.transfers.previewFile(it).delete() }
            resolver.delete(uri,null,null)
            store.close(); c.deleteDatabase(id)
        }
        Unit
    }
}
