# Data Safety Guidelines - USB Advance

## 1. Fundamental Principles
Disk formatting is inherently destructive. USB Advance implements multi-layered safety barriers to prevent accidental data loss:

### 1.1 Rootless Hardware Isolation
In default (non-root) mode, the application operates strictly through the Android USB Host API (`android.hardware.usb.UsbManager`). Android only provides USB device descriptors for externally attached USB devices. There is no code path or capability through which internal storage (`/data`, `/system`, `/vendor`) could ever be accessed or touched.

### 1.2 Strict Block Device Blacklisting (Root Mode)
In Root mode, `RootBlockDevice` enforces strict path validation before opening any device node:
* Nodes containing `bootdevice`, `userdata`, `system`, `vendor`, or `mmcblk0` trigger an immediate `SecurityException`, permanently blocking any writes to the smartphone's internal eMMC or UFS flash memory.
* Only external removable media device nodes (`sd[a-z]` or `mmcblk1`) are permitted.

### 1.3 Strict Type-In Confirmation Barrier
Before any destructive partition or boot sector write command is issued, the user is presented with a red safety confirmation modal displaying:
* Device Name;
* Exact Capacity;
* Hardware Identifiers and target filesystem.

The execution button remains strictly disabled until the user manually types the confirmation keyword, preventing accidental taps.

### 1.4 WakeLock and Unexpected Disconnection Protection
* A partial CPU WakeLock (`PARTIAL_WAKE_LOCK`) is acquired to prevent the device SoC from sleeping during lengthy format operations.
* `FormatForegroundService` guarantees top process priority against the Android Low Memory Killer (LMK).
* `UsbHostDetector` listens for `ACTION_USB_DEVICE_DETACHED` broadcasts, immediately aborting active write coroutines and safely releasing resources if the OTG cable is unplugged mid-operation.
