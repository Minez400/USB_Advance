#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <unordered_map>
#include "ExFatTypes.hpp"
#include "NativeBlockIo.hpp"

namespace usbadvance {

/**
 * High-performance userspace exFAT VFS Engine written in C++20.
 * Supports direct sector I/O via USB OTG Host callbacks,
 * O(1) contiguous file streaming (NoFatChain optimization),
 * directory parsing, and cluster extent fragmentation analysis for PS2 OPL.
 */
class ExFatVfsEngine {
public:
    ExFatVfsEngine();
    ~ExFatVfsEngine();

    // Prevent copies, allow move
    ExFatVfsEngine(const ExFatVfsEngine&) = delete;
    ExFatVfsEngine& operator=(const ExFatVfsEngine&) = delete;
    ExFatVfsEngine(ExFatVfsEngine&&) noexcept;
    ExFatVfsEngine& operator=(ExFatVfsEngine&&) noexcept;

    /**
     * Mounts an exFAT filesystem starting at [partition_start_lba].
     */
    bool mount(ReadSectorsFn read_fn, uint64_t partition_start_lba, uint64_t total_sectors);

    /**
     * Unmounts the filesystem and frees internal buffers and caches.
     */
    void unmount();

    /**
     * Checks if the filesystem is currently mounted.
     */
    bool isMounted() const { return mounted_; }

    /**
     * Lists all directory entries inside the specified directory path (e.g. "/" or "/PS2/DVD").
     */
    bool listDirectory(const std::string& path, std::vector<ExFatNode>& out_entries);

    /**
     * Resolves the metadata node for a specific file or folder path.
     */
    bool resolvePath(const std::string& path, ExFatNode& out_node);

    /**
     * Reads up to [buffer_length] bytes from [node] starting at [file_offset] directly
     * into [out_buffer]. If [node.no_fat_chain] is true, uses O(1) cluster arithmetic.
     * Returns the actual count of bytes read, or -1 on error.
     */
    int64_t readFile(
        const ExFatNode& node,
        uint64_t file_offset,
        uint8_t* out_buffer,
        size_t buffer_length
    );

    /**
     * Analyzes cluster contiguity / fragmentation for PS2 OPL.
     */
    bool analyzeContiguity(
        const ExFatNode& node,
        std::vector<ClusterExtent>& out_extents,
        bool& out_is_contiguous
    );

    // Filesystem metrics
    uint32_t getSectorSize() const { return sector_size_; }
    uint32_t getClusterSize() const { return cluster_size_bytes_; }
    uint32_t getSectorsPerCluster() const { return sectors_per_cluster_; }
    uint64_t getTotalCapacityBytes() const { return total_sectors_ * sector_size_; }
    uint32_t getRootDirCluster() const { return root_dir_cluster_; }
    const std::string& getVolumeLabel() const { return volume_label_; }

private:
    ReadSectorsFn read_fn_;
    uint64_t partition_start_lba_ = 0;
    uint64_t total_sectors_ = 0;
    bool mounted_ = false;

    // Boot sector geometry
    uint32_t sector_size_ = 512;
    uint32_t sectors_per_cluster_ = 1;
    uint32_t cluster_size_bytes_ = 512;
    uint32_t fat_offset_lba_ = 0;
    uint32_t fat_length_sectors_ = 0;
    uint32_t cluster_heap_offset_lba_ = 0;
    uint32_t cluster_count_ = 0;
    uint32_t root_dir_cluster_ = 0;
    std::string volume_label_ = "USB ADVANCE";

    // Internal FAT cache (LBA -> Sector data)
    mutable std::mutex fat_cache_mutex_;
    std::vector<uint8_t> fat_sector_cache_;
    uint64_t cached_fat_lba_ = UINT64_MAX;

    // Helper methods
    uint64_t clusterToLba(uint32_t cluster) const;
    uint32_t getNextCluster(uint32_t current_cluster);
    bool readCluster(uint32_t cluster, uint8_t* destination);
    bool parseDirectoryEntries(
        uint32_t start_cluster,
        bool no_fat_chain,
        std::vector<ExFatNode>& out_entries
    );
    static std::string utf16ToUtf8(const uint16_t* utf16, size_t length);
    static uint64_t exfatTimestampToEpochMs(uint32_t timestamp, uint8_t increment_10ms);
};

} // namespace usbadvance
