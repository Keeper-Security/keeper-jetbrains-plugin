package keepersecurity.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeperCliSafetyTest {

    @Test fun `requireSafe accepts a plain single-line value`() {
        assertEquals("hello", KeeperCliSafety.requireSafe("hello", "title"))
    }

    @Test fun `requireSafe accepts spaces, tabs, and quotes`() {
        val v = "a value with \"quotes\" and a\ttab"
        assertEquals(v, KeeperCliSafety.requireSafe(v, "selected text"))
    }

    @Test fun `requireSafe rejects an embedded LF`() {
        val ex = assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.requireSafe("foo\nthis-device rename PWNED", "selected text")
        }
        assertTrue(ex.message!!.contains("selected text"))
    }

    @Test fun `requireSafe rejects an embedded CR`() {
        assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.requireSafe("foo\rbad", "title")
        }
    }

    @Test fun `requireSafe rejects an embedded NUL`() {
        assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.requireSafe("foo\u0000bad", "field name")
        }
    }

    @Test fun `assertSingleLine accepts a single-line command`() {
        KeeperCliSafety.assertSingleLine("record-add --title=\"x\" password=\"y\"")
    }

    @Test fun `assertSingleLine rejects an embedded LF in the assembled command`() {
        assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.assertSingleLine("record-add\nthis-device rename PWNED")
        }
    }

    @Test fun `isValidRecordUid accepts a 22-char URL-safe Base64 uid`() {
        assertTrue(KeeperCliSafety.isValidRecordUid("abc123def456GHI789jkLM"))
    }

    @Test fun `isValidRecordUid rejects placeholder and short uids`() {
        assertFalse(KeeperCliSafety.isValidRecordUid("REPLACE_WITH_REAL_UID"))
        assertFalse(KeeperCliSafety.isValidRecordUid("abc"))
    }

    @Test fun `isValidRecordUid rejects too-long and invalid-character uids`() {
        assertFalse(KeeperCliSafety.isValidRecordUid("abc123def456GHI789jkLMx"))
        assertFalse(KeeperCliSafety.isValidRecordUid("abc123def456GHI789jk!M"))
    }

    @Test fun `KEEPER_RECORD_UID regex matches only full 22-char uids`() {
        assertTrue(KeeperCliSafety.KEEPER_RECORD_UID.matches("2zHC3Umb41PnVECFZxYTXw"))
        assertFalse(KeeperCliSafety.KEEPER_RECORD_UID.matches("prefix2zHC3Umb41PnVECFZxYTXw"))
    }

    @Test fun `escapeDoubleQuoted escapes backslashes and double quotes`() {
        assertEquals("foo\\\"bar", KeeperCliSafety.escapeDoubleQuoted("foo\"bar"))
        assertEquals("back\\\\slash", KeeperCliSafety.escapeDoubleQuoted("back\\slash"))
    }

    @Test fun `escapeSingleQuoted escapes embedded single quotes`() {
        assertEquals("it'\\''s", KeeperCliSafety.escapeSingleQuoted("it's"))
    }

    // --- VM-1450: crafted editor selection / CLI injection regression shapes ---

    @Test fun `requireSafe rejects Step-A shaped multi-line selection with quote break`() {
        // Report Step A: close the field quote, inject a second Commander command, reopen.
        val payload = "benign-value\"\nthis-device rename INJECTED\n\"end"
        assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.requireSafe(payload, "selected text")
        }
    }

    @Test fun `requireSafe rejects Step-B shaped multi-line selection with pam tunnel run`() {
        val payload =
            "benign-value\"\npam tunnel start abc123def456GHI789jkLM --run \"echo pwned\"\n\"end"
        assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.requireSafe(payload, "selected text")
        }
    }

    @Test fun `assertSingleLine rejects assembled record-add with injected second command`() {
        val command =
            "record-add --title=\"t\" --record-type=login password=\"benign-value\"\nthis-device rename INJECTED"
        assertThrows(KeeperCliSafety.UnsafeCliInputException::class.java) {
            KeeperCliSafety.assertSingleLine(command)
        }
    }

    @Test fun `escapeDoubleQuoted prevents quote-break of field value`() {
        val selection = "benign-value\""
        val esc = KeeperCliSafety.escapeDoubleQuoted(selection)
        assertEquals("benign-value\\\"", esc)
        // After escaping, embedding inside double quotes must not prematurely close.
        val field = "password=\"$esc\""
        assertEquals("password=\"benign-value\\\"\"", field)
        assertFalse(field.contains("password=\"benign-value\"\""))
    }

    @Test fun `isValidRecordUid rejects quote and metachar injection shapes`() {
        assertFalse(KeeperCliSafety.isValidRecordUid("abc123def456GHI789jk\"M"))
        assertFalse(KeeperCliSafety.isValidRecordUid("uid\" --folder=evil"))
        assertFalse(KeeperCliSafety.isValidRecordUid("aaaaaaaaaaaaaaaaaaaaa\n"))
    }
}
