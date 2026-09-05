<p align="center">
  <img src="art/logo.jpg" alt="USB Advance Logo" width="160" style="border-radius: 28px;" />
</p>

<h1 align="center">USB Advance</h1>

<p align="center">
  <b>High-Performance, Low-Level USB/OTG Storage Manager &amp; Formatter for Android</b>
</p>

<p align="center">
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
  <img src="https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-3DDC84.svg?logo=android&logoColor=white" alt="Android 10+" />
  <img src="https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-brightgreen.svg" alt="Target SDK 35" />
  <img src="https://img.shields.io/badge/ELF-16KB%20Pages%20Ready-purple.svg" alt="16KB Page Size Ready" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?logo=kotlin&logoColor=white" alt="Kotlin 2.0.21" />
  <img src="https://img.shields.io/badge/C%2B%2B-20-00599C.svg?logo=c%2B%2B&logoColor=white" alt="C++20" />
  <img src="https://img.shields.io/badge/Engineered%20with-AI%20%E2%9C%A8-blueviolet.svg" alt="Engineered with AI" />
</p>

---

## 📖 Overview

**USB Advance** is a modern low-level systems engineering utility for Android that enables you to **format, partition, burn ISO images, benchmark speed, and detect counterfeit USB flash drives** directly on your smartphone or tablet through a USB OTG adapter.

Unlike traditional Android file managers constrained by the restrictive Storage Access Framework (SAF), **USB Advance implements a complete userspace USB Mass Storage Class (BOT) and SCSI protocol driver**. This allows reading and writing physical raw Logical Block Addresses (LBAs) directly **without requiring root privileges**.

For rooted power users, USB Advance also provides an optional direct block-device backend with Android `vold` daemon coordination for internal storage and MicroSD cards.

---

## 🤖 Built with AI & Open Source Collaboration

> [!NOTE]
> **This project was engineered in pair-programming collaboration with AI (Google DeepMind's advanced coding agent, Antigravity).**

USB Advance showcases the frontier of what human-directed, agentic AI systems engineering can build:
* **Custom Userspace USB BOT / SCSI Driver**: Fully compliant Bulk-Only Transport state machine with resilient endpoint recovery, STALL clear handling, and progressive CSW read backoff.
* **Zero-Allocation C++20 Formatting Engines**: Handcrafted native formatters for exFAT, NTFS, ext4, and FAT32 with 16 KB ELF page size alignment for Android 15 & 16.
* **Modern Jetpack Compose Architecture**: Reactive, high-performance UI running smooth background I/O on `Dispatchers.IO` without ever blocking the UI thread.

**Everyone is warmly invited to use, study, fork, and contribute!** Whether you want to add new filesystem formats, improve translation localization, test obscure OTG chipsets, or submit bug fixes, pull requests and issues from all developers are welcome.

---

## ✨ Key Features

### 🔌 100% Rootless USB Host Driver
- Communicates directly with flash drives, external SSDs, and card readers via `android.hardware.usb.UsbManager` with userspace `claimInterface(force = true)`.
- Full implementation of the **USB Bulk-Only Transport (BOT 1.0)** protocol and **SCSI Block Commands (SBC-3 / SPC-4)**.
- Bulletproof multi-attempt CSW reading with backoff and automatic STALL recovery designed for slow NAND write cycles.

### 💾 Comprehensive Filesystem Engines
- **exFAT**: Full modern exFAT formatting with automatically computed Allocation Bitmaps and 128 KB Up-case tables for files larger than 4 GB.
- **NTFS**: Native Windows compatibility with standard 4 KB clusters and valid Master File Table (MFT) layout.
- **FAT32**: Universal compatibility across smart TVs, PCs, retro consoles, car stereos, and Android.
- **ext4**: Native Linux filesystem with optional zero-journaling mode to extend flash memory lifespan.
- **FAT16**: Legacy support for industrial machinery, older synthesizers, and vintage hardware.

### 📐 Precision Flash Partitioning
- **1 MiB Flash Alignment**: All partition starts are strictly aligned to LBA 2048 ($2048 \times 512 = 1\text{ MiB}$), matching physical NAND erase blocks for maximum I/O speed and flash longevity.
- **MBR (Master Boot Record)**: Standard 4-partition table with 32-bit sector overflow protection for drives larger than 2 TB.
- **GPT (GUID Partition Table)**: Full Primary and Backup GPT headers with CRC32 checksums and unique GUID generation.

### 🛡️ Counterfeit Drive Detector (Anti-Fraud)
- **Smart Boundary Quick Probe (~45 seconds)**: Strategically tests logarithmic power-of-2 memory boundaries (512 MB, 1 GB, 2 GB, 4 GB, 8 GB, 16 GB, 32 GB, 64 GB...) with cryptographic nonce signatures to immediately catch firmware address wrap-around without filling the whole drive.
- **H2testw Sequential Fill & Verify**: Full disk capacity soak test with pseudo-random sector verification for comprehensive stress testing.

### ⚡ Non-Destructive Real-World Benchmark
- Accurately measures sequential Read and Write speeds in MB/s.
- Configurable I/O buffer blocks (64 KB to 16 MB).
- **100% Non-Destructive**: Reads, backs up, tests, and safely restores original sector data with zero data loss.

### 🔥 Bootable ISO / IMG Writer
- Burn Linux live distributions, Windows installation images, or raw disk images directly to your USB drive from your phone.

### 🔒 Enterprise Safety Architecture
- Hardware write-protect verification via SCSI.
- Strict confirmation barrier to prevent accidental formatting.
- Complete blacklisting of internal system partitions (`bootdevice`, `userdata`, `system`).
- Safe ejection protocol with SCSI cache flush and `START STOP UNIT` spin-down.

### 🚀 Modern Android 15 & 16 Ready
- Fully compliant with **16 KB memory page size requirements** in Android 15 and 16 via NDK r27b+ (`-Wl,-z,max-page-size=16384`).
- Android 14 `ForegroundService` with `connectedDevice|dataSync` types for uninterrupted long operations.

---

## 🏗️ Architecture

The codebase is organized into a clean, decoupled two-module architecture:

```
USB_Advance/
├── core/                        # Low-Level Core Library (:core)
│   ├── src/main/cpp/            # High-performance C++20 native formatters (exFAT, NTFS, ext4, FAT32)
│   └── src/main/kotlin/
│       └── org/usbadvance/core/
│           ├── fs/              # JNI Bridge & Filesystem Providers
│           ├── partition/       # MBR & GPT Engines and Flash Alignment
│           ├── root/            # Rooted SuRandomAccessFile block device backend
│           ├── storage/         # IBlockDevice & IStorageDevice API contracts
│           └── usb/             # Rootless USB Host BOT protocol & SCSI commands
│
└── app/                         # Jetpack Compose Application (:app)
    └── src/main/kotlin/
        └── org/usbadvance/
            ├── feature/
            │   ├── devicelist/  # OTG detection, device hub & visualizer
            │   ├── diagnostic/  # Anti-fraud fake detector & speed benchmark
            │   ├── formatter/   # Format wizard, ISO burner & safety dialogs
            │   └── settings/    # App settings & developer overlay
            └── ui/              # Main navigation, Material 3 Dark Obsidian theme
```

---

## 🛠️ Building from Source

### Prerequisites
* **Android Studio Ladybug (2024.2+)** or later
* **JDK 21** (e.g. JetBrains Runtime or OpenJDK 21)
* **Android SDK Platform 35**
* **Android NDK r27b** or newer (required for 16 KB page-size ELF alignment)
* **CMake 3.22.1+**

### Build Commands

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-org/USB_Advance.git
   cd USB_Advance
   ```

2. **Run unit tests**:
   ```bash
   ./gradlew testDebugUnitTest
   ```

3. **Assemble Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Assemble Release APK** (with ProGuard/R8 minification and 16 KB ELF alignment):
   ```bash
   ./gradlew assembleRelease
   ```
   The optimized APK (~8.7 MB) will be generated at `app/build/outputs/apk/release/app-release.apk`.

---

## 🤝 Contributing

Contributions of all kinds are welcome! Whether you are:
* Fixing bugs or improving hardware compatibility with peculiar USB controllers
* Adding support for additional filesystems (such as Btrfs or F2FS)
* Enhancing translations (check `res/values/strings.xml` and `res/values-*/`)
* Suggesting new diagnostic tools or UI refinements

Please read our [Contributing Guide](CONTRIBUTING.md) to get started.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.
