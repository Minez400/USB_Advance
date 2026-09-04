# USB Advance 🚀
### Formatador e Gerenciador de Armazenamento USB/OTG de Alto Desempenho para Android

[![CI Status](https://github.com/your-org/USB_Advance/actions/workflows/ci.yml/badge.svg)](https://github.com/your-org/USB_Advance/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android Min SDK: 29](https://img.shields.io/badge/Min%20SDK-29%20(Android%2010)-green.svg)](https://developer.android.com)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-brightgreen.svg)](https://developer.android.com)
[![16KB Page Size Ready](https://img.shields.io/badge/Memory-16KB%20Pages%20Ready-purple.svg)](https://developer.android.com/guide/practices/page-sizes)

---

## 📌 Visão Geral

O **USB Advance** é uma ferramenta de engenharia de baixo nível para Android projetada para **formatar, particionar e diagnosticar dispositivos de armazenamento USB/OTG** (pendrives, SSDs/HDs externos e leitores de cartão) diretamente pelo smartphone ou tablet.

Diferente de soluções convencionais limitadas pelo Storage Access Framework (SAF) ou restrições de permissões do sistema, o **USB Advance implementa um driver completo de USB Mass Storage Class (BOT) e SCSI em userspace**. Isso possibilita **leitura e gravação de setores brutos (LBAs) sem necessidade de Root**.

Adicionalmente, para aparelhos rooteados, o aplicativo disponibiliza um backend direto de blocos com suporte ao gerenciamento do daemon `vold` e formatação de cartões MicroSD internos.

---

## ✨ Recursos Principais

* **Modo 100% Funcional Sem Root**:
  * Utiliza a API `android.hardware.usb.UsbManager` com `claimInterface(force = true)`.
  * Comunicação direta via protocolo **Bulk-Only Transport (BOT)** e conjunto de comandos **SCSI (SBC-3 / SPC-4)**.
* **Sistemas de Arquivos Suportados**:
  * **FAT32**: Compatibilidade universal com PCs, TVs, consoles de videogame e Android.
  * **exFAT**: Suporte a arquivos maiores que 4 GB com geração completa de *Upcase Table* e *Allocation Bitmap*.
  * **FAT16**: Otimizado para mídias legadas e equipamentos industriais ou de som automotivo antigo.
  * **ext4**: Sistema de arquivos nativo do ecossistema Linux com opções para desativar *journaling* (preservando a vida útil de memórias Flash).
* **Gerenciamento de Tabelas de Partição**:
  * **MBR (Master Boot Record)**: Suporte a 4 partições primárias.
  * **GPT (GUID Partition Table)**: Cabeçalhos primários e secundários (backup) com verificação estrita de integridade via CRC32.
  * **Alinhamento Flash Otimizado (1 MiB Alignment)**: Toda partição inicia no LBA 2048 ($2048 \times 512 = 1\text{ MiB}$), alinhando aos blocos de apagamento NAND para máxima velocidade e menor desgaste de hardware.
* **Configurações Avançadas**:
  * Customização de **Tamanho de Cluster (Allocation Unit)**: de 512 B até 64 KB.
  * Personalização do Nome do Volume (*Volume Label*) e Geração de UUID/Serial.
  * Escolha entre **Formatação Rápida (Quick Format)** e **Limpeza Completa de Setores (Zeroing / Full Wipe)**.
* **Arquitetura de Segurança em Camadas**:
  * Detecção de trava física de gravação (*Hardware Write-Protect*) via SCSI `MODE SENSE`.
  * Barreira de segurança anti-desastre: o usuário deve confirmar digitando a palavra de segurança para evitar formatações acidentais.
  * Bloqueio rigoroso de partições internas do sistema Android.
  * Tratamento resiliente de desconexão súbita do cabo OTG.
* **Compatibilidade com Android Moderno**:
  * Totalmente preparado para o requisito de **Páginas de 16 KB** do **Android 15 e 16** via NDK r27b+ com flags `-Wl,-z,max-page-size=16384`.
  * Execução em segundo plano via `ForegroundService` com os tipos oficiais do Android 14 (`connectedDevice|dataSync`).
* **Diagnóstico e Ferramentas**:
  * Teste de velocidade de barramento em tempo real (Leitura/Escrita sequencial).
  * Varredura e verificação de integridade de setores físicos.
  * Exportação de relatório técnico detalhado e anônimo para diagnóstico de erros SCSI.

---

## 🏗️ Arquitetura do Projeto

O projeto é modularizado de acordo com os princípios de **Clean Architecture**:

```
USB_Advance/
├── core/
│   ├── storage-api/     # Contratos, modelos e SPI (FilesystemProvider)
│   ├── usb/             # USB Host, Bulk-Only Transport (BOT) e comandos SCSI
│   ├── partition/       # Motores MBR, GPT e alinhamento 1 MiB
│   ├── fs-native/       # Formatadores de baixo nível em C++20 (FAT, exFAT, ext4)
│   └── root/            # Backend opcional com suporte a Root via libsu
├── feature/
│   ├── device-list/     # Scanner de hardware OTG em tempo real
│   ├── formatter/       # Wizard de formatação e execução em Foreground
│   └── diagnostic/      # Benchmark de I/O e leitor de integridade
└── app/                 # Orquestração, Navegação Compose e Inicialização
```

---

## ⚙️ Como Compilar

### Pré-requisitos
* **Android Studio Ladybug (2024.2+)** ou superior
* **JDK 17 ou 21**
* **Android SDK Platform 35**
* **Android NDK r27b** ou mais recente (para compatibilidade com páginas de 16 KB)
* **CMake 3.22.1+**

### Passo a Passo

1. Clone o repositório:
   ```bash
   git clone https://github.com/your-org/USB_Advance.git
   cd USB_Advance
   ```

2. Compile os módulos e gere o APK de depuração:
   ```bash
   ./gradlew assembleDebug
   ```

3. Execute a suíte de testes unitários:
   ```bash
   ./gradlew testDebugUnitTest
   ```

---

## 🛡️ Licença

Este projeto é distribuído sob os termos da licença **GNU General Public License v3.0 (GPL-3.0)**. Consulte o arquivo [LICENSE](LICENSE) para detalhes completos.
