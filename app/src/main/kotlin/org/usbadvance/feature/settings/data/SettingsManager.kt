package org.usbadvance.feature.settings.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.usbadvance.feature.settings.model.AppSettings

/**
 * High-performance, lightweight preferences manager for USB Advance.
 * Utilizes SharedPreferences backed by a reactive StateFlow.
 */
class SettingsManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val savedLang = prefs.getString(KEY_LANGUAGE, null)
        val languageTag = savedLang ?: if (!appLocales.isEmpty) {
            appLocales.toLanguageTags().split(",").firstOrNull()?.take(2) ?: ""
        } else {
            ""
        }

        val savedBlockSize = if (prefs.contains(KEY_IO_BLOCK_SIZE)) {
            prefs.getInt(KEY_IO_BLOCK_SIZE, 1048576)
        } else if (prefs.contains(KEY_BENCHMARK_BLOCK_SIZE)) {
            prefs.getInt(KEY_BENCHMARK_BLOCK_SIZE, 1) * 1024 * 1024
        } else {
            1048576
        }

        return AppSettings(
            languageTag = languageTag,
            defaultFileSystem = prefs.getString(KEY_DEFAULT_FS, "exFAT") ?: "exFAT",
            defaultQuickFormat = prefs.getBoolean(KEY_QUICK_FORMAT, true),
            strictSafetyConfirmation = prefs.getBoolean(KEY_STRICT_SAFETY, true),
            ioBlockSizeBytes = savedBlockSize,
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
            enableFakeUsbDrive = prefs.getBoolean(KEY_FAKE_USB_DRIVE, false)
        )
    }

    fun setLanguage(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
        _settings.value = _settings.value.copy(languageTag = tag)

        val locales = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setDefaultFileSystem(fs: String) {
        prefs.edit().putString(KEY_DEFAULT_FS, fs).apply()
        _settings.value = _settings.value.copy(defaultFileSystem = fs)
    }

    fun setDefaultQuickFormat(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_FORMAT, enabled).apply()
        _settings.value = _settings.value.copy(defaultQuickFormat = enabled)
    }

    fun setStrictSafetyConfirmation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT_SAFETY, enabled).apply()
        _settings.value = _settings.value.copy(strictSafetyConfirmation = enabled)
    }

    fun setIoBlockSize(sizeBytes: Int) {
        prefs.edit().putInt(KEY_IO_BLOCK_SIZE, sizeBytes).apply()
        _settings.value = _settings.value.copy(ioBlockSizeBytes = sizeBytes)
    }

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
        _settings.value = _settings.value.copy(developerMode = enabled)
    }

    fun setEnableFakeUsbDrive(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAKE_USB_DRIVE, enabled).apply()
        _settings.value = _settings.value.copy(enableFakeUsbDrive = enabled)
    }

    companion object {
        private const val PREFS_NAME = "usb_advance_preferences"
        private const val KEY_LANGUAGE = "pref_language"
        private const val KEY_DEFAULT_FS = "pref_default_fs"
        private const val KEY_QUICK_FORMAT = "pref_quick_format"
        private const val KEY_STRICT_SAFETY = "pref_strict_safety"
        private const val KEY_IO_BLOCK_SIZE = "pref_io_block_size"
        private const val KEY_BENCHMARK_BLOCK_SIZE = "pref_benchmark_block_size"
        private const val KEY_DEVELOPER_MODE = "pref_developer_mode"
        private const val KEY_FAKE_USB_DRIVE = "pref_fake_usb_drive"

        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }
}
