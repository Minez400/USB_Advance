# Contributing to USB Advance

Thank you for your interest in contributing to **USB Advance**! 

This is an open-source project created through human-AI pair-programming collaboration. Whether you are a developer, security researcher, tester, or translator, contributions from all skill levels are warmly welcome.

---

## 🛠️ How to Contribute

### 1. Fork and Clone
1. Fork the repository on GitHub.
2. Clone your fork locally:
   ```bash
   git clone https://github.com/your-username/USB_Advance.git
   cd USB_Advance
   ```
3. Create a descriptive feature or bugfix branch:
   ```bash
   git checkout -b feature/btrfs-support
   # or
   git checkout -b fix/usb-bot-stall-recovery
   ```

### 2. Code Standards
* **Kotlin (Jetpack Compose)**:
  * Follow official Kotlin coding conventions.
  * Keep coroutine I/O off the main thread: all USB transfers and disk access must run on `Dispatchers.IO`.
  * Compose UI state should be driven reactively via `StateFlow`.
* **C++20 (NDK)**:
  * Use modern C++20 features and RAII (`std::unique_ptr`, `std::span`).
  * Ensure all native binaries compile with 16 KB page-size ELF alignment (`-Wl,-z,max-page-size=16384`).
  * Avoid raw memory allocations and memory leaks.
* **Code Comments**:
  * All in-code comments and docstrings must be written in **English**.
* **Translations / Localization**:
  * User-facing UI strings reside in `app/src/main/res/values/strings.xml` (default English) and `app/src/main/res/values-pt/strings.xml` (Portuguese). Additional language translations are encouraged!

---

## 🧪 Testing Guidelines

Before opening a pull request, ensure the unit test suite and builds succeed locally:

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Verify Release compilation and R8 minification
./gradlew assembleRelease
```

---

## 📬 Submitting a Pull Request

1. Push your branch to your GitHub fork:
   ```bash
   git push origin feature/your-feature-name
   ```
2. Open a Pull Request against the `main` branch.
3. Provide a clear, detailed summary of your changes, motivation, and any hardware/OTG devices used for testing.

Thank you for helping make USB Advance better for everyone!
