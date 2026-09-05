#pragma once

#include "NativeBlockIo.hpp"

namespace usbadvance {

class Ext4Formatter {
public:
    static bool format(
        const NativeFormatParams& params,
        WriteSectorsFn write_fn,
        ProgressFn progress_fn
    );

private:
    static uint32_t calculateOptimalBlockSize(uint64_t capacity_bytes);
};

} // namespace usbadvance
