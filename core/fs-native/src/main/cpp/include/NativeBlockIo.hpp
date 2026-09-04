#pragma once

#include <cstdint>
#include <cstddef>
#include <functional>
#include <string>

namespace usbadvance {

/**
 * Função de callback para escrita de setores contíguos na mídia física.
 * Parâmetros: (lba_inicial, quantidade_de_setores, ponteiro_dos_dados).
 * Retorna true se a escrita e confirmação foram bem-sucedidas.
 */
using WriteSectorsFn = std::function<bool(uint64_t lba, uint32_t count, const uint8_t* data)>;

/**
 * Função de callback para notificação de progresso da formatação.
 * Parâmetros: (etapa_percentual_0_a_100, descricao_etapa).
 */
using ProgressFn = std::function<void(float percentage, const std::string& description)>;

struct NativeFormatParams {
    uint64_t start_lba;
    uint64_t sector_count;
    uint32_t sector_size;
    uint32_t cluster_size_bytes; // 0 = automático
    std::string volume_label;
    bool quick_format;
    bool disable_journal; // Específico para ext4
};

} // namespace usbadvance
