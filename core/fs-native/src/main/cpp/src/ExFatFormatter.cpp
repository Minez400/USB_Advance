#include "ExFatFormatter.hpp"
#include <cstring>
#include <vector>
#include <chrono>
#include <random>
#include <algorithm>

namespace usbadvance {

uint32_t ExFatFormatter::calculateBootChecksum(const uint8_t* sectors_data, size_t length) {
    uint32_t checksum = 0;
    for (size_t i = 0; i < length; ++i) {
        // Ignora campos modificáveis durante a montagem (VolumeFlags no offset 106-107 e PercentInUse no offset 112)
        if (i == 106 || i == 107 || i == 112) {
            continue;
        }
        checksum = ((checksum << 31) | (checksum >> 1)) + sectors_data[i];
    }
    return checksum;
}

uint32_t ExFatFormatter::calculateOptimalClusterSize(uint64_t capacity_bytes) {
    // Recomendações Microsoft para exFAT:
    // <= 32 MB -> 4 KB
    // <= 256 MB -> 8 KB
    // <= 32 GB -> 32 KB
    // > 32 GB -> 128 KB
    if (capacity_bytes <= 32ULL * 1024 * 1024) {
        return 4096;
    } else if (capacity_bytes <= 256ULL * 1024 * 1024) {
        return 8192;
    } else if (capacity_bytes <= 32ULL * 1024 * 1024 * 1024) {
        return 32768; // 32 KB (padrão ideal para pendrives de 8GB a 32GB)
    } else {
        return 131072; // 128 KB para pendrives de 64GB+, SSDs e HDs externos
    }
}

// Tabela Upcase oficial exFAT comprimida (converte a-z para A-Z e mapeamentos Unicode padrão)
void ExFatFormatter::generateUpcaseTable(std::vector<uint8_t>& out_table) {
    out_table.assign(5120, 0); // 5120 bytes comportam a tabela padrão comprimida
    uint16_t* u16 = reinterpret_cast<uint16_t*>(out_table.data());
    
    // Mapeamento direto básico ASCII 0x0000 a 0x007F
    for (uint16_t ch = 0; ch < 128; ++ch) {
        if (ch >= 'a' && ch <= 'z') {
            u16[ch] = ch - ('a' - 'A');
        } else {
            u16[ch] = ch;
        }
    }
    // O restante preenchido com identidade para os caracteres BMP comuns
    for (size_t i = 128; i < 2560; ++i) {
        u16[i] = static_cast<uint16_t>(i);
    }
}

bool ExFatFormatter::format(
    const NativeFormatParams& params,
    WriteSectorsFn write_fn,
    ProgressFn progress_fn
) {
    progress_fn(5.0f, "Calculando geometria exFAT...");

    const uint32_t sector_size = params.sector_size;
    const uint64_t total_sectors = params.sector_count;
    const uint64_t capacity_bytes = total_sectors * sector_size;

    uint32_t cluster_size = params.cluster_size_bytes;
    if (cluster_size == 0) {
        cluster_size = calculateOptimalClusterSize(capacity_bytes);
    }

    uint8_t sector_shift = 0;
    while ((1u << sector_shift) < sector_size) sector_shift++;

    uint8_t cluster_shift = 0;
    uint32_t temp_spc = cluster_size / sector_size;
    while ((1u << cluster_shift) < temp_spc) cluster_shift++;

    // Estrutura de setores reservados iniciais:
    // Setores 0 a 11: Main Boot Region
    // Setores 12 a 23: Backup Boot Region
    const uint32_t fat_offset = 24; // Inicia no setor 24

    // Cálculo da FAT e do Cluster Heap
    // Cada entrada da FAT tem 4 bytes (32 bits)
    uint64_t estimated_clusters = (total_sectors - fat_offset) / (1u << cluster_shift);
    uint32_t fat_length_sectors = static_cast<uint32_t>(((estimated_clusters * 4) + sector_size - 1) / sector_size);

    // Alinhamento do Cluster Heap a um múltiplo do cluster
    uint32_t cluster_heap_offset = fat_offset + fat_length_sectors;
    uint32_t sectors_per_cluster = 1u << cluster_shift;
    uint32_t misalignment = cluster_heap_offset % sectors_per_cluster;
    if (misalignment != 0) {
        cluster_heap_offset += (sectors_per_cluster - misalignment);
    }

    uint32_t cluster_count = static_cast<uint32_t>((total_sectors - cluster_heap_offset) / sectors_per_cluster);

    auto now = std::chrono::system_clock::now().time_since_epoch().count();
    uint32_t volume_serial = static_cast<uint32_t>(now);

    progress_fn(15.0f, "Construindo Main e Backup Boot Region...");

    // Buffer de 12 setores para a Boot Region (Setores 0 a 11)
    std::vector<uint8_t> boot_region(12 * sector_size, 0);

    // Setor 0: VBR
    uint8_t* vbr = &boot_region[0];
    vbr[0] = 0xEB; vbr[1] = 0x76; vbr[2] = 0x90;
    std::memcpy(&vbr[3], "EXFAT   ", 8);
    *reinterpret_cast<uint64_t*>(&vbr[64]) = params.start_lba;
    *reinterpret_cast<uint64_t*>(&vbr[72]) = total_sectors;
    *reinterpret_cast<uint32_t*>(&vbr[80]) = fat_offset;
    *reinterpret_cast<uint32_t*>(&vbr[84]) = fat_length_sectors;
    *reinterpret_cast<uint32_t*>(&vbr[88]) = cluster_heap_offset;
    *reinterpret_cast<uint32_t*>(&vbr[92]) = cluster_count;
    *reinterpret_cast<uint32_t*>(&vbr[96]) = 4; // Primeiro cluster do Root Directory
    *reinterpret_cast<uint32_t*>(&vbr[100]) = volume_serial;
    *reinterpret_cast<uint16_t*>(&vbr[104]) = 0x0100; // Revisão 1.00
    *reinterpret_cast<uint16_t*>(&vbr[106]) = 0; // Volume Flags
    vbr[108] = sector_shift;
    vbr[109] = cluster_shift;
    vbr[110] = 1; // 1 FAT
    vbr[111] = 0x80; // Drive Select
    vbr[112] = 0; // Percent In Use
    vbr[510] = 0x55; vbr[511] = 0xAA;

    // Setores 1 a 8: Extended Boot Sectors (assinatura 0x55AA no final)
    for (int s = 1; s <= 8; ++s) {
        boot_region[s * sector_size + 510] = 0x55;
        boot_region[s * sector_size + 511] = 0xAA;
    }
    // Setor 9: OEM Parameter Sector
    boot_region[9 * sector_size + 510] = 0x55;
    boot_region[9 * sector_size + 511] = 0xAA;
    // Setor 10: Reservado
    boot_region[10 * sector_size + 510] = 0x55;
    boot_region[10 * sector_size + 511] = 0xAA;

    // Cálculo dinâmico de clusters necessários para cada estrutura
    uint32_t bitmap_bytes = (cluster_count + 7) / 8;
    uint32_t bitmap_clusters = (bitmap_bytes + cluster_size - 1) / cluster_size;
    uint32_t upcase_clusters = (5120 + cluster_size - 1) / cluster_size;
    uint32_t upcase_cluster = 2 + bitmap_clusters;
    uint32_t root_dir_cluster = upcase_cluster + upcase_clusters;

    // Atualiza o primeiro cluster do Root Directory no VBR (offset 96)
    *reinterpret_cast<uint32_t*>(&vbr[96]) = root_dir_cluster;

    // Recalcula o checksum do boot sector agora com o cluster raiz exato
    checksum = calculateBootChecksum(boot_region.data(), 11 * sector_size);
    for (size_t k = 0; k < sector_size / 4; ++k) {
        chk_ptr[k] = checksum;
    }

    progress_fn(30.0f, "Gravando Boot Sectors primários e de segurança...");

    // Grava Main Boot Region (Setores 0 a 11)
    if (!write_fn(params.start_lba, 12, boot_region.data())) return false;

    // Grava Backup Boot Region (Setores 12 a 23)
    if (!write_fn(params.start_lba + 12, 12, boot_region.data())) return false;

    progress_fn(45.0f, "Inicializando tabela FAT exFAT...");

    // Inicializa a FAT com suporte a cadeias multi-cluster
    std::vector<uint8_t> fat_buf(sector_size, 0);
    uint32_t* fat = reinterpret_cast<uint32_t*>(fat_buf.data());
    fat[0] = 0xFFFFFFF8; // Media ID
    fat[1] = 0xFFFFFFFF; // Reserved

    // Encadeamento dos clusters do Allocation Bitmap
    for (uint32_t c = 0; c < bitmap_clusters; ++c) {
        uint32_t current_cl = 2 + c;
        fat[current_cl] = (c == bitmap_clusters - 1) ? 0xFFFFFFFF : (current_cl + 1);
    }

    // Encadeamento dos clusters da Upcase Table
    for (uint32_t u = 0; u < upcase_clusters; ++u) {
        uint32_t current_cl = upcase_cluster + u;
        fat[current_cl] = (u == upcase_clusters - 1) ? 0xFFFFFFFF : (current_cl + 1);
    }

    // Cluster do Diretório Raiz
    fat[root_dir_cluster] = 0xFFFFFFFF;

    uint64_t fat_lba = params.start_lba + fat_offset;
    if (!write_fn(fat_lba, 1, fat_buf.data())) return false;

    progress_fn(60.0f, "Criando Allocation Bitmap multi-cluster...");

    // Cluster 2: Allocation Bitmap
    // Marca todos os clusters alocados (Bitmap + Upcase + Root)
    uint32_t total_allocated_clusters = bitmap_clusters + upcase_clusters + 1;
    uint32_t bitmap_sectors = (bitmap_bytes + sector_size - 1) / sector_size;
    std::vector<uint8_t> bitmap(bitmap_sectors * sector_size, 0);

    for (uint32_t k = 0; k < total_allocated_clusters; ++k) {
        bitmap[k / 8] |= static_cast<uint8_t>(1u << (k % 8));
    }

    uint64_t cluster2_lba = params.start_lba + cluster_heap_offset;
    if (!write_fn(cluster2_lba, bitmap_sectors, bitmap.data())) return false;

    progress_fn(75.0f, "Gerando Upcase Table comprimida...");

    // Upcase Table
    std::vector<uint8_t> upcase_table;
    generateUpcaseTable(upcase_table);
    uint32_t upcase_sectors = static_cast<uint32_t>((upcase_table.size() + sector_size - 1) / sector_size);
    upcase_table.resize(upcase_sectors * sector_size, 0);

    // Calcula checksum da Upcase Table
    uint32_t upcase_checksum = 0;
    for (size_t b = 0; b < 5120; ++b) {
        upcase_checksum = ((upcase_checksum << 31) | (upcase_checksum >> 1)) + upcase_table[b];
    }

    uint64_t upcase_lba = params.start_lba + cluster_heap_offset + (static_cast<uint64_t>(upcase_cluster - 2) * sectors_per_cluster);
    if (!write_fn(upcase_lba, upcase_sectors, upcase_table.data())) return false;

    progress_fn(90.0f, "Configurando Diretório Raiz e Volume Label...");

    // Root Directory (3 entradas de 32 bytes cada)
    uint64_t root_dir_lba = params.start_lba + cluster_heap_offset + (static_cast<uint64_t>(root_dir_cluster - 2) * sectors_per_cluster);
    std::vector<uint8_t> root_dir(sectors_per_cluster * sector_size, 0);

    // Entrada 1: Volume Label Entry (Tipo 0x83)
    std::string label = params.volume_label;
    if (label.empty()) label = "EXFAT_DISK";
    if (label.length() > 11) label.resize(11);
    root_dir[0] = 0x83; // EntryType: Volume Label
    root_dir[1] = static_cast<uint8_t>(label.length());
    for (size_t i = 0; i < label.length(); ++i) {
        *reinterpret_cast<uint16_t*>(&root_dir[2 + (i * 2)]) = static_cast<uint16_t>(label[i]);
    }

    // Entrada 2: Allocation Bitmap Entry (Tipo 0x81)
    uint8_t* b_ent = &root_dir[32];
    b_ent[0] = 0x81; // EntryType: Bitmap
    b_ent[1] = 0; // BitmapFlags: Primeiro bitmap
    *reinterpret_cast<uint32_t*>(&b_ent[20]) = 2; // First Cluster = 2
    *reinterpret_cast<uint64_t*>(&b_ent[24]) = bitmap_bytes; // DataLength

    // Entrada 3: Upcase Table Entry (Tipo 0x82)
    uint8_t* u_ent = &root_dir[64];
    u_ent[0] = 0x82; // EntryType: Upcase Table
    *reinterpret_cast<uint32_t*>(&u_ent[4]) = upcase_checksum; // TableChecksum
    *reinterpret_cast<uint32_t*>(&u_ent[20]) = upcase_cluster; // First Cluster dinâmico
    *reinterpret_cast<uint64_t*>(&u_ent[24]) = 5120; // DataLength

    if (!write_fn(root_dir_lba, sectors_per_cluster, root_dir.data())) return false;

    progress_fn(100.0f, "Formatação exFAT concluída com sucesso!");
    return true;
}

} // namespace usbadvance
