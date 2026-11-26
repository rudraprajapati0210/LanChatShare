package dev.lanchatshare.connectivity

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.paging.PagingSource
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryTest {
    @Test fun filteredPageAndDeletionShareOnePhysicalFile() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val db = UUID.randomUUID().toString()
        val store = TrustStore(c,db)
        val peers = listOf("first","second").map { TrustedPeer(UUID.randomUUID().toString(),it,0,it) }
        try {
            peers.forEach { store.dao.save(it) }
            val sample = ByteArray(100) { it.toByte() }
            store.transfers.stage(peers,"100% draft.pdf","application/pdf") { sample.inputStream() }
            val rows = store.transfers.all.first()
            val source = store.transfers.dao.library(LibraryFilter(text = "% draft",kind = FileKind.Documents,peer = peers[0].id).query())
            val page = source.load(PagingSource.LoadParams.Refresh(null,16,false)) as PagingSource.LoadResult.Page<Int, Transfer>
            assertEquals(peers[0].id,page.data.single().peer)
            assertEquals(100L,store.transfers.storageUsage.first())
            rows.forEach { store.transfers.action(it,"cancelled") }
            assertTrue(store.transfers.removeLocal(rows[0]))
            assertTrue(store.transfers.file(rows[1]).exists())
            assertTrue(store.transfers.removeLocal(rows[1]))
            assertFalse(store.transfers.file(rows[0]).exists())
            assertEquals(0L,store.transfers.storageUsage.first())
        } finally { store.close(); c.deleteDatabase(db) }
        Unit
    }
}
