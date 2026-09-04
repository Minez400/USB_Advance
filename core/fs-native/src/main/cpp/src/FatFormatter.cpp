#include "FatFormatter.hpp"
#include <cstring>
#include <vector>
#include <chrono>
#include <random>
#include <algorithm>

namespace usbadvance {

uint32_t FatFormatter::calculateOptimalFat32ClusterSize(uint64_t capacity_bytes) {
    // Standard cluster size recommendations per Microsoft FAT32 specification
    if (capacity_bytes < 8ULL * 1024 * 1024 * 1024) { // < 8 GB
        return 4096; // 4 KB
    } else if (capacity_bytes < 16ULL * 1024 * 1024 * 1024) { // 8 - 16 GB
        return 8192; // 8 KB
    } else if (capacity_bytes < 32ULL * 1024 * 1024 * 1024) { // 16 - 32 GB
        return 16384; // 16 KB
    } else {
        return 32768; // 32 KB
    }
}

uint32_t FatFormatter::generateVolumeSerial() {
    auto now = std::chrono::system_clock::now().time_since_epoch().count();
    std::mt19937 gen(static_cast<unsigned int>(now));
    std::uniform_int_distribution<uint32_t> dist;
    return dist(gen);
}

bool FatFormatter::formatFat32(
    const NativeFormatParams& params,
    WriteSectorsFn write_fn,
    ProgressFn progress_fn
) {
    progress_fn(5.0f, "Calculating BIOS Parameter Block (FAT32) parameters...");

    const uint32_t sector_size = params.sector_size;
    const uint64_t total_sectors = params.sector_count;
    const uint64_t capacity_bytes = total_sectors * sector_size;

    uint32_t cluster_size = params.cluster_size_bytes;
    if (cluster_size == 0) {
        cluster_size = calculateOptimalFat32ClusterSize(capacity_bytes);
    }
    const uint8_t sectors_per_cluster = static_cast<uint8_t>(cluster_size / sector_size);
    const uint16_t reserved_sectors = 32;
    const uint8_t num_fats = 2;

    // Exact sectors-per-FAT calculation per Microsoft FAT32 specification:
    // Dmp = TotalSectors - ReservedSectors
    // Fatsz = (Dmp * 4) / ((SecPerClus * SectorSize) + (NumFATs * 4))
    uint64_t dmp = total_sectors - reserved_sectors;
    uint64_t fat_bytes = ((dmp / sectors_per_cluster) + 2) * 4;
    uint32_t sectors_per_fat = static_cast<uint32_t>((fat_bytes + sector_size - 1) / sector_size);

    uint64_t total_data_sectors = total_sectors - (reserved_sectors + (num_fats * sectors_per_fat));
    uint32_t total_data_clusters = static_cast<uint32_t>(total_data_sectors / sectors_per_cluster);

    if (total_data_clusters < 65525) {
        // Partition is too small for FAT32 (specification mandates at least 65,525 data clusters)
        return false;
    }

    uint32_t volume_serial = generateVolumeSerial();

    // 1. Assemble Volume Boot Record (VBR) at relative LBA 0
    std::vector<uint8_t> vbr(sector_size, 0);
    vbr[0] = 0xEB; vbr[1] = 0x58; vbr[2] = 0x90; // jmp boot
    std::memcpy(&vbr[3], "MSDOS5.0", 8);
    *reinterpret_cast<uint16_t*>(&vbr[11]) = static_cast<uint16_t>(sector_size);
    vbr[13] = sectors_per_cluster;
    *reinterpret_cast<uint16_t*>(&vbr[14]) = reserved_sectors;
    vbr[16] = num_fats;
    *reinterpret_cast<uint16_t*>(&vbr[17]) = 0; // Root directory entries (0 for FAT32)
    *reinterpret_cast<uint16_t*>(&vbr[19]) = 0; // TotSec16 (0 for FAT32)
    vbr[21] = 0xF8; // Media: Fixed Disk
    *reinterpret_cast<uint16_t*>(&vbr[22]) = 0; // FATSz16 (0 for FAT32)
    *reinterpret_cast<uint16_t*>(&vbr[24]) = 63; // SecPerTrk
    *reinterpret_cast<uint16_t*>(&vbr[26]) = 255; // NumHeads
    *reinterpret_cast<uint32_t*>(&vbr[28]) = static_cast<uint32_t>(params.start_lba);
    *reinterpret_cast<uint32_t*>(&vbr[32]) = static_cast<uint32_t>(total_sectors);

    // FAT32 Extended Section
    *reinterpret_cast<uint32_t*>(&vbr[36]) = sectors_per_fat;
    *reinterpret_cast<uint16_t*>(&vbr[40]) = 0; // ExtFlags
    *reinterpret_cast<uint16_t*>(&vbr[42]) = 0; // FSVer (0:0)
    *reinterpret_cast<uint32_t*>(&vbr[44]) = 2; // RootClus (Root Directory starts at Cluster 2)
    *reinterpret_cast<uint16_t*>(&vbr[48]) = 1; // FSInfo sector (Sector 1)
    *reinterpret_cast<uint16_t*>(&vbr[50]) = 6; // Backup Boot Sector (LBA 6)
    vbr[64] = 0x80; // DrvNum
    vbr[66] = 0x29; // BootSig
    *reinterpret_cast<uint32_t*>(&vbr[67]) = volume_serial;

    // Format Volume Label (11 characters, uppercase, space-padded)
    std::string label = params.volume_label;
    label.resize(11, ' ');
    std::transform(label.begin(), label.end(), label.begin(), ::toupper);
    std::memcpy(&vbr[71], label.data(), 11);
    std::memcpy(&vbr[82], "FAT32   ", 8);

    vbr[510] = 0x55;
    vbr[511] = 0xAA;

    progress_fn(20.0f, "Writing Boot Sector and FSInfo Sector...");

    // Write primary VBR at sector 0 and backup VBR at sector 6
    if (!write_fn(params.start_lba, 1, vbr.data())) return false;
    if (!write_fn(params.start_lba + 6, 1, vbr.data())) return false;

    // 2. Assemble FSInfo Sector at relative LBA 1
    std::vector<uint8_t> fsinfo(sector_size, 0);
    *reinterpret_cast<uint32_t*>(&fsinfo[0]) = 0x41615252; // Lead signature ("RRaA")
    *reinterpret_cast<uint32_t*>(&fsinfo[484]) = 0x61417272; // Structure signature ("rrAa")
    *reinterpret_cast<uint32_t*>(&fsinfo[488]) = total_data_clusters - 1; // Free cluster count
    *reinterpret_cast<uint32_t*>(&fsinfo[492]) = 3; // Next free cluster hint
    *reinterpret_cast<uint32_t*>(&fsinfo[508]) = 0xAA550000; // Trail signature

    // Write primary FSInfo at sector 1 and backup at sector 7
    if (!write_fn(params.start_lba + 1, 1, fsinfo.data())) return false;
    if (!write_fn(params.start_lba + 7, 1, fsinfo.data())) return false;

    progress_fn(40.0f, "Initializing primary and secondary FAT tables...");

    // 3. Initialize headers for FAT1 and FAT2
    std::vector<uint8_t> fat_first_sector(sector_size, 0);
    uint32_t* fat_entries = reinterpret_cast<uint32_t*>(fat_first_sector.data());
    fat_entries[0] = 0x0FFFFFF8; // Entry 0: Media byte (0xF8) with high nibble reserved
    fat_entries[1] = 0xFFFFFFFF; // Entry 1: EOC mark
    fat_entries[2] = 0x0FFFFFFF; // Entry 2: Root directory cluster (EOC)

    // Write initial sector of FAT1 and FAT2
    uint64_t fat1_start = params.start_lba + reserved_sectors;
    uint64_t fat2_start = fat1_start + sectors_per_fat;

    if (!write_fn(fat1_start, 1, fat_first_sector.data())) return false;
    if (!write_fn(fat2_start, 1, fat_first_sector.data())) return false;

    // Zero out additional FAT sectors to prevent residual fragments from prior partitions
    std::vector<uint8_t> zero_buffer(sector_size * 16, 0);
    uint32_t sectors_to_zero = std::min(sectors_per_fat - 1, 32u);
    write_fn(fat1_start + 1, sectors_to_zero, zero_buffer.data());
    write_fn(fat2_start + 1, sectors_to_zero, zero_buffer.data());

    progress_fn(70.0f, "Creating root directory and volume label entry...");

    // 4. Initialize Root Directory (Cluster 2)
    uint64_t root_dir_lba = fat2_start + sectors_per_fat;
    std::vector<uint8_t> root_dir(cluster_size, 0);

    // Volume label directory entry in root directory
    if (!params.volume_label.empty()) {
        std::memcpy(&root_dir[0], label.data(), 11);
        root_dir[11] = 0x08; // ATTR_VOLUME_ID attribute
    }

    if (!write_fn(root_dir_lba, sectors_per_cluster, root_dir.data())) return false;

    progress_fn(100.0f, "FAT32 formatting completed successfully!");
    return true;
}

bool FatFormatter::formatFat16(
    const NativeFormatParams& params,
    WriteSectorsFn write_fn,
    ProgressFn progress_fn
) {
    progress_fn(10.0f, "Calculating FAT16 parameters...");
    // Optimized implementation for legacy drives < 2 GB
    const uint32_t sector_size = params.sector_size;
    const uint64_t total_sectors = params.sector_count;
    const uint16_t root_entries = 512;
    const uint16_t reserved_sectors = 1;
    const uint8_t num_fats = 2;

    uint32_t cluster_size = params.cluster_size_bytes;
    if (cluster_size == 0) {
        cluster_size = (total_sectors * sector_size > 1ULL * 1024 * 1024 * 1024) ? 32768 : 16384;
    }
    const uint8_t sectors_per_cluster = static_cast<uint8_t>(cluster_size / sector_size);
    const uint16_t root_dir_sectors = ((root_entries * 32) + (sector_size - 1)) / sector_size;

    uint64_t dmp = total_sectors - (reserved_sectors + root_dir_sectors);
    uint32_t sectors_per_fat = static_cast<uint32_t>(((dmp / sectors_per_cluster) * 2 + sector_size - 1) / sector_size);

    std::vector<uint8_t> vbr(sector_size, 0);
    vbr[0] = 0xEB; vbr[1] = 0x3C; vbr[2] = 0x90;
    std::memcpy(&vbr[3], "MSDOS5.0", 8);
    *reinterpret_cast<uint16_t*>(&vbr[11]) = static_cast<uint16_t>(sector_size);
    vbr[13] = sectors_per_cluster;
    *reinterpret_cast<uint16_t*>(&vbr[14]) = reserved_sectors;
    vbr[16] = num_fats;
    *reinterpret_cast<uint16_t*>(&vbr[17]) = root_entries;
    if (total_sectors < 65536) {
        *reinterpret_cast<uint16_t*>(&vbr[19]) = static_cast<uint16_t>(total_sectors);
    } else {
        *reinterpret_cast<uint16_t*>(&vbr[19]) = 0;
        *reinterpret_cast<uint32_t*>(&vbr[32]) = static_cast<uint32_t>(total_sectors);
    }
    vbr[21] = 0xF8;
    *reinterpret_cast<uint16_t*>(&vbr[22]) = static_cast<uint16_t>(sectors_per_fat);
    *reinterpret_cast<uint16_t*>(&vbr[24]) = 63;
    *reinterpret_cast<uint16_t*>(&vbr[26]) = 255;
    *reinterpret_cast<uint32_t*>(&vbr[28]) = static_cast<uint32_t>(params.start_lba);

    vbr[36] = 0x80;
    vbr[38] = 0x29;
    *reinterpret_cast<uint32_t*>(&vbr[39]) = generateVolumeSerial();

    std::string label = params.volume_label;
    label.resize(11, ' ');
    std::transform(label.begin(), label.end(), label.begin(), ::toupper);
    std::memcpy(&vbr[43], label.data(), 11);
    std::memcpy(&vbr[54], "FAT16   ", 8);

    vbr[510] = 0x55;
    vbr[511] = 0xAA;

    progress_fn(40.0f, "Writing FAT16 Boot Sector...");
    if (!write_fn(params.start_lba, 1, vbr.data())) return false;

    // Initialize FAT1 and FAT2
    std::vector<uint8_t> fat_sector(sector_size, 0);
    uint16_t* fat_entries = reinterpret_cast<uint16_t*>(fat_sector.data());
    fat_entries[0] = 0xFFF8;
    fat_entries[1] = 0xFFFF;

    uint64_t fat1_start = params.start_lba + reserved_sectors;
    uint64_t fat2_start = fat1_start + sectors_per_fat;
    write_fn(fat1_start, 1, fat_sector.data());
    write_fn(fat2_start, 1, fat_sector.data());

    // Root Directory
    uint64_t root_lba = fat2_start + sectors_per_fat;
    std::vector<uint8_t> root_data(root_dir_sectors * sector_size, 0);
    if (!params.volume_label.empty()) {
        std::memcpy(&root_data[0], label.data(), 11);
        root_data[11] = 0x08;
    }
    write_fn(root_lba, root_dir_sectors, root_data.data());

    progress_fn(100.0f, "FAT16 formatting completed successfully!");
    return true;
}

} // namespace usbadvance
