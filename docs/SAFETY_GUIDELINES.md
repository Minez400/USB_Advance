# Diretrizes de Segurança Contra Perda de Dados - USB Advance

## 1. Princípios Fundamentais
A formatação de uma unidade é destrutiva. O USB Advance implementa barreiras de proteção em três níveis:

### 1.1 Isolamento de Hardware Sem Root
No modo padrão (sem root), o aplicativo opera unicamente via `android.hardware.usb.UsbManager`. O framework do Android apenas entrega descritores para dispositivos conectados na porta USB externa. Não há nenhum caminho de código pelo qual a memória interna (`/data`, `/system`, `/vendor`) possa ser acessada.

### 1.2 Blacklist Estrita de Nós de Bloco (Modo Root)
No modo Root, o `RootBlockDevice` aplica uma checagem rigorosa antes de abrir qualquer descritor:
* Nós contendo `bootdevice`, `userdata`, `system`, `vendor` ou `mmcblk0` disparam uma `SecurityException` imediata, impedindo qualquer gravação acidental na memória eMMC/UFS do smartphone.
* Apenas nós com identificador de mídia externa removível (`sd[a-z]` ou `mmcblk1`) são aceitos.

### 1.3 Barreira de Confirmação por Digitação
Antes de qualquer comando de gravação de partição ou boot sector, o usuário é confrontado com uma tela de aviso vermelho detalhando:
* Nome do Dispositivo;
* Capacidade exata;
* Identificador físico.

O botão de confirmação permanece desabilitado até que o usuário digite a palavra **FORMATAR**, prevenindo toques acidentais na tela.

### 1.4 Prevenção de Desconexão Inesperada e WakeLock
* Um `WakeLock` parcial (`PARTIAL_WAKE_LOCK`) é retido para evitar que o SoC do celular entre em repouso durante a formatação.
* O `FormatForegroundService` assegura prioridade máxima contra o Low Memory Killer (LMK).
* O `UsbHostDetector` escuta o evento `ACTION_USB_DEVICE_DETACHED`, cancelando corrotinas ativas de escrita e reportando erro amigável caso o cabo OTG seja desconectado no meio do processo.
