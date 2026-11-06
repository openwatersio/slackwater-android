package io.openwaters.slackwater.android.sharing

import android.content.Intent
import android.net.Uri
import android.print.PageRange
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TableExportTest {
    @Test fun rangesAreClippedMergedAndSortedByOriginalPage() {
        assertEquals(listOf(PageRange(1, 3), PageRange(5, 5)),
            selectedRanges(arrayOf(PageRange(5, 10), PageRange(2, 3), PageRange(1, 2)), 6))
        assertEquals(listOf(PageRange(0, 3)), selectedRanges(arrayOf(PageRange.ALL_PAGES), 4))
        assertTrue(selectedRanges(arrayOf(PageRange(8, 9)), 2).isEmpty())
    }

    @Test fun truncationPreservesUnicodeGraphemesAndSafePathComponents() {
        val joined = "👩🏽‍🔬"
        val prefix = "a".repeat(49)
        assertEquals(prefix + joined, safeTablePlace(prefix + joined + "next"))
        assertEquals(prefix + "e\u0301", safeTablePlace(prefix + "e\u0301next"))
        assertEquals("_north__sound", safeTablePlace("/north\\\u202Esound"))
        assertEquals("Slackwater", safeTablePlace(" "))
        val long = safeTablePlace(joined.repeat(100))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 160)
        assertTrue(long.isNotEmpty() && long.replace(joined, "").isEmpty())
    }

    @Test fun onlySharedCopiesAndUnfinishedPrintJobsSurviveClosingThePreview() {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        fun export(): TableExport {
            val dir = File(root, UUID.randomUUID().toString()).also { assertTrue(it.mkdir()) }
            val file = File(dir, "Harbour.pdf").also { it.writeText("preview") }
            return TableExport(file, 1, true, null) { error("Unused") }
        }
        val preview = export()
        preview.releasePreview()
        assertFalse(preview.file.parentFile!!.exists())
        val print = export()
        print.startPrint()
        print.startPrint()
        print.releasePreview()
        print.finishPrint()
        assertTrue(print.file.exists())
        print.finishPrint()
        assertFalse(print.file.exists())
        val shared = export()
        try {
            shared.handToShare {}
            shared.releasePreview()
            assertTrue(shared.file.exists())
        } finally { shared.file.parentFile!!.deleteRecursively() }
        val rejected = export()
        assertThrows(IllegalStateException::class.java) { rejected.handToShare { error("Cannot launch") } }
        rejected.releasePreview()
        assertFalse(rejected.file.exists())
    }

    @Suppress("DEPRECATION")
    @Test fun chooserGrantsOnlyReadAccessToThePreviewBytes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "shared-tables/${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "Harbour – Week.pdf")
        val bytes = "%PDF-1.4\n".toByteArray()
        try {
            file.writeBytes(bytes)
            val chooser = TableExport(file, 1, true, null) { error("Sharing must use the displayed file") }.shareIntent(app)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/pdf", send.type)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertFalse(app.packageManager.resolveContentProvider(uri.authority!!, 0)!!.exported)
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(app, uri.authority!!, File(app.cacheDir, "private-record"))
            }
        } finally { directory.deleteRecursively() }
    }
}
