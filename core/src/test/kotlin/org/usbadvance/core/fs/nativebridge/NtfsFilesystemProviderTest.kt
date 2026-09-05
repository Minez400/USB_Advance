package org.usbadvance.core.fs.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions

class NtfsFilesystemProviderTest {

    @Test
    fun testNtfsProviderConfiguration() {
        val provider = NtfsFilesystemProvider()
        assertEquals("ntfs", provider.id)
        assertEquals(FilesystemType.NTFS, provider.filesystemType)
        assertEquals("NTFS", provider.displayName)
        assertFalse(provider.isRootRequired)
        assertEquals(32, provider.maxVolumeLabelLength)
        assertEquals(4096, provider.defaultClusterSize)
        assertTrue(provider.supportedClusterSizes.contains(4096))
        assertTrue(provider.supportedClusterSizes.contains(65536))
    }

    @Test
    fun testNtfsValidation() {
        val provider = NtfsFilesystemProvider()

        // Valid options (64 GB disk, label "MY_USB")
        val validOptions = FormatOptions(
            filesystemType = FilesystemType.NTFS,
            volumeLabel = "MY_USB",
            clusterSizeBytes = 4096
        )
        val validResult = provider.validateOptions(validOptions, 64L * 1024 * 1024 * 1024)
        assertTrue(validResult.isValid)
        assertTrue(validResult.errors.isEmpty())

        // Invalid: label too long (> 32 characters)
        val longLabelOptions = FormatOptions(
            filesystemType = FilesystemType.NTFS,
            volumeLabel = "A_VERY_LONG_VOLUME_LABEL_THAT_EXCEEDS_THIRTY_TWO_CHARS",
            clusterSizeBytes = 4096
        )
        val longLabelResult = provider.validateOptions(longLabelOptions, 64L * 1024 * 1024 * 1024)
        assertFalse(longLabelResult.isValid)
        assertTrue(longLabelResult.errorKeys.contains("validation_err_label_too_long"))

        // Invalid: partition too small (< 10 MB)
        val smallDiskResult = provider.validateOptions(validOptions, 5L * 1024 * 1024)
        assertFalse(smallDiskResult.isValid)
        assertTrue(smallDiskResult.errorKeys.contains("validation_err_partition_too_small"))
    }
}
