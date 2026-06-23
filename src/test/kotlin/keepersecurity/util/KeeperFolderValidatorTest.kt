package keepersecurity.util

import keepersecurity.model.KeeperFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-function side of [KeeperFolderValidator].
 *
 * The CLI-touching `verifyBlocking` / `verifySavedFolder` paths require a
 * live shell and aren't covered here — the action-level smoke tests in
 * `KeeperActionTestSuite` exercise them through the mocked shell. Here we
 * just cover [KeeperFolderValidator.classify], which is the bit that
 * decides Valid vs Missing vs Mismatch from a parsed folder list.
 */
class KeeperFolderValidatorTest {

    private fun classicFolder(uid: String, name: String) =
        KeeperFolder(folderUid = uid, name = name, type = null, source = KeeperFolder.SOURCE_CLASSIC_FOLDER)

    private fun nestedShareFolder(uid: String, name: String) =
        KeeperFolder(folderUid = uid, name = name, type = null, source = KeeperFolder.SOURCE_NESTED_SHARE_FOLDER)

    /** Legacy Commander wire values — kept for backward compatibility in tests. */
    private fun legacyClassicFolder(uid: String, name: String) =
        KeeperFolder(folderUid = uid, name = name, type = null, source = "Legacy")

    private fun legacyNestedShareFolder(uid: String, name: String) =
        KeeperFolder(folderUid = uid, name = name, type = null, source = "KeeperDrive")

    @Test
    fun `classify returns Valid for classic UUID present in the classic slot`() {
        val folders = listOf(
            classicFolder("classic-1", "Demo Folder"),
            nestedShareFolder("drive-1", "Nested Shared Folder")
        )

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "classic-1",
            expected = KeeperFolderValidator.ExpectedKind.CLASSIC
        )

        assertTrue("Should be Valid", verdict is KeeperFolderValidator.Verdict.Valid)
        verdict as KeeperFolderValidator.Verdict.Valid
        assertEquals("classic-1", verdict.uuid)
        assertEquals("Demo Folder", verdict.name)
        assertEquals(false, verdict.isDrive)
    }

    @Test
    fun `classify returns Valid for Nested Shared Folder UUID present in the drive slot`() {
        val folders = listOf(
            classicFolder("classic-1", "Demo Folder"),
            nestedShareFolder("drive-1", "Nested Shared Folder")
        )

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "drive-1",
            expected = KeeperFolderValidator.ExpectedKind.DRIVE
        )

        assertTrue("Should be Valid", verdict is KeeperFolderValidator.Verdict.Valid)
        verdict as KeeperFolderValidator.Verdict.Valid
        assertEquals("drive-1", verdict.uuid)
        assertEquals("Nested Shared Folder", verdict.name)
        assertEquals(true, verdict.isDrive)
    }

    @Test
    fun `classify returns Missing when the UUID isn't in the listing`() {
        val folders = listOf(classicFolder("classic-1", "Demo Folder"))

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "ghost-uuid",
            expected = KeeperFolderValidator.ExpectedKind.CLASSIC
        )

        assertTrue("Should be Missing", verdict is KeeperFolderValidator.Verdict.Missing)
    }

    @Test
    fun `classify returns Mismatch when a classic UUID resolves to a Nested Shared Folder`() {
        // Saved as Classic but the folder it points at is actually a Nested Shared Folder.
        val folders = listOf(nestedShareFolder("drive-1", "Promoted Folder"))

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "drive-1",
            expected = KeeperFolderValidator.ExpectedKind.CLASSIC
        )

        assertTrue("Should be Mismatch", verdict is KeeperFolderValidator.Verdict.Mismatch)
        verdict as KeeperFolderValidator.Verdict.Mismatch
        assertEquals(KeeperFolderValidator.ExpectedKind.DRIVE, verdict.actualKind)
        assertEquals("Promoted Folder", verdict.name)
    }

    @Test
    fun `classify returns Mismatch when a Nested Shared Folder UUID resolves to a Classic folder`() {
        val folders = listOf(classicFolder("classic-1", "Old Folder"))

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "classic-1",
            expected = KeeperFolderValidator.ExpectedKind.DRIVE
        )

        assertTrue("Should be Mismatch", verdict is KeeperFolderValidator.Verdict.Mismatch)
        verdict as KeeperFolderValidator.Verdict.Mismatch
        assertEquals(KeeperFolderValidator.ExpectedKind.CLASSIC, verdict.actualKind)
        assertEquals("Old Folder", verdict.name)
    }

    @Test
    fun `classify recognizes nested_share_folder UUID from current Commander ls output`() {
        val folders = listOf(
            classicFolder("y2cG8GbSF3k_TT0D-EOH1w", "kcm-creds"),
            nestedShareFolder("2zHC3Umb41PnVECFZxYTXw", "Keeper Drive Test folder"),
        )

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "2zHC3Umb41PnVECFZxYTXw",
            expected = KeeperFolderValidator.ExpectedKind.DRIVE,
        )

        assertTrue(verdict is KeeperFolderValidator.Verdict.Valid)
        verdict as KeeperFolderValidator.Verdict.Valid
        assertEquals(true, verdict.isDrive)
    }

    @Test
    fun `classify treats classic_folder UUID as Classic not Nested Shared Folder`() {
        val folders = listOf(
            classicFolder("y2cG8GbSF3k_TT0D-EOH1w", "kcm-creds"),
            nestedShareFolder("2zHC3Umb41PnVECFZxYTXw", "Keeper Drive Test folder"),
        )

        val verdict = KeeperFolderValidator.classify(
            folders = folders,
            uuid = "y2cG8GbSF3k_TT0D-EOH1w",
            expected = KeeperFolderValidator.ExpectedKind.CLASSIC,
        )

        assertTrue(verdict is KeeperFolderValidator.Verdict.Valid)
        verdict as KeeperFolderValidator.Verdict.Valid
        assertEquals(false, verdict.isDrive)
    }

    @Test
    fun `classify on empty list is always Missing`() {
        val verdict = KeeperFolderValidator.classify(
            folders = emptyList(),
            uuid = "any",
            expected = KeeperFolderValidator.ExpectedKind.CLASSIC
        )

        assertTrue("Should be Missing", verdict is KeeperFolderValidator.Verdict.Missing)
    }
}
