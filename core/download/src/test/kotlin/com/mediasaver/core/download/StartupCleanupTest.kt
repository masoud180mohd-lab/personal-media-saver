package com.mediasaver.core.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediasaver.core.database.MediaSaverDatabase
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.download.muxing.DefaultMuxingEngine
import com.mediasaver.core.download.queue.DownloadQueueManager
import com.mediasaver.core.download.retry.RetryPolicy
import com.mediasaver.core.download.worker.DownloadWorker
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.core.storage.TempFileManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StartupCleanupTest {
    @Test fun `recovery protects active files fails exhausted tasks and does not reset twice`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MediaSaverDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.downloadDao()
            val temp = TempFileManager(context)
            val worker = DownloadWorker(OkHttpClient(), dao, temp, MediaStoreManager(context), DefaultMuxingEngine())
            val queue = DownloadQueueManager(context, dao, db.streamOptionDao(), worker, temp, RetryPolicy())
            queue.maxConcurrentDownloads = 0
            val active = DownloadEntity(id = "active", url = "url", mediaId = "m", platform = "YOUTUBE", status = "DOWNLOADING")
            dao.insertAll(listOf(active, active.copy(id = "spent", status = "MUXING", retryCount = 3)))
            fun old(id: String) = temp.createTempDownloadFile(id, "_vid.tmp").apply { writeText("fixture"); setLastModified(1L) }
            val protected = old("active")
            val orphan = old("gone")
            queue.performStartupRecovery()
            assertEquals("QUEUED", dao.getById("active")?.status)
            assertEquals("FAILED", dao.getById("spent")?.status)
            assertTrue(protected.exists())
            assertFalse(orphan.exists())
            dao.updateStatus("active", "DOWNLOADING")
            queue.performStartupRecovery()
            assertEquals("DOWNLOADING", dao.getById("active")?.status)
            assertTrue(protected.exists())
        } finally { db.close() }
    }
}
