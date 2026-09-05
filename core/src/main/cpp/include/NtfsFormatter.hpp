#pragma once

#include "NativeBlockIo.hpp"
#include <vector>
#include <string>
#include <cstdint>

namespace usbadvance {

/**
 * Native C++20 NTFS Formatter.
 * Generates valid NTFS 3.1 structures (VBR, Backup VBR, Master File Table, $Bitmap, $Volume).
 * Formats USB block devices directly via userspace SCSI BOT commands without requiring root.
 */
class NtfsFormatter {
public:
    static bool format(
        const NativeFormatParams& params,
        WriteSectorsFn write_fn,
        ProgressFn progress_fn
    );

    static void applyUsaFixup(uint8_t* record, uint16_t usa_offset, uint16_t usa_count, uint16_t seq_num);

private:
    static uint32_t calculateOptimalClusterSize(uint64_t capacity_bytes);
    static uint64_t generateVolumeSerial();

    static void buildBootSector(
        const NativeFormatParams& params,
        uint32_t cluster_size,
        uint64_t mft_cluster,
        uint64_t mftmirr_cluster,
        uint64_t serial,
        std::vector<uint8_t>& out_boot_sector
    );
};

} // namespace usbadvance
