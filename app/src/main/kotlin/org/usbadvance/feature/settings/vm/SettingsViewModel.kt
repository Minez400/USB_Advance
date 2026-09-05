package org.usbadvance.feature.settings.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow
import org.usbadvance.feature.settings.data.SettingsManager
import org.usbadvance.feature.settings.model.AppSettings

class SettingsViewModel(private val settingsManager: SettingsManager) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsManager.settings

    fun setLanguage(tag: String) = settingsManager.setLanguage(tag)

    fun setDefaultFileSystem(fs: String) = settingsManager.setDefaultFileSystem(fs)

    fun setDefaultQuickFormat(enabled: Boolean) = settingsManager.setDefaultQuickFormat(enabled)

    fun setStrictSafetyConfirmation(enabled: Boolean) = settingsManager.setStrictSafetyConfirmation(enabled)

    fun setIoBlockSize(sizeBytes: Int) = settingsManager.setIoBlockSize(sizeBytes)

    fun setDeveloperMode(enabled: Boolean) = settingsManager.setDeveloperMode(enabled)

    fun setEnableFakeUsbDrive(enabled: Boolean) = settingsManager.setEnableFakeUsbDrive(enabled)

    class Factory(private val settingsManager: SettingsManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsManager) as T
        }
    }
}
