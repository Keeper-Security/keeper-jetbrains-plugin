package keepersecurity.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KeeperRecordOutputValidators]. Covers classic and Nested
 * Share Subfolders (`nsf-*`) success outputs plus the well-known failure modes
 * from Commander (sync banners, BreachWatch hints, nsf-record-add warning
 * aborts, unknown-command errors on older builds).
 */
class KeeperRecordOutputValidatorsTest {

    // --- isRecordAddSuccess -------------------------------------------------

    @Test
    fun `record-add succeeds with bare UID output`() {
        assertTrue(KeeperRecordOutputValidators.isRecordAddSuccess("abc123def456GHI789jkLM"))
    }

    @Test
    fun `record-add succeeds with Commander wrapping the UID in a sentence`() {
        val output = "Record UID: abc123def456GHI789jkLM"
        assertTrue(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `nsf-record-add succeeds with Commander v3 success line`() {
        val output = "Created Nested Shared record abc123def456GHI789jkLM"
        assertTrue(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `record-add rejects empty output`() {
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(""))
    }

    @Test
    fun `record-add rejects sync-banner output even when a UID-shaped token is present`() {
        val output = "Decrypted [12] record(s) abc123def456GHI789jkLM"
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `record-add rejects breachwatch hint`() {
        val output = "Use \"breachwatch list\" command to inspect recent activity"
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `record-add rejects explicit CLI error`() {
        val output = "Error: invalid record type 'login2'"
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `nsf-record-add rejects warning-aborted output (no -f)`() {
        val output =
            "Warning: attachment fields are not supported in nsf-record-add; rerun with -f to skip"
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `nsf-record-add rejects unknown-command error on older Commander`() {
        val output = "Unknown command: nsf-record-add"
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    @Test
    fun `record-add rejects output with no UID-shaped token`() {
        val output = "Operation completed"
        assertFalse(KeeperRecordOutputValidators.isRecordAddSuccess(output))
    }

    // --- isRecordUpdateSuccess ---------------------------------------------

    @Test
    fun `record-update accepts empty output as success`() {
        assertTrue(KeeperRecordOutputValidators.isRecordUpdateSuccess(""))
    }

    @Test
    fun `record-update accepts a short success line`() {
        assertTrue(KeeperRecordOutputValidators.isRecordUpdateSuccess("Updated 1 record"))
    }

    @Test
    fun `nsf-record-update accepts nsf-flavoured success line`() {
        assertTrue(
            KeeperRecordOutputValidators.isRecordUpdateSuccess(
                "nsf-record-update succeeded for rvwIBG_ban2VTH64OsnzLn"
            )
        )
    }

    @Test
    fun `record-update rejects sync banner`() {
        val output = "Decrypted [42] record(s)"
        assertFalse(KeeperRecordOutputValidators.isRecordUpdateSuccess(output))
    }

    @Test
    fun `record-update rejects CLI error string`() {
        val output = "Record not found: abc123"
        assertFalse(KeeperRecordOutputValidators.isRecordUpdateSuccess(output))
    }

    @Test
    fun `nsf-record-update rejects unknown-command failure on older Commander`() {
        val output = "Unknown command: nsf-record-update"
        assertFalse(KeeperRecordOutputValidators.isRecordUpdateSuccess(output))
    }

    @Test
    fun `record-update rejects warning prefix from nsf-record-add style abort`() {
        val output = "Warning: unsupported field 'attachments'"
        assertFalse(KeeperRecordOutputValidators.isRecordUpdateSuccess(output))
    }

    // --- helper detectors --------------------------------------------------

    @Test
    fun `isSyncBanner detects every known marker case-sensitively`() {
        assertTrue(KeeperRecordOutputValidators.isSyncBanner("Decrypted [3]"))
        assertTrue(KeeperRecordOutputValidators.isSyncBanner("synced record(s)"))
        assertTrue(KeeperRecordOutputValidators.isSyncBanner("Use \"breachwatch list\" command"))
        assertFalse(KeeperRecordOutputValidators.isSyncBanner("nothing to see here"))
    }

    @Test
    fun `looksLikeError ignores case`() {
        assertTrue(KeeperRecordOutputValidators.looksLikeError("ERROR: bad input"))
        assertTrue(KeeperRecordOutputValidators.looksLikeError("Failed to authenticate"))
        assertTrue(KeeperRecordOutputValidators.looksLikeError("Aborted by user"))
        assertFalse(KeeperRecordOutputValidators.looksLikeError("Record created"))
    }

    // --- extractRecordUid --------------------------------------------------

    @Test
    fun `extractRecordUid returns the first 22-char UID in the output`() {
        val uid = "abc123def456GHI789jkLM"
        assertEquals(uid, KeeperRecordOutputValidators.extractRecordUid("created uid=$uid"))
    }

    @Test
    fun `extractRecordUid returns null when no UID-shaped token is present`() {
        assertNull(KeeperRecordOutputValidators.extractRecordUid("short text"))
    }

    // --- isFatalError ------------------------------------------------------

    @Test
    fun `isFatalError flags Commander's catch-all unexpected error`() {
        val output = "An unexpected error occurred: No module named 'keepercommander.keeper_drive.folder_api'."
        assertTrue(KeeperRecordOutputValidators.isFatalError(output))
    }

    @Test
    fun `isFatalError flags Python ModuleNotFoundError tracebacks`() {
        val output = "ModuleNotFoundError: No module named 'keepercommander.keeper_drive.folder_api'"
        assertTrue(KeeperRecordOutputValidators.isFatalError(output))
    }

    @Test
    fun `isFatalError flags older Commander unknown-command output`() {
        assertTrue(KeeperRecordOutputValidators.isFatalError("Unknown command: nsf-record-add"))
    }

    @Test
    fun `isFatalError flags not-logged-in state`() {
        assertTrue(KeeperRecordOutputValidators.isFatalError("You are not logged in"))
    }

    @Test
    fun `isFatalError ignores generic transient errors that can recover on retry`() {
        // BreachWatch / sync banners are noisy but recoverable — they must
        // *not* short-circuit the retry loop.
        assertFalse(KeeperRecordOutputValidators.isFatalError("Decrypted [91] records"))
        assertFalse(KeeperRecordOutputValidators.isFatalError("Use \"breachwatch list\" command"))
    }

    @Test
    fun `isFatalError is case-insensitive`() {
        assertTrue(KeeperRecordOutputValidators.isFatalError("AN UNEXPECTED ERROR OCCURRED"))
        assertTrue(KeeperRecordOutputValidators.isFatalError("Not Logged In"))
    }

    // --- summariseError ----------------------------------------------------

    @Test
    fun `summariseError strips Commander prompts and debug trailer`() {
        val raw = "My Vault> An unexpected error occurred: boom. Type \"debug\" to toggle verbose error output"
        val summary = KeeperRecordOutputValidators.summariseError(raw)
        assertFalse("Should drop the My Vault prompt", summary.contains("My Vault>"))
        assertFalse("Should drop the debug trailer", summary.contains("toggle verbose"))
        assertTrue("Should keep the real error sentence", summary.contains("An unexpected error occurred"))
    }

    @Test
    fun `summariseError caps long outputs at a sensible length`() {
        val raw = "Error: " + "x".repeat(1000)
        val summary = KeeperRecordOutputValidators.summariseError(raw)
        assertTrue("Summary should be capped to 300 chars", summary.length <= 300)
        assertNotEquals(raw, summary)
    }
}
