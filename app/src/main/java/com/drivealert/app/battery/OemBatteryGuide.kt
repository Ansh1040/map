package com.drivealert.app.battery

import androidx.annotation.StringRes
import com.drivealert.app.R

data class OemGuide(
    @StringRes val title: Int,
    @StringRes val path: Int,
    @StringRes val note: Int,
)

/**
 * Manufacturer-specific wording for the battery setting that most often stops
 * foreground services. Paths are guidance only: menu names change between
 * OS releases, which the UI states explicitly.
 */
object OemBatteryGuide {
    fun forFamily(family: DeviceFamily): OemGuide = when (family) {
        DeviceFamily.ONEPLUS_OPPO_REALME -> OemGuide(
            title = R.string.oem_oneplus_title,
            path = R.string.oem_oneplus_path,
            note = R.string.oem_oneplus_note,
        )
        DeviceFamily.XIAOMI -> OemGuide(R.string.oem_xiaomi_title, R.string.oem_xiaomi_path, R.string.oem_generic_note)
        DeviceFamily.VIVO -> OemGuide(R.string.oem_vivo_title, R.string.oem_vivo_path, R.string.oem_generic_note)
        DeviceFamily.SAMSUNG -> OemGuide(R.string.oem_samsung_title, R.string.oem_samsung_path, R.string.oem_generic_note)
        DeviceFamily.MOTOROLA, DeviceFamily.NOTHING, DeviceFamily.GOOGLE, DeviceFamily.OTHER ->
            OemGuide(R.string.oem_generic_title, R.string.oem_generic_path, R.string.oem_generic_note)
    }
}
