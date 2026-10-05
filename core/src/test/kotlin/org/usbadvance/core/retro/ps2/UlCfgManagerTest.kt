package org.usbadvance.core.retro.ps2

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UlCfgManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testUlGameEntrySerialization() {
        val entry = UlGameEntry(
            title = "God of War II",
            gameId = "SCUS_974.81",
            chunkCount = 8,
            mediaType = UlGameEntry.MediaType.DVD
        )

        assertEquals("ul.SCUS_974.81", entry.imagePrefix)
        assertEquals("SCUS_974.81", entry.gameId)
        assertEquals("ul.SCUS_974.81.00", entry.getChunkFileName(0))
        assertEquals("ul.SCUS_974.81.07", entry.getChunkFileName(7))

        val buffer = ByteBuffer.allocate(UlGameEntry.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        UlGameEntry.writeTo(buffer, entry)
        buffer.flip()

        assertEquals(UlGameEntry.RECORD_SIZE, buffer.limit())

        val parsed = UlGameEntry.parseFrom(buffer)
        assertNotNull(parsed)
        assertEquals("God of War II", parsed?.title)
        assertEquals("ul.SCUS_974.81", parsed?.imagePrefix)
        assertEquals("SCUS_974.81", parsed?.gameId)
        assertEquals(8, parsed?.chunkCount)
        assertEquals(UlGameEntry.MediaType.DVD, parsed?.mediaType)
    }

    @Test
    fun testPrefixLengthSafety() {
        // Test that excessively long IDs never exceed 14 characters
        val longPrefix = UlGameEntry.buildPrefix("VERY_LONG_PS2_GAME_SERIAL_12345")
        assertTrue(longPrefix.length <= 14)
        assertTrue(longPrefix.startsWith("ul."))

        val standardPrefix = UlGameEntry.buildPrefix("SLUS_200.62")
        assertEquals("ul.SLUS_200.62", standardPrefix)
        assertEquals(14, standardPrefix.length)
    }

    @Test
    fun testReadAndWriteCatalog() = runBlocking {
        val ulFile = tempFolder.newFile("ul.cfg")
        val manager = UlCfgManager()

        val entries = listOf(
            UlGameEntry("Resident Evil 4", "SLUS_211.34", 5, UlGameEntry.MediaType.DVD),
            UlGameEntry("Crash Bandicoot", "SCES_003.44", 1, UlGameEntry.MediaType.CD)
        )

        manager.writeCatalog(ulFile, entries)
        assertEquals(128L, ulFile.length()) // 2 entries * 64 bytes = 128 bytes

        val loaded = manager.readCatalog(ulFile)
        assertEquals(2, loaded.size)
        assertEquals("Resident Evil 4", loaded[0].title)
        assertEquals("SLUS_211.34", loaded[0].gameId)
        assertEquals(UlGameEntry.MediaType.DVD, loaded[0].mediaType)

        assertEquals("Crash Bandicoot", loaded[1].title)
        assertEquals("SCES_003.44", loaded[1].gameId)
        assertEquals(UlGameEntry.MediaType.CD, loaded[1].mediaType)
    }

    @Test
    fun testSplitSmallIsoSimulation() = runBlocking {
        val sourceIso = tempFolder.newFile("test_game.iso")
        val targetDir = tempFolder.newFolder("output_ps2")

        // Write simulated SYSTEM.CNF in ISO
        val cnfText = "BOOT2 = cdrom0:\\SLUS_200.62;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n"
        sourceIso.writeBytes(cnfText.toByteArray(Charsets.US_ASCII))

        val manager = UlCfgManager()
        val entry = manager.splitIso(
            sourceIso = sourceIso,
            targetDirectory = targetDir,
            customTitle = "Test Game"
        ) { _, _ -> }

        assertEquals("Test Game", entry.title)
        assertEquals("SLUS_200.62", entry.gameId)
        assertEquals(1, entry.chunkCount)

        val chunk0 = File(targetDir, entry.getChunkFileName(0))
        assertTrue(chunk0.exists())
        assertEquals(sourceIso.length(), chunk0.length())

        val catalog = manager.readCatalog(File(targetDir, "ul.cfg"))
        assertEquals(1, catalog.size)
        assertEquals("SLUS_200.62", catalog[0].gameId)
    }
}
