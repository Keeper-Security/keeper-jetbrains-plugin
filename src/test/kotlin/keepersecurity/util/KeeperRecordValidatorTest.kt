package keepersecurity.util

import keepersecurity.model.KeeperRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-function side of [KeeperRecordValidator].
 *
 * The CLI-touching `lookupBlocking` / `lookupRecord` paths require a
 * live shell and aren't covered here — the action-level smoke tests in
 * `KeeperActionTestSuite` exercise them through the mocked shell. Here
 * we cover [KeeperRecordValidator.classify], which decides Found vs
 * NotFound from a parsed record list.
 */
class KeeperRecordValidatorTest {

    private fun classicRecord(uid: String, title: String) =
        KeeperRecord(recordUid = uid, title = title, recordCategory = KeeperRecord.CATEGORY_CLASSIC)

    private fun nestedShareRecord(uid: String, title: String) =
        KeeperRecord(recordUid = uid, title = title, recordCategory = KeeperRecord.CATEGORY_NESTED)

    /** Legacy Commander wire value — kept for backward compatibility in tests. */
    private fun legacyNestedShareRecord(uid: String, title: String) =
        KeeperRecord(recordUid = uid, title = title, recordCategory = "KeeperDrive")

    private fun legacyRecord(uid: String, title: String) =
        KeeperRecord(recordUid = uid, title = title, recordCategory = null)

    @Test
    fun `classify routes a classic record to Found with CLASSIC kind`() {
        val records = listOf(
            classicRecord("classic-1", "Demo Login"),
            nestedShareRecord("drive-1", "Nested Shared Login")
        )

        val verdict = KeeperRecordValidator.classify(records, "classic-1")
        assertTrue("Should be Found", verdict is KeeperRecordValidator.Verdict.Found)
        verdict as KeeperRecordValidator.Verdict.Found
        assertEquals(KeeperRecordValidator.Kind.CLASSIC, verdict.kind)
        assertEquals("classic-1", verdict.uuid)
        assertEquals("Demo Login", verdict.title)
    }

    @Test
    fun `classify routes a Nested Shared record to Found with DRIVE kind`() {
        val records = listOf(
            classicRecord("classic-1", "Demo Login"),
            nestedShareRecord("drive-1", "Nested Shared Login")
        )

        val verdict = KeeperRecordValidator.classify(records, "drive-1")
        assertTrue("Should be Found", verdict is KeeperRecordValidator.Verdict.Found)
        verdict as KeeperRecordValidator.Verdict.Found
        assertEquals(KeeperRecordValidator.Kind.DRIVE, verdict.kind)
        assertEquals("drive-1", verdict.uuid)
        assertEquals("Nested Shared Login", verdict.title)
    }

    @Test
    fun `classify defaults legacy records (no record_category) to CLASSIC`() {
        // Older Commander releases that pre-date the v3 API don't
        // populate `record_category`. Defaulting to Classic keeps the
        // Update flow working unchanged for those vaults; the cost of
        // a wrong guess is the same "record not found" the user would
        // have seen with the old explicit prompt, just one round-trip
        // later.
        val records = listOf(legacyRecord("legacy-1", "Old Login"))

        val verdict = KeeperRecordValidator.classify(records, "legacy-1")
        assertTrue("Should be Found", verdict is KeeperRecordValidator.Verdict.Found)
        verdict as KeeperRecordValidator.Verdict.Found
        assertEquals(KeeperRecordValidator.Kind.CLASSIC, verdict.kind)
    }

    @Test
    fun `classify returns NotFound when the UID isn't in the listing`() {
        val records = listOf(classicRecord("classic-1", "Demo Login"))

        val verdict = KeeperRecordValidator.classify(records, "ghost-uid")
        assertTrue("Should be NotFound", verdict is KeeperRecordValidator.Verdict.NotFound)
    }

    @Test
    fun `classify recognizes Nested record_category from current Commander list output`() {
        val records = listOf(
            classicRecord("JrdTfqy4sp6WFzZ4cov9TQ", "iTerm2ClassicRec"),
            nestedShareRecord("FnxkikNsz2W7OyqDB9fTAg", "iTerm2NSFRec"),
        )

        val verdict = KeeperRecordValidator.classify(records, "FnxkikNsz2W7OyqDB9fTAg")
        assertTrue(verdict is KeeperRecordValidator.Verdict.Found)
        verdict as KeeperRecordValidator.Verdict.Found
        assertEquals(KeeperRecordValidator.Kind.DRIVE, verdict.kind)
        assertEquals("iTerm2NSFRec", verdict.title)
    }

    @Test
    fun `classify treats Classic record_category as Classic vault`() {
        val records = listOf(
            classicRecord("JrdTfqy4sp6WFzZ4cov9TQ", "iTerm2ClassicRec"),
            nestedShareRecord("FnxkikNsz2W7OyqDB9fTAg", "iTerm2NSFRec"),
        )

        val verdict = KeeperRecordValidator.classify(records, "JrdTfqy4sp6WFzZ4cov9TQ")
        assertTrue(verdict is KeeperRecordValidator.Verdict.Found)
        verdict as KeeperRecordValidator.Verdict.Found
        assertEquals(KeeperRecordValidator.Kind.CLASSIC, verdict.kind)
    }

    @Test
    fun `classify on empty list is always NotFound`() {
        val verdict = KeeperRecordValidator.classify(emptyList(), "any")
        assertTrue("Should be NotFound", verdict is KeeperRecordValidator.Verdict.NotFound)
    }

    @Test
    fun `classify falls back to the UID when the record has a blank title`() {
        val records = listOf(classicRecord("classic-1", ""))

        val verdict = KeeperRecordValidator.classify(records, "classic-1")
        assertTrue("Should be Found", verdict is KeeperRecordValidator.Verdict.Found)
        verdict as KeeperRecordValidator.Verdict.Found
        assertEquals("classic-1", verdict.title)
    }
}
