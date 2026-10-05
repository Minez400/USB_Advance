#include "ExFatVfsEngine.hpp"
#include <cstring>
#include <algorithm>
#include <sstream>
#include <android/log.h>

#define TAG "ExFatVfsEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

namespace usbadvance {

ExFatVfsEngine::ExFatVfsEngine() = default;

ExFatVfsEngine::~ExFatVfsEngine() {
    unmount();
}

ExFatVfsEngine::ExFatVfsEngine(ExFatVfsEngine&& other) noexcept {
    *this = std::move(other);
}

ExFatVfsEngine& ExFatVfsEngine::operator=(ExFatVfsEngine&& other) noexcept {
    if (this != &other) {
        unmount();
        read_fn_ = std::move(other.read_fn_);
        partition_start_lba_ = other.partition_start_lba_;
        total_sectors_ = other.total_sectors_;
        mounted_ = other.mounted_;
        sector_size_ = other.sector_size_;
        sectors_per_cluster_ = other.sectors_per_cluster_;
        cluster_size_bytes_ = other.cluster_size_bytes_;
        fat_offset_lba_ = other.fat_offset_lba_;
        fat_length_sectors_ = other.fat_length_sectors_;
        cluster_heap_offset_lba_ = other.cluster_heap_offset_lba_;
        cluster_count_ = other.cluster_count_;
        root_dir_cluster_ = other.root_dir_cluster_;
        volume_label_ = std::move(other.volume_label_);
        fat_sector_cache_ = std::move(other.fat_sector_cache_);
        cached_fat_lba_ = other.cached_fat_lba_;

        other.mounted_ = false;
    }
    return *this;
}

bool ExFatVfsEngine::mount(ReadSectorsFn read_fn, uint64_t partition_start_lba, uint64_t total_sectors) {
    if (!read_fn) {
        LOGE("mount: read_fn is null");
        return false;
    }

    unmount();
    read_fn_ = read_fn;
    partition_start_lba_ = partition_start_lba;
    total_sectors_ = total_sectors;

    // Read Sector 0 (Main Boot Record) - allocate 4096 bytes to safely handle 512B and 4Kn media
    std::vector<uint8_t> boot_buf(4096, 0);
    if (!read_fn_(partition_start_lba_, 1, boot_buf.data())) {
        LOGE("mount: failed to read boot sector at LBA %llu", (unsigned long long)partition_start_lba_);
        return false;
    }

    const auto* boot = reinterpret_cast<const ExFatBootRecord*>(boot_buf.data());

    // Validate Signature
    if (std::memcmp(boot->fs_name, "EXFAT   ", 8) != 0 || boot->boot_signature != 0xAA55) {
        LOGE("mount: invalid exFAT magic or signature");
        return false;
    }

    if (boot->bytes_per_sector_shift < 9 || boot->bytes_per_sector_shift > 12) {
        LOGE("mount: unsupported bytes_per_sector_shift: %u", boot->bytes_per_sector_shift);
        return false;
    }

    sector_size_ = 1U << boot->bytes_per_sector_shift;
    sectors_per_cluster_ = 1U << boot->sectors_per_cluster_shift;
    cluster_size_bytes_ = sector_size_ * sectors_per_cluster_;

    fat_offset_lba_ = boot->fat_offset;
    fat_length_sectors_ = boot->fat_length;
    cluster_heap_offset_lba_ = boot->cluster_heap_offset;
    cluster_count_ = boot->cluster_count;
    root_dir_cluster_ = boot->root_dir_first_cluster;

    fat_sector_cache_.resize(sector_size_);
    cached_fat_lba_ = UINT64_MAX;

    mounted_ = true;

    LOGI("exFAT mounted successfully: SectorSize=%u, ClusterSize=%u, HeapOffset=%u, RootCluster=%u",
         sector_size_, cluster_size_bytes_, cluster_heap_offset_lba_, root_dir_cluster_);

    // Parse root directory to retrieve Volume Label if present
    std::vector<ExFatNode> root_entries;
    parseDirectoryEntries(root_dir_cluster_, false, root_entries);

    return true;
}

void ExFatVfsEngine::unmount() {
    mounted_ = false;
    read_fn_ = nullptr;
    partition_start_lba_ = 0;
    total_sectors_ = 0;
    fat_sector_cache_.clear();
    cached_fat_lba_ = UINT64_MAX;
    volume_label_ = "USB ADVANCE";
}

uint64_t ExFatVfsEngine::clusterToLba(uint32_t cluster) const {
    if (cluster < 2) return partition_start_lba_;
    return partition_start_lba_ + cluster_heap_offset_lba_ +
           static_cast<uint64_t>(cluster - 2) * sectors_per_cluster_;
}

uint32_t ExFatVfsEngine::getNextCluster(uint32_t current_cluster) {
    if (current_cluster < 2 || current_cluster >= cluster_count_ + 2) {
        return 0; // EOF or invalid
    }

    uint64_t fat_byte_offset = static_cast<uint64_t>(current_cluster) * 4;
    uint64_t fat_sector_index = fat_byte_offset / sector_size_;
    uint32_t offset_in_sector = static_cast<uint32_t>(fat_byte_offset % sector_size_);
    uint64_t target_lba = partition_start_lba_ + fat_offset_lba_ + fat_sector_index;

    std::lock_guard<std::mutex> lock(fat_cache_mutex_);
    if (cached_fat_lba_ != target_lba) {
        if (!read_fn_(target_lba, 1, fat_sector_cache_.data())) {
            LOGE("getNextCluster: read failed at FAT LBA %llu", (unsigned long long)target_lba);
            return 0;
        }
        cached_fat_lba_ = target_lba;
    }

    uint32_t next_cluster = 0;
    std::memcpy(&next_cluster, &fat_sector_cache_[offset_in_sector], sizeof(uint32_t));

    // Check for End of Cluster Chain
    if (next_cluster >= 0xFFFFFFF8U || next_cluster == 0) {
        return 0; // EOF
    }
    return next_cluster;
}

bool ExFatVfsEngine::readCluster(uint32_t cluster, uint8_t* destination) {
    uint64_t lba = clusterToLba(cluster);
    return read_fn_(lba, sectors_per_cluster_, destination);
}

std::string ExFatVfsEngine::utf16ToUtf8(const uint16_t* utf16, size_t length) {
    std::string utf8;
    utf8.reserve(length * 2);
    for (size_t i = 0; i < length && utf16[i] != 0; ++i) {
        uint16_t cp = utf16[i];
        if (cp < 0x80) {
            utf8.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            utf8.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            utf8.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return utf8;
}

uint64_t ExFatVfsEngine::exfatTimestampToEpochMs(uint32_t timestamp, uint8_t increment_10ms) {
    if (timestamp == 0) return 0;
    // Dos/exFAT format:
    // Bits 0-4: Second / 2 (0-29)
    // Bits 5-10: Minute (0-59)
    // Bits 11-15: Hour (0-23)
    // Bits 16-20: Day (1-31)
    // Bits 21-24: Month (1-12)
    // Bits 25-31: Year offset from 1980
    uint32_t year = ((timestamp >> 25) & 0x7F) + 1980;
    uint32_t month = (timestamp >> 21) & 0x0F;
    uint32_t day = (timestamp >> 16) & 0x1F;
    uint32_t hour = (timestamp >> 11) & 0x1F;
    uint32_t min = (timestamp >> 5) & 0x3F;
    uint32_t sec = (timestamp & 0x1F) * 2 + (increment_10ms / 100);

    // Simplified epoch approximation sufficient for file listings
    // Days since 1970
    int64_t days = (year - 1970) * 365 + ((year - 1969) / 4);
    static const int days_before_month[13] = {0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    if (month >= 1 && month <= 12) {
        days += days_before_month[month];
        if (month > 2 && (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0))) {
            days++;
        }
    }
    days += (day > 0 ? day - 1 : 0);
    int64_t seconds = days * 86400 + hour * 3600 + min * 60 + sec;
    return static_cast<uint64_t>(seconds * 1000 + (increment_10ms % 100) * 10);
}

bool ExFatVfsEngine::parseDirectoryEntries(
    uint32_t start_cluster,
    bool no_fat_chain,
    std::vector<ExFatNode>& out_entries
) {
    if (!mounted_ || start_cluster < 2) return false;

    std::vector<uint8_t> cluster_buffer(cluster_size_bytes_);
    uint32_t current_cluster = start_cluster;
    bool stop_parsing = false;

    while (current_cluster >= 2 && !stop_parsing) {
        if (!readCluster(current_cluster, cluster_buffer.data())) {
            LOGE("parseDirectoryEntries: failed reading cluster %u", current_cluster);
            return false;
        }

        size_t total_entries = cluster_size_bytes_ / 32;
        size_t i = 0;

        while (i < total_entries) {
            const auto* generic = reinterpret_cast<const ExFatEntryGeneric*>(&cluster_buffer[i * 32]);
            uint8_t entry_type = generic->type;

            // 0x00 indicates end of directory
            if (entry_type == 0x00) {
                stop_parsing = true;
                break;
            }

            // Deleted or unused entry (MSB == 0)
            if ((entry_type & 0x80) == 0) {
                i++;
                continue;
            }

            // 0x83: Volume Label
            if (entry_type == 0x83) {
                const auto* label_entry = reinterpret_cast<const ExFatVolumeLabelEntry*>(generic);
                if (label_entry->character_count <= 11) {
                    volume_label_ = utf16ToUtf8(label_entry->volume_label, label_entry->character_count);
                }
                i++;
                continue;
            }

            // 0x85: File Directory Entry (Primary)
            if (entry_type == 0x85) {
                const auto* file_dir = reinterpret_cast<const ExFatFileDirectoryEntry*>(generic);
                uint8_t secondary_count = file_dir->secondary_count;

                // A valid directory set requires at least 1 stream entry (0xC0) and 1 name entry (0xC1)
                if (secondary_count >= 2 && (i + secondary_count) < total_entries) {
                    const auto* stream_entry = reinterpret_cast<const ExFatStreamExtensionEntry*>(
                        &cluster_buffer[(i + 1) * 32]
                    );

                    if (stream_entry->type == 0xC0) {
                        ExFatNode node;
                        node.first_cluster = stream_entry->first_cluster;
                        node.size_bytes = stream_entry->data_length;
                        node.valid_data_length = stream_entry->valid_data_length;
                        node.no_fat_chain = (stream_entry->general_secondary_flags & 0x02) != 0;
                        node.is_directory = (file_dir->file_attributes & 0x10) != 0;
                        node.is_read_only = (file_dir->file_attributes & 0x01) != 0;
                        node.is_hidden = (file_dir->file_attributes & 0x02) != 0;
                        node.is_system = (file_dir->file_attributes & 0x04) != 0;

                        node.created_epoch_ms = exfatTimestampToEpochMs(
                            file_dir->create_timestamp,
                            file_dir->create_10ms_increment
                        );
                        node.modified_epoch_ms = exfatTimestampToEpochMs(
                            file_dir->last_modified_timestamp,
                            file_dir->modify_10ms_increment
                        );

                        // Read UTF-16LE filename parts from 0xC1 entries
                        std::vector<uint16_t> name_utf16;
                        name_utf16.reserve(stream_entry->name_length);

                        for (uint8_t sec = 2; sec <= secondary_count; ++sec) {
                            const auto* name_entry = reinterpret_cast<const ExFatFileNameEntry*>(
                                &cluster_buffer[(i + sec) * 32]
                            );
                            if (name_entry->type == 0xC1) {
                                for (int c = 0; c < 15; ++c) {
                                    if (name_utf16.size() < stream_entry->name_length) {
                                        name_utf16.push_back(name_entry->file_name_part[c]);
                                    }
                                }
                            }
                        }

                        node.name = utf16ToUtf8(name_utf16.data(), name_utf16.size());
                        if (!node.name.empty()) {
                            out_entries.push_back(std::move(node));
                        }

                        i += (1 + secondary_count);
                        continue;
                    }
                }
            }

            i++;
        }

        if (no_fat_chain) {
            current_cluster++;
        } else {
            current_cluster = getNextCluster(current_cluster);
        }
    }

    return true;
}

bool ExFatVfsEngine::resolvePath(const std::string& path, ExFatNode& out_node) {
    if (!mounted_) return false;

    if (path.empty() || path == "/") {
        out_node.name = "/";
        out_node.is_directory = true;
        out_node.first_cluster = root_dir_cluster_;
        out_node.size_bytes = 0;
        out_node.no_fat_chain = false;
        return true;
    }

    std::stringstream ss(path);
    std::string segment;
    std::vector<std::string> segments;
    while (std::getline(ss, segment, '/')) {
        if (!segment.empty() && segment != ".") {
            segments.push_back(segment);
        }
    }

    if (segments.empty()) {
        return resolvePath("/", out_node);
    }

    uint32_t current_dir_cluster = root_dir_cluster_;
    bool current_no_fat = false;
    ExFatNode current_node;

    for (size_t s = 0; s < segments.size(); ++s) {
        const std::string& target_name = segments[s];
        std::vector<ExFatNode> entries;
        if (!parseDirectoryEntries(current_dir_cluster, current_no_fat, entries)) {
            return false;
        }

        bool found = false;
        for (const auto& entry : entries) {
            // Case-insensitive comparison for cross-platform robustness
            if (entry.name.size() == target_name.size() &&
                std::equal(entry.name.begin(), entry.name.end(), target_name.begin(),
                           [](char a, char b) { return std::tolower(a) == std::tolower(b); })) {
                current_node = entry;
                found = true;
                break;
            }
        }

        if (!found) {
            return false; // Segment not found
        }

        if (s == segments.size() - 1) {
            out_node = current_node;
            return true;
        }

        if (!current_node.is_directory) {
            return false; // Intermediate path component is not a directory
        }

        current_dir_cluster = current_node.first_cluster;
        current_no_fat = current_node.no_fat_chain;
    }

    return false;
}

bool ExFatVfsEngine::listDirectory(const std::string& path, std::vector<ExFatNode>& out_entries) {
    out_entries.clear();
    ExFatNode dir_node;
    if (!resolvePath(path, dir_node)) {
        LOGE("listDirectory: path not found: %s", path.c_str());
        return false;
    }

    if (!dir_node.is_directory) {
        LOGE("listDirectory: path is not a directory: %s", path.c_str());
        return false;
    }

    return parseDirectoryEntries(dir_node.first_cluster, dir_node.no_fat_chain, out_entries);
}

int64_t ExFatVfsEngine::readFile(
    const ExFatNode& node,
    uint64_t file_offset,
    uint8_t* out_buffer,
    size_t buffer_length
) {
    if (!mounted_ || out_buffer == nullptr || buffer_length == 0) {
        return -1;
    }

    if (file_offset >= node.size_bytes) {
        return 0; // EOF
    }

    size_t bytes_to_read = std::min<uint64_t>(buffer_length, node.size_bytes - file_offset);
    size_t bytes_transferred = 0;

    // PS2 OPL Contiguous Path (NoFatChain) - O(1) Cluster Calculation
    if (node.no_fat_chain) {
        uint64_t current_offset = file_offset;

        while (bytes_transferred < bytes_to_read) {
            uint64_t cluster_idx = current_offset / cluster_size_bytes_;
            uint64_t internal_cluster_offset = current_offset % cluster_size_bytes_;
            uint32_t cluster_num = node.first_cluster + static_cast<uint32_t>(cluster_idx);

            uint64_t cluster_lba = clusterToLba(cluster_num);
            uint64_t sector_in_cluster = internal_cluster_offset / sector_size_;
            uint32_t offset_in_sector = static_cast<uint32_t>(internal_cluster_offset % sector_size_);
            uint64_t start_lba = cluster_lba + sector_in_cluster;

            size_t bytes_remaining_in_cluster = cluster_size_bytes_ - internal_cluster_offset;
            size_t chunk_size = std::min<size_t>(bytes_to_read - bytes_transferred, bytes_remaining_in_cluster);

            // True Zero-Copy direct buffer path if aligned to sector boundaries
            if (offset_in_sector == 0 && (chunk_size % sector_size_) == 0) {
                uint32_t sector_count = static_cast<uint32_t>(chunk_size / sector_size_);
                if (!read_fn_(start_lba, sector_count, out_buffer + bytes_transferred)) {
                    LOGE("readFile (direct O(1)): sector read failed at LBA %llu", (unsigned long long)start_lba);
                    return bytes_transferred > 0 ? static_cast<int64_t>(bytes_transferred) : -1;
                }
            } else {
                // Unaligned boundary read using sector cache
                std::vector<uint8_t> sector_buf(sector_size_);
                if (!read_fn_(start_lba, 1, sector_buf.data())) {
                    LOGE("readFile: boundary sector read failed at LBA %llu", (unsigned long long)start_lba);
                    return bytes_transferred > 0 ? static_cast<int64_t>(bytes_transferred) : -1;
                }

                size_t copy_len = std::min<size_t>(chunk_size, sector_size_ - offset_in_sector);
                std::memcpy(out_buffer + bytes_transferred, sector_buf.data() + offset_in_sector, copy_len);
                chunk_size = copy_len;
            }

            bytes_transferred += chunk_size;
            current_offset += chunk_size;
        }

        return static_cast<int64_t>(bytes_transferred);
    }

    // Standard Non-Contiguous File (FAT Chain Traversal)
    uint64_t target_cluster_idx = file_offset / cluster_size_bytes_;
    uint64_t internal_cluster_offset = file_offset % cluster_size_bytes_;

    uint32_t current_cluster = node.first_cluster;
    for (uint64_t c = 0; c < target_cluster_idx && current_cluster >= 2; ++c) {
        current_cluster = getNextCluster(current_cluster);
    }

    if (current_cluster < 2) {
        return -1; // Premature EOF in FAT chain
    }

    std::vector<uint8_t> cluster_buf(cluster_size_bytes_);

    while (bytes_transferred < bytes_to_read && current_cluster >= 2) {
        if (!readCluster(current_cluster, cluster_buf.data())) {
            LOGE("readFile: cluster read failed at cluster %u", current_cluster);
            return bytes_transferred > 0 ? static_cast<int64_t>(bytes_transferred) : -1;
        }

        size_t available_in_cluster = cluster_size_bytes_ - internal_cluster_offset;
        size_t chunk_size = std::min<size_t>(bytes_to_read - bytes_transferred, available_in_cluster);

        std::memcpy(out_buffer + bytes_transferred, cluster_buf.data() + internal_cluster_offset, chunk_size);

        bytes_transferred += chunk_size;
        internal_cluster_offset = 0; // Only the first cluster can have a non-zero start offset
        current_cluster = getNextCluster(current_cluster);
    }

    return static_cast<int64_t>(bytes_transferred);
}

bool ExFatVfsEngine::analyzeContiguity(
    const ExFatNode& node,
    std::vector<ClusterExtent>& out_extents,
    bool& out_is_contiguous
) {
    out_extents.clear();
    out_is_contiguous = false;

    if (!mounted_ || node.first_cluster < 2 || node.size_bytes == 0) {
        return false;
    }

    uint32_t total_clusters = static_cast<uint32_t>(
        (node.size_bytes + cluster_size_bytes_ - 1) / cluster_size_bytes_
    );

    // If marked NoFatChain, it is strictly 100% contiguous!
    if (node.no_fat_chain) {
        out_extents.push_back({ node.first_cluster, total_clusters });
        out_is_contiguous = true;
        return true;
    }

    // Traverse FAT chain to identify all fragmented extents
    uint32_t current_cluster = node.first_cluster;
    uint32_t extent_start = current_cluster;
    uint32_t extent_count = 1;
    uint32_t visited_clusters = 1;

    while (visited_clusters < total_clusters) {
        uint32_t next_cluster = getNextCluster(current_cluster);
        if (next_cluster < 2) {
            break; // Truncated or EOF
        }

        if (next_cluster == current_cluster + 1) {
            // Contiguous sequence continues
            extent_count++;
        } else {
            // Discontinuity detected: push completed extent and start a new one
            out_extents.push_back({ extent_start, extent_count });
            extent_start = next_cluster;
            extent_count = 1;
        }

        current_cluster = next_cluster;
        visited_clusters++;
    }

    out_extents.push_back({ extent_start, extent_count });
    out_is_contiguous = (out_extents.size() <= 1);

    return true;
}

} // namespace usbadvance
