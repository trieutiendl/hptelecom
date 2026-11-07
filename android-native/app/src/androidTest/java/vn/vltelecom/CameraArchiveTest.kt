package vn.vltelecom

import android.app.Instrumentation
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Local persistence tests only: no camera, MediaStore, network, or real account is used. */
@RunWith(AndroidJUnit4::class)
class CameraArchiveTest {
    private class Fixture : AutoCloseable {
        // A separate Application wrapper keeps accountScope isolated from the running app.
        // onCreate is deliberately not called, so this fixture starts no sync/lifecycle work.
        val app = Instrumentation.newApplication(
            WorkApp::class.java,
            InstrumentationRegistry.getInstrumentation().targetContext
        ) as WorkApp
        private val accounts = mutableListOf<String>()

        fun account(): String = (hexId() + "-" + hexId()).also { accounts.add(it) }

        fun root(account: String): File {
            check(account in accounts)
            return File(app.filesDir, "accounts/$account")
        }

        fun source(account: String): File = File(root(account), "photos/camera-${UUID.randomUUID()}.jpg").apply {
            check(parentFile!!.mkdirs() || parentFile!!.isDirectory)
            // These tests copy bytes; decoding and rendering are outside their scope.
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte()))
        }

        override fun close() {
            val parent = File(app.filesDir, "accounts").canonicalFile
            accounts.forEach { account ->
                val directory = root(account).canonicalFile
                check(directory.parentFile == parent && directory.name == account)
                check(directory.deleteRecursively()) { "Không dọn được dữ liệu tài khoản kiểm thử." }
            }
        }

        private fun hexId() = UUID.randomUUID().toString().replace("-", "")
    }

    private fun capture(source: File): Intent = Intent()
        .putExtra("path", source.path)
        .putExtra("raw", source.path)
        .putExtra("time", 1791000000123L)
        .putExtra("name", "Cột số 12")
        .putExtra("address", "Đắk Lắk")
        .putExtra("note", "Ảnh chụp độc lập")
        .putExtra("lat", 12.7135)
        .putExtra("lon", 108.071952)
        .putExtra("accuracy", 4.5f)

    @Test fun repeatedCaptureResultKeepsOneReceiptAndOriginalPhotographer() {
        Fixture().use { fixture ->
            val account = fixture.account()
            val source = fixture.source(account)
            val first = CameraArchive.recordCapture(fixture.app, account, "Nguyễn An", "nguyenan", capture(source))
            val second = CameraArchive.recordCapture(fixture.app, account, "Another user", "other", capture(source).putExtra("note", "Changed"))

            assertEquals(first.id, second.id)
            assertEquals("Nguyễn An", second.photographer)
            assertEquals("nguyenan", second.username)
            val persisted = CameraArchive.list(fixture.app, account)
            assertEquals(1, persisted.size)
            assertEquals(first, persisted.single())
            assertEquals("Ảnh chụp độc lập", persisted.single().note)
            assertEquals(12.7135, persisted.single().lat, 0.000001)
            assertEquals(1791000000123L, persisted.single().capturedAt)
            assertFalse(persisted.single().serverQueued)
            assertTrue(persisted.single().galleryUri.isEmpty())
            assertEquals(source.canonicalPath, CameraArchive.source(fixture.app, persisted.single()).canonicalPath)
            assertTrue(source.isFile)
        }
    }

    @Test fun anotherAccountCannotRecordOrOpenThePhotographersSource() {
        Fixture().use { fixture ->
            val owner = fixture.account()
            val other = fixture.account()
            val source = fixture.source(owner)
            val photo = CameraArchive.recordCapture(fixture.app, owner, "Owner", "owner", capture(source))

            var recordRejected = false
            try { CameraArchive.recordCapture(fixture.app, other, "Other", "other", capture(source)) }
            catch (_: IllegalStateException) { recordRejected = true }
            assertTrue("A photo from one account must not be recorded for another.", recordRejected)

            var openRejected = false
            try { CameraArchive.source(fixture.app, photo.copy(account = other)) }
            catch (_: IllegalStateException) { openRejected = true }
            assertTrue("Changing receipt ownership must not grant access to the source.", openRejected)
            assertTrue(CameraArchive.list(fixture.app, other).isEmpty())
            assertFalse(File(fixture.root(other), "camera-library").exists())
            assertEquals(listOf(photo), CameraArchive.list(fixture.app, owner))
            assertTrue(source.isFile)
        }
    }

    @Test fun retryingStableUploadIdKeepsOneQueueEntryAndOriginalCapture() = runBlocking {
        Fixture().use { fixture ->
            val account = fixture.account()
            fixture.app.switchAccount(account)
            val source = fixture.source(account)
            val bytes = source.readBytes()
            val id = UUID.randomUUID().toString()
            val details = JSONObject().put("cameraArchiveId", id).put("locationName", "Cột 12").put("note", "Ảnh đầu tiên")

            PhotoLibrary.queueServerUpload(fixture.app, source, 1791000000123L, details, account, id)
            PhotoLibrary.queueServerUpload(fixture.app, source, 1791000000999L,
                JSONObject().put("note", "Không ghi đè khi thử lại"), account, id)

            val pending = File(fixture.root(account), "photo-library-pending")
            assertEquals(setOf("$id.jpg", "$id.json"), pending.listFiles()!!.map { it.name }.toSet())
            assertArrayEquals(bytes, File(pending, "$id.jpg").readBytes())
            val receipt = JSONObject(File(pending, "$id.json").readText(Charsets.UTF_8))
            assertEquals(1791000000123L, receipt.getLong("capturedAt"))
            assertEquals(id, receipt.getJSONObject("details").getString("cameraArchiveId"))
            assertEquals("Ảnh đầu tiên", receipt.getJSONObject("details").getString("note"))
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun accountChangeRejectsQueueWithoutWritingToEitherAccount() = runBlocking {
        Fixture().use { fixture ->
            val original = fixture.account()
            val current = fixture.account()
            val source = fixture.source(original)
            fixture.app.switchAccount(current)

            var rejected = false
            try {
                PhotoLibrary.queueServerUpload(fixture.app, source, 1791000000123L,
                    JSONObject(), original, UUID.randomUUID().toString())
            } catch (_: IllegalStateException) { rejected = true }

            assertTrue("An old capture must not be queued under the new login.", rejected)
            assertFalse(File(fixture.root(original), "photo-library-pending").exists())
            assertFalse(File(fixture.root(current), "photo-library-pending").exists())
            assertTrue(source.isFile)
        }
    }
}
