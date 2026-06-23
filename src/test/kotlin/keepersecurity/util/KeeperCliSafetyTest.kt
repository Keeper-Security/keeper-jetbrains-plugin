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
}
