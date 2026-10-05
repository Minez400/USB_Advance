#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace usbadvance {

#pragma pack(push, 1)

/**
 * exFAT Main Boot Sector (Sector 0)
 * Total: 512 bytes minimum
 */
struct ExFatBootRecord {
    uint8_t  jump_boot[3];               // 0x00: 0xEB 0x76 0x90
    char     fs_name[8];                 // 0x03: "EXFAT   "
    uint8_t  must_be_zero[53];           // 0x0B
    uint64_t partition_offset;          // 0x40: Starting LBA
    uint64_t volume_length;             // 0x48: Volume size in sectors
    uint32_t fat_offset;                // 0x50: FAT start sector (relative to partition)
    uint32_t fat_length;                // 0x54: FAT size in sectors
    uint32_t cluster_heap_offset;       // 0x58: Cluster 2 start sector (relative to partition)
    uint32_t cluster_count;             // 0x5C: Total data clusters
    uint32_t root_dir_first_cluster;    // 0x60: First cluster of root directory
    uint32_t volume_serial;             // 0x64
    uint16_t fs_revision;               // 0x68
    uint16_t volume_flags;              // 0x6A
    uint8_t  bytes_per_sector_shift;    // 0x6C: power of 2 (9 = 512, 12 = 4096)
    uint8_t  sectors_per_cluster_shift; // 0x6D: power of 2
    uint8_t  number_of_fats;            // 0x6E: usually 1
    uint8_t  drive_select;              // 0x6F
    uint8_t  percent_in_use;            // 0x70
    uint8_t  reserved[7];               // 0x71
    uint8_t  boot_code[390];            // 0x78
    uint16_t boot_signature;            // 0x1FE: 0xAA55
};

/**
 * Generic 32-byte exFAT Directory Entry Header
 */
struct ExFatEntryGeneric {
    uint8_t type;                       // MSB = 1: in-use, MSB = 0: unused/deleted
    uint8_t data[31];
};

/**
 * Type 0x83: Volume Label Directory Entry
 */
struct ExFatVolumeLabelEntry {
    uint8_t  type;                      // 0x83
    uint8_t  character_count;           // Length in UTF-16 characters (max 11)
    uint16_t volume_label[11];          // UTF-16LE characters
    uint8_t  reserved[8];
};

/**
 * Type 0x85: File Directory Entry (Primary entry for file/folder)
 */
struct ExFatFileDirectoryEntry {
    uint8_t  type;                      // 0x85
    uint8_t  secondary_count;           // Number of secondary entries (Stream + Names)
    uint16_t set_checksum;              // Checksum of directory set
    uint16_t file_attributes;           // 0x10 = Directory, 0x01 = ReadOnly, 0x02 = Hidden, 0x20 = Archive
    uint16_t reserved1;
    uint32_t create_timestamp;          // Dos/exFAT timestamp
    uint32_t last_modified_timestamp;
    uint32_t last_access_timestamp;
    uint8_t  create_10ms_increment;
    uint8_t  modify_10ms_increment;
    uint8_t  create_utc_offset;
    uint8_t  modify_utc_offset;
    uint8_t  access_utc_offset;
    uint8_t  reserved2[7];
};

/**
 * Type 0xC0: Stream Extension Directory Entry (Secondary entry 1)
 */
struct ExFatStreamExtensionEntry {
    uint8_t  type;                      // 0xC0
    uint8_t  general_secondary_flags;   // Bit 0 = AllocPossible, Bit 1 = NoFatChain
    uint8_t  reserved1;
    uint8_t  name_length;               // UTF-16 character count
    uint16_t name_hash;                 // Hash of lowercase name
    uint16_t reserved2;
    uint64_t valid_data_length;         // Actual data length written
    uint32_t reserved3;
    uint32_t first_cluster;             // Starting cluster number
    uint64_t data_length;               // Total file size allocated in bytes
};

/**
 * Type 0xC1: File Name Directory Entry (Secondary entry 2..N)
 */
struct ExFatFileNameEntry {
    uint8_t  type;                      // 0xC1
    uint8_t  general_secondary_flags;
    uint16_t file_name_part[15];        // Up to 15 UTF-16LE characters
};

#pragma pack(pop)

/**
 * Parsed File / Directory Node in memory
 */
struct ExFatNode {
    std::string name;
    uint64_t size_bytes = 0;
    uint64_t valid_data_length = 0;
    uint32_t first_cluster = 0;
    bool is_directory = false;
    bool is_read_only = false;
    bool is_hidden = false;
    bool is_system = false;
    bool no_fat_chain = false;          // True = 100% contiguous cluster run (PS2 OPL optimal)
    uint64_t created_epoch_ms = 0;
    uint64_t modified_epoch_ms = 0;
};

/**
 * Cluster run / extent for fragmentation analysis
 */
struct ClusterExtent {
    uint32_t start_cluster;
    uint32_t cluster_count;
};

} // namespace usbadvance
