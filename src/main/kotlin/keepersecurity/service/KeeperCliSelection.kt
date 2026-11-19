package keepersecurity.service

import com.intellij.ide.util.PropertiesComponent
import keepersecurity.model.KeeperCliFlavor
import keepersecurity.model.KeeperCliInstall

/**
 * Decides which detected CLI the plugin uses, and remembers the user's explicit choice.
 *
 * Rules ([resolve]):
 *  1. A saved choice that is still installed wins.
 *  2. A saved choice that is no longer installed is an error. There's no silent
 *     fallback to another CLI, because Commander and each SMK host talk to
 *     different servers and vaults.
 *  3. With no saved choice, a single install is used as-is.
 *  4. With no saved choice and several installs, SMK is preferred (first host
 *     alphabetically). The result is flagged so the caller can tell the user
 *     how to switch.
 *
 * Only an explicit pick from "Switch Keeper CLI" is persisted. Auto-picks are
 * recomputed on every shell start.
 */
object KeeperCliSelection {
    const val PREF_SELECTED_ID = "keeper.cli.selected.id"
    const val PREF_SELECTED_LABEL = "keeper.cli.selected.label"

    data class Saved(val id: String, val label: String?)

    sealed interface Resolution {
        data class Active(val install: KeeperCliInstall, val autoPickedFromMany: Boolean) : Resolution
        data class SavedMissing(val saved: Saved, val available: List<KeeperCliInstall>) : Resolution
        data object NoneFound : Resolution
    }

    fun resolve(installs: List<KeeperCliInstall>, saved: Saved?): Resolution {
        if (saved != null) {
            val match = installs.firstOrNull { it.id == saved.id }
            return if (match != null) Resolution.Active(match, autoPickedFromMany = false)
            else Resolution.SavedMissing(saved, installs)
        }
        return when (installs.size) {
            0 -> Resolution.NoneFound
            1 -> Resolution.Active(installs.single(), autoPickedFromMany = false)
            else -> {
                val preferred = installs
                    .filter { it.flavor == KeeperCliFlavor.SMK }
                    .minByOrNull { it.smkHost ?: it.path }
                    ?: installs.first()
                Resolution.Active(preferred, autoPickedFromMany = true)
            }
        }
    }

    fun loadSaved(): Saved? {
        val props = PropertiesComponent.getInstance()
        val id = props.getValue(PREF_SELECTED_ID)?.takeIf { it.isNotBlank() } ?: return null
        return Saved(id, props.getValue(PREF_SELECTED_LABEL))
    }

    fun save(install: KeeperCliInstall) {
        val props = PropertiesComponent.getInstance()
        props.setValue(PREF_SELECTED_ID, install.id)
        props.setValue(PREF_SELECTED_LABEL, install.displayLabel())
    }
}
