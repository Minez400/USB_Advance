#pragma once

#include "NativeBlockIo.hpp"

namespace usbadvance {

class FatFormatter {
public:
    static bool formatFat32(
        const NativeFormatParams& params,
        WriteSectorsFn write_fn,
        ProgressFn progress_fn
    );

    static bool formatFat16(
        const NativeFormatParams& params,
        WriteSectorsFn write_fn,
        ProgressFn progress_fn
    );

private:
    static uint32_t calculateOptimalFat32ClusterSize(uint64_t capacity_bytes);
    static uint32_t generateVolumeSerial();
};

} // namespace usbadvance
