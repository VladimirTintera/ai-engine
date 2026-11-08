package eu.tintera.ai.core

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Zátěž telefonu podle systému: úsporný režim (`isPowerSaveMode`) nebo teplota od
 * `THERMAL_STATUS_MODERATE` (API 29+). Mírné omezování výkonu systém hlásí už tam, a model
 * o gigabajtech, který by se teprve načítal, je přesně to, co se pak má odložit — dál
 * (`SEVERE`) už telefon citelně zpomaluje i bez nás.
 *
 * Bez naslouchání změnám: [strained] se čte těsně před tím, než by se model pustil.
 */
class AndroidDeviceLoad(context: Context) : DeviceLoad {
    private val power = context.getSystemService(PowerManager::class.java)

    override val strained: Boolean
        get() {
            val power = power ?: return false
            if (power.isPowerSaveMode) return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        }
}
