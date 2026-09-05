#pragma once

#include <cstdint>
#include <cstddef>
#include <functional>
#include <string>

namespace usbadvance {

/**
 * Callback function for writing contiguous sectors to physical media.
 * Parameters: (start_lba, sector_count, data_pointer).
 * Returns true if write and storage verification succeeded.
 */
using WriteSectorsFn = std::function<bool(uint64_t lba, uint32_t count, const uint8_t* data)>;

/**
 * Callback function for format progress notification.
 * Parameters: (percentage_0_to_100, stage_description).
 */
using ProgressFn = std::function<void(float percentage, const std::string& description)>;

struct NativeFormatParams {
    uint64_t start_lba;
    uint64_t sector_count;
    uint32_t sector_size;
    uint32_t cluster_size_bytes; // 0 = automatic
    std::string volume_label;
    bool quick_format;
    bool disable_journal; // Specific to ext4
};

} // namespace usbadvance
