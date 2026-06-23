package keepersecurity.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeperListPickerItemTest {

    @Test
    fun `matchesSearch finds label substring`() {
        val item = KeeperListPickerItem("Production API Key", KeeperVaultBadge.NESTED)
        assertTrue(item.matchesSearch("production"))
    }

    @Test
    fun `matchesSearch finds badge text`() {
        val item = KeeperListPickerItem("Shared Login", KeeperVaultBadge.CLASSIC)
        assertTrue(item.matchesSearch("classic"))
        assertFalse(item.matchesSearch("nested"))
    }

    @Test
    fun `matchesSearch accepts empty query`() {
        val item = KeeperListPickerItem("Anything", KeeperVaultBadge.NESTED)
        assertTrue(item.matchesSearch(""))
    }
}
