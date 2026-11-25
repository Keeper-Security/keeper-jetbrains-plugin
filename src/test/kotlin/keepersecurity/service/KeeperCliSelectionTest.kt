package keepersecurity.service

import keepersecurity.model.KeeperCliFlavor
import keepersecurity.model.KeeperCliInstall
import keepersecurity.service.KeeperCliSelection.Resolution
import keepersecurity.service.KeeperCliSelection.Saved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules in [KeeperCliSelection.resolve]. Pure logic, no IDE needed. */
class KeeperCliSelectionTest {

    private val pip = KeeperCliInstall("/usr/local/bin/keeper", "/usr/local/keepercommandercli/bin/keeper-commander",
        KeeperCliFlavor.COMMANDER, "18.1.8")
    private val smkProd = smk("keeper.corp.com")
    private val smkLocal = smk("localhost")

    private fun smk(host: String): KeeperCliInstall {
        val path = "/Users/me/.keeper-smk/$host/bin/keeper-smk"
        return KeeperCliInstall(path, path, KeeperCliFlavor.SMK, "18.1.8", smkHost = host)
    }

    @Test fun `saved choice that is still installed wins`() {
        val r = KeeperCliSelection.resolve(listOf(smkProd, pip), Saved(pip.id, pip.displayLabel()))
        assertEquals(Resolution.Active(pip, autoPickedFromMany = false), r)
    }

    @Test fun `saved choice that is gone is an error with no silent fallback`() {
        val saved = Saved(smkProd.id, smkProd.displayLabel())
        val r = KeeperCliSelection.resolve(listOf(pip), saved)
        assertTrue(r is Resolution.SavedMissing)
        r as Resolution.SavedMissing
        assertEquals(saved, r.saved)
        assertEquals(listOf(pip), r.available)
    }

    @Test fun `saved choice with nothing installed is still SavedMissing`() {
        val r = KeeperCliSelection.resolve(emptyList(), Saved(pip.id, null))
        assertTrue(r is Resolution.SavedMissing)
    }

    @Test fun `single install with no saved choice is used without a notice`() {
        val r = KeeperCliSelection.resolve(listOf(pip), saved = null)
        assertEquals(Resolution.Active(pip, autoPickedFromMany = false), r)
    }

    @Test fun `several installs prefer SMK and flag the auto pick`() {
        val r = KeeperCliSelection.resolve(listOf(pip, smkProd), saved = null) as Resolution.Active
        assertEquals(smkProd, r.install)
        assertTrue(r.autoPickedFromMany)
    }

    @Test fun `several SMK hosts pick the first host alphabetically`() {
        val r = KeeperCliSelection.resolve(listOf(pip, smkLocal, smkProd), saved = null) as Resolution.Active
        assertEquals("keeper.corp.com", r.install.smkHost)
    }

    @Test fun `several Commander installs with no SMK pick the first detected`() {
        val other = KeeperCliInstall("/opt/homebrew/bin/keeper", "/opt/homebrew/bin/keeper", KeeperCliFlavor.COMMANDER, "17.0.0")
        val r = KeeperCliSelection.resolve(listOf(pip, other), saved = null) as Resolution.Active
        assertEquals(pip, r.install)
        assertTrue(r.autoPickedFromMany)
    }

    @Test fun `nothing installed and nothing saved is NoneFound`() {
        assertEquals(Resolution.NoneFound, KeeperCliSelection.resolve(emptyList(), saved = null))
    }

    @Test fun `module installs have a distinct id from executables`() {
        val module = KeeperCliInstall("python3 -m keepercommander", "python3 -m keepercommander",
            KeeperCliFlavor.COMMANDER, "18.1.8", isModule = true)
        assertEquals("module:python3 -m keepercommander", module.id)
        assertFalse(module.id == pip.id)
    }
}
