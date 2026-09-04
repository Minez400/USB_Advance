#include "Ext4Formatter.hpp"
#include <cstring>
#include <vector>
#include <chrono>
#include <random>
#include <algorithm>

namespace usbadvance {

uint32_t Ext4Formatter::calculateOptimalBlockSize(uint64_t capacity_bytes) {
    if (capacity_bytes < 512ULL * 1024 * 1024) {
        return 1024; // 1 KB
    } else {
        return 4096; // 4 KB padrão do kernel Linux
    }
}

bool Ext4Formatter::format(
    const NativeFormatParams& params,
    WriteSectorsFn write_fn,
    ProgressFn progress_fn
) {
    progress_fn(5.0f, "Calculando geometria ext4 e grupos de blocos...");

    const uint32_t sector_size = params.sector_size;
    const uint64_t total_sectors = params.sector_count;
    const uint64_t capacity_bytes = total_sectors * sector_size;

    const uint32_t block_size = 4096; // 4 KB padrão
    const uint32_t sectors_per_block = block_size / sector_size;
    const uint64_t total_blocks = capacity_bytes / block_size;

    const uint32_t blocks_per_group = 32768; // Padrão Linux ext4
    const uint32_t num_groups = static_cast<uint32_t>((total_blocks + blocks_per_group - 1) / blocks_per_group);

    const uint32_t inodes_per_group = 8192;
    const uint32_t inode_size = 256;
    const uint64_t total_inodes = num_groups * inodes_per_group;

    auto now = std::chrono::system_clock::now().time_since_epoch().count();
    uint32_t format_time = static_cast<uint32_t>(now);

    progress_fn(20.0f, "Construindo Superblock ext4...");

    // O Superblock ext4 possui 1024 bytes e fica no offset absoluto de 1024 bytes
    // (setores 2 e 3 em mídias de 512B)
    std::vector<uint8_t> sb_data(1024, 0);

    *reinterpret_cast<uint32_t*>(&sb_data[0]) = static_cast<uint32_t>(total_inodes); // s_inodes_count
    *reinterpret_cast<uint32_t*>(&sb_data[4]) = static_cast<uint32_t>(total_blocks); // s_blocks_count_lo
    *reinterpret_cast<uint32_t*>(&sb_data[8]) = static_cast<uint32_t>(total_blocks / 20); // s_r_blocks_count_lo (5% reservado para root)
    *reinterpret_cast<uint32_t*>(&sb_data[12]) = static_cast<uint32_t>(total_blocks - (num_groups * 200)); // s_free_blocks_count_lo
    *reinterpret_cast<uint32_t*>(&sb_data[16]) = static_cast<uint32_t>(total_inodes - 11); // s_free_inodes_count
    *reinterpret_cast<uint32_t*>(&sb_data[20]) = 0; // s_first_data_block (0 para blocos de 4KB)
    *reinterpret_cast<uint32_t*>(&sb_data[24]) = 2; // s_log_block_size (1024 << 2 = 4096)
    *reinterpret_cast<uint32_t*>(&sb_data[28]) = 2; // s_log_cluster_size
    *reinterpret_cast<uint32_t*>(&sb_data[32]) = blocks_per_group; // s_blocks_per_group
    *reinterpret_cast<uint32_t*>(&sb_data[36]) = blocks_per_group; // s_clusters_per_group
    *reinterpret_cast<uint32_t*>(&sb_data[40]) = inodes_per_group; // s_inodes_per_group
    *reinterpret_cast<uint32_t*>(&sb_data[44]) = format_time; // s_mtime
    *reinterpret_cast<uint32_t*>(&sb_data[48]) = format_time; // s_wtime
    *reinterpret_cast<uint16_t*>(&sb_data[56]) = 0xEF53; // s_magic (ASSINATURA EXT4)
    *reinterpret_cast<uint16_t*>(&sb_data[58]) = 1; // s_state (Cleanly unmounted)
    *reinterpret_cast<uint16_t*>(&sb_data[60]) = 1; // s_errors (Continue)
    *reinterpret_cast<uint32_t*>(&sb_data[64]) = format_time; // s_lastcheck
    *reinterpret_cast<uint32_t*>(&sb_data[76]) = 0; // s_creator_os (Linux)
    *reinterpret_cast<uint32_t*>(&sb_data[80]) = 1; // s_rev_level (Dynamic revision)
    *reinterpret_cast<uint16_t*>(&sb_data[84]) = 11; // s_first_ino (Inodes reservados até 11)
    *reinterpret_cast<uint16_t*>(&sb_data[88]) = inode_size; // s_inode_size (256 bytes)

    // s_feature_compat, incompat, ro_compat
    uint32_t feat_compat = 0x20; // Ext_attr
    uint32_t feat_incompat = 0x40 | 0x02 | 0x80; // Extents, Filetype, 64bit
    uint32_t feat_ro_compat = 0x01 | 0x02 | 0x04; // Sparse_super, Large_file, BT_tree

    if (!params.disable_journal) {
        feat_compat |= 0x04; // HAS_JOURNAL
    }

    *reinterpret_cast<uint32_t*>(&sb_data[92]) = feat_compat;
    *reinterpret_cast<uint32_t*>(&sb_data[96]) = feat_incompat;
    *reinterpret_cast<uint32_t*>(&sb_data[100]) = feat_ro_compat;

    // Gera UUID do filesystem no offset 104 (16 bytes)
    std::mt19937 rnd(format_time);
    for (int u = 0; u < 16; ++u) {
        sb_data[104 + u] = static_cast<uint8_t>(rnd() & 0xFF);
    }

    // Volume label no offset 120 (16 bytes)
    std::string label = params.volume_label;
    if (label.empty()) label = "EXT4_USB";
    if (label.length() > 16) label.resize(16);
    std::memcpy(&sb_data[120], label.data(), label.length());

    progress_fn(40.0f, "Gravando Superblock no LBA 2...");

    // LBA do Superblock = start_lba + (1024 / sector_size)
    uint64_t sb_lba = params.start_lba + (1024 / sector_size);
    uint32_t sb_sectors = 1024 / sector_size;
    if (!write_fn(sb_lba, sb_sectors, sb_data.data())) return false;

    progress_fn(60.0f, "Inicializando Tabela de Descritores de Grupo (GDT)...");

    // GDT (Group Descriptor Table) fica no Bloco 1 (offset 4096 = 8 setores em 512B)
    // Para 64-bit ext4, cada descritor tem 64 bytes
    const uint32_t desc_size = 64;
    std::vector<uint8_t> gdt_data(num_groups * desc_size, 0);

    for (uint32_t g = 0; g < num_groups; ++g) {
        uint8_t* desc = &gdt_data[g * desc_size];
        uint64_t grp_start_block = g * blocks_per_group;
        uint32_t block_bitmap = grp_start_block + 2;
        uint32_t inode_bitmap = grp_start_block + 3;
        uint32_t inode_table = grp_start_block + 4;

        *reinterpret_cast<uint32_t*>(&desc[0]) = block_bitmap;
        *reinterpret_cast<uint32_t*>(&desc[4]) = inode_bitmap;
        *reinterpret_cast<uint32_t*>(&desc[8]) = inode_table;
        *reinterpret_cast<uint16_t*>(&desc[12]) = static_cast<uint16_t>(blocks_per_group - 500);
        *reinterpret_cast<uint16_t*>(&desc[14]) = static_cast<uint16_t>(inodes_per_group - 11);
        *reinterpret_cast<uint16_t*>(&desc[16]) = (g == 0) ? 2 : 0; // Diretórios existentes
        *reinterpret_cast<uint16_t*>(&desc[18]) = 0x0004; // Flags: EXT4_BG_INODE_ZEROED
    }

    uint64_t gdt_lba = params.start_lba + sectors_per_block; // Bloco 1
    uint32_t gdt_sectors = static_cast<uint32_t>((gdt_data.size() + sector_size - 1) / sector_size);
    gdt_data.resize(gdt_sectors * sector_size, 0);

    if (!write_fn(gdt_lba, gdt_sectors, gdt_data.data())) return false;

    progress_fn(80.0f, "Criando Inode Raiz (Inode 2)...");

    // Inode 2 (Diretório Raiz):
    // Fica na Inode Table do Grupo 0 (Bloco 4 = offset 16384 bytes)
    // Offset do Inode 2 = 1 * inode_size (1-indexed: Inode 1 = bad blocks, Inode 2 = root)
    std::vector<uint8_t> inode_table_block(block_size, 0);
    uint8_t* root_inode = &inode_table_block[1 * inode_size];

    *reinterpret_cast<uint16_t*>(&root_inode[0]) = 0x41ED; // i_mode: Diretório (0040000) com permissão 0755
    *reinterpret_cast<uint16_t*>(&root_inode[2]) = 0; // i_uid = 0 (root)
    *reinterpret_cast<uint32_t*>(&root_inode[4]) = block_size; // i_size_lo = 4096
    *reinterpret_cast<uint32_t*>(&root_inode[8]) = format_time; // i_atime
    *reinterpret_cast<uint32_t*>(&root_inode[12]) = format_time; // i_ctime
    *reinterpret_cast<uint32_t*>(&root_inode[16]) = format_time; // i_mtime
    *reinterpret_cast<uint16_t*>(&root_inode[26]) = 2; // i_links_count = 2 ("." e "..")
    *reinterpret_cast<uint32_t*>(&root_inode[28]) = sectors_per_block; // i_blocks_lo
    *reinterpret_cast<uint32_t*>(&root_inode[32]) = 0x00080000; // i_flags: EXT4_EXTENTS_FL

    // Cabeçalho da Árvore de Extents (offset 40)
    *reinterpret_cast<uint16_t*>(&root_inode[40]) = 0xF30A; // eh_magic
    *reinterpret_cast<uint16_t*>(&root_inode[42]) = 1; // eh_entries = 1
    *reinterpret_cast<uint16_t*>(&root_inode[44]) = 4; // eh_max = 4
    *reinterpret_cast<uint16_t*>(&root_inode[46]) = 0; // eh_depth = 0 (folha)

    // Extent 1: aponta para o bloco de dados do diretório raiz (bloco 600 por exemplo)
    uint32_t root_data_block = 600;
    *reinterpret_cast<uint32_t*>(&root_inode[52]) = 0; // ee_block = 0
    *reinterpret_cast<uint16_t*>(&root_inode[56]) = 1; // ee_len = 1 bloco
    *reinterpret_cast<uint16_t*>(&root_inode[58]) = 0; // ee_start_hi
    *reinterpret_cast<uint32_t*>(&root_inode[60]) = root_data_block; // ee_start_lo

    uint64_t inode_table_lba = params.start_lba + (4 * sectors_per_block);
    if (!write_fn(inode_table_lba, sectors_per_block, inode_table_block.data())) return false;

    // Inicializa o bloco de dados do diretório raiz (entradas "." e "..")
    std::vector<uint8_t> dir_block(block_size, 0);

    // Entrada 1: "." (Inode 2, rec_len 12)
    *reinterpret_cast<uint32_t*>(&dir_block[0]) = 2; // Inode 2
    *reinterpret_cast<uint16_t*>(&dir_block[4]) = 12; // rec_len
    dir_block[6] = 1; // name_len
    dir_block[7] = 2; // file_type (EXT4_FT_DIR)
    dir_block[8] = '.';

    // Entrada 2: ".." (Inode 2, rec_len block_size - 12)
    *reinterpret_cast<uint32_t*>(&dir_block[12]) = 2; // Inode 2
    *reinterpret_cast<uint16_t*>(&dir_block[16]) = static_cast<uint16_t>(block_size - 12);
    dir_block[18] = 2; // name_len
    dir_block[19] = 2; // file_type (EXT4_FT_DIR)
    dir_block[20] = '.';
    dir_block[21] = '.';

    uint64_t root_data_lba = params.start_lba + (root_data_block * sectors_per_block);
    if (!write_fn(root_data_lba, sectors_per_block, dir_block.data())) return false;

    progress_fn(100.0f, "Formatação ext4 concluída com sucesso!");
    return true;
}

} // namespace usbadvance
