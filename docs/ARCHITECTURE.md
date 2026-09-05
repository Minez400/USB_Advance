# System Architecture - USB Advance

## 1. Overview
**USB Advance** is engineered under Clean Architecture principles with strict separation of concerns. The primary objective is to deliver robust partitioning, formatting, and diagnostic capabilities for USB/OTG storage devices without requiring superuser (Root) privileges, while maintaining an optional Root block backend for internal MicroSD cards and `/dev/block/` nodes.

---

## 2. Module & Layer Diagram

```
                 +--------------------------------------+
                 |                 :app                 |
                 |  Jetpack Compose UI & Feature Layers |
                 +-------------------+------------------+
                                     |
                                     | depends on
                                     v
                 +--------------------------------------+
                 |                :core                 |
                 |  Low-Level Storage Library & Native  |
                 +-------------------+------------------+
                                     |
       +-----------------------------+-----------------------------+
       |                             |                             |
+------v------+               +------v------+               +------v------+
|  core.usb   |               | core.part   |               |  core.root  |
| (BOT & SCSI)|               | (MBR & GPT) |               | (libsu I/O) |
+------+------+               +------+------+               +-------------+
       |                             |
       +--------------+--------------+
                      |
             +--------v--------+
             |   core.native   | <--- High-Performance C++20 Formatter Engines
             | (exFAT, NTFS,   |      (CMake + NDK r27b with 16 KB ELF alignment)
             |  ext4, FAT32)   |
             +-----------------+
```

---

## 3. Service Provider Interface (SPI) Pattern

The core filesystem abstraction is modeled via the `FilesystemProvider` interface:

```kotlin
interface FilesystemProvider {
    val id: String
    val filesystemType: FilesystemType
    val displayName: String
    val supportedClusterSizes: List<Int>
    fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult
    suspend fun format(
        blockDevice: IBlockDevice,
        partition: IPartition,
        options: FormatOptions,
        progressCallback: FormatProgressCallback
    ): FormatResult
}
```

Each filesystem is completely isolated and pluggable:
* `ExFatFilesystemProvider`: Native C++20 exFAT format with automatic Allocation Bitmap and 128 KB Up-case table generation.
* `NtfsFilesystemProvider`: Native C++20 NTFS format with standard 4 KB clusters and valid Master File Table (MFT) structures.
* `Fat32FilesystemProvider`: Universal FAT32 formatting with compatibility across PCs, TVs, and retro consoles.
* `Ext4FilesystemProvider`: Native ext4 format with optional zero-journaling mode for flash longevity.
* `Fat16FilesystemProvider`: Legacy FAT16 format for industrial machinery and vintage hardware.

To add new formats in the future (such as F2FS or Btrfs), developers only need to implement `FilesystemProvider` and register it in `FilesystemRegistry.register()`, without altering the Compose UI or the USB transport layers.
