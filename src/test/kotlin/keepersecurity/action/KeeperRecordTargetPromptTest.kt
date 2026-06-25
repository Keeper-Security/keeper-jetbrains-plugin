package keepersecurity.action

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [KeeperRecordTargetPrompt]. The dialog itself can't be driven
 * from a unit test (it needs the EDT plus a real `Messages.showDialog`
 * callback), and a `BasePlatformTestCase` fixture trips a coroutines /
 * test-framework mismatch in the current IntelliJ Platform version — so
 * this test focuses on the prompt's *static* contract:
 *
 *  - preference key names are stable across releases,
 *  - the `AddOutcome` sealed hierarchy stays exhaustive (this is the
 *    contract every Add / Generate caller switches on).
 *
 * The Update prompt was retired in favour of
 * [keepersecurity.util.KeeperRecordValidator] (record UID lookup against
 * `list --format json`), so the old `Target` enum / `UpdateOutcome` /
 * `PREF_LAST_TARGET` surface is gone. Per-project persistence is
 * verified indirectly via the action-level smoke tests in
 * `KeeperActionTestSuite`.
 */
class KeeperRecordTargetPromptTest {

    @Before
    fun setUp() {
        System.setProperty("keeper.test.mode", "true")
        System.setProperty("java.awt.headless", "true")
    }

    @After
    fun tearDown() {
        System.clearProperty("keeper.test.mode")
        System.clearProperty("java.awt.headless")
    }

    @Test
    fun `test preference keys are stable across releases`() {
        // These keys are persisted in user workspaces — renaming them would
        // silently invalidate every selection the user has already made.
        assertEquals("keeper.drive.folder.uuid", KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_UUID)
        assertEquals("keeper.drive.folder.name", KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_NAME)
        assertEquals("keeper.folder.uuid", KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_UUID)
        assertEquals("keeper.folder.name", KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_NAME)
    }

    @Test
    fun `test AddOutcome sealed shape supports Cancelled, Classic and Drive`() {
        // Compile-time exhaustiveness check: if a new branch is added, this
        // `when` will start failing to compile and force the test to be
        // updated, which is exactly the canary we want.
        val outcomes = listOf(
            KeeperRecordTargetPrompt.AddOutcome.Cancelled,
            KeeperRecordTargetPrompt.AddOutcome.Classic(folderUuid = "uuid", folderName = "name"),
            KeeperRecordTargetPrompt.AddOutcome.Drive(folderUuid = "uuid", folderName = "name")
        )
        outcomes.forEach { outcome ->
            val label = when (outcome) {
                is KeeperRecordTargetPrompt.AddOutcome.Cancelled -> "cancelled"
                is KeeperRecordTargetPrompt.AddOutcome.Classic -> "classic"
                is KeeperRecordTargetPrompt.AddOutcome.Drive -> "drive"
            }
            assertNotNull(label)
        }
    }

    @Test
    fun `test Classic AddOutcome carries optional folder UUID and name`() {
        val withFolder = KeeperRecordTargetPrompt.AddOutcome.Classic("uuid-1", "Classic Folder")
        val classicRoot = KeeperRecordTargetPrompt.AddOutcome.Classic(folderUuid = null, folderName = null)

        assertEquals("uuid-1", withFolder.folderUuid)
        assertEquals("Classic Folder", withFolder.folderName)
        assertNull(classicRoot.folderUuid)
        assertNull(classicRoot.folderName)
    }

    @Test
    fun `test Drive AddOutcome carries optional folder UUID and name`() {
        val withFolder = KeeperRecordTargetPrompt.AddOutcome.Drive("uuid-1", "Drive Folder")
        val driveRoot = KeeperRecordTargetPrompt.AddOutcome.Drive(folderUuid = null, folderName = null)

        assertEquals("uuid-1", withFolder.folderUuid)
        assertEquals("Drive Folder", withFolder.folderName)
        assertNull(driveRoot.folderUuid)
        assertNull(driveRoot.folderName)
    }
}
