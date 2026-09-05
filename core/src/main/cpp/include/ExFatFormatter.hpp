#pragma once

#include "NativeBlockIo.hpp"

namespace usbadvance {

class ExFatFormatter {
public:
    static bool format(
        const NativeFormatParams& params,
        WriteSectorsFn write_fn,
        ProgressFn progress_fn
    );

private:
    static uint32_t calculateBootChecksum(const uint8_t* sectors_data, size_t length);
    static uint32_t calculateOptimalClusterSize(uint64_t capacity_bytes);
    static void generateUpcaseTable(std::vector<uint8_t>& out_table);
};

} // namespace usbadvance
