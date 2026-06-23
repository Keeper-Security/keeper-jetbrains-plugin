package keepersecurity.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeperEnvSafetyTest {

    @Test fun `blockedEnvKeyReason rejects NODE_OPTIONS`() {
        assertTrue(KeeperEnvSafety.blockedEnvKeyReason("NODE_OPTIONS")!!.contains("blocked"))
    }

    @Test fun `blockedEnvKeyReason rejects case-insensitive exact keys`() {
        assertTrue(KeeperEnvSafety.blockedEnvKeyReason("node_options")!!.contains("blocked"))
    }

    @Test fun `blockedEnvKeyReason rejects DYLD_INSERT_LIBRARIES`() {
        assertTrue(KeeperEnvSafety.blockedEnvKeyReason("DYLD_INSERT_LIBRARIES")!!.contains("DYLD"))
    }

    @Test fun `blockedEnvKeyReason rejects suffix _OPTIONS`() {
        assertTrue(KeeperEnvSafety.blockedEnvKeyReason("FOO_OPTIONS")!!.contains("_OPTIONS"))
    }

    @Test fun `blockedEnvKeyReason rejects suffix _DEBUGGER`() {
        assertTrue(KeeperEnvSafety.blockedEnvKeyReason("MY_DEBUGGER")!!.contains("_DEBUGGER"))
    }

    @Test fun `blockedEnvKeyReason allows routine secret keys`() {
        assertNull(KeeperEnvSafety.blockedEnvKeyReason("DATABASE_PASSWORD"))
        assertNull(KeeperEnvSafety.blockedEnvKeyReason("API_TOKEN"))
        assertNull(KeeperEnvSafety.blockedEnvKeyReason("APP_NAME"))
    }

    @Test fun `blockedEnvValueReason rejects interpreter flag values`() {
        assertTrue(
            KeeperEnvSafety.blockedEnvValueReason("--require=./pwn.js")!!.contains("interpreter flag")
        )
    }

    @Test fun `blockedEnvValueReason rejects command substitution`() {
        assertTrue(KeeperEnvSafety.blockedEnvValueReason("$(whoami)")!!.contains("substitution"))
        assertTrue(KeeperEnvSafety.blockedEnvValueReason("`id`")!!.contains("substitution"))
    }

    @Test fun `blockedEnvValueReason rejects control characters`() {
        assertTrue(KeeperEnvSafety.blockedEnvValueReason("secret\nbad")!!.contains("control"))
        assertTrue(KeeperEnvSafety.blockedEnvValueReason("secret\rbad")!!.contains("control"))
        assertTrue(KeeperEnvSafety.blockedEnvValueReason("secret\u0000bad")!!.contains("control"))
    }

    @Test fun `blockedEnvValueReason allows normal secret values`() {
        assertNull(KeeperEnvSafety.blockedEnvValueReason("super-secret-password"))
        assertNull(KeeperEnvSafety.blockedEnvValueReason("sk-live-abc123"))
    }

    @Test fun `validateForInjection blocks PoC NODE_OPTIONS payload`() {
        val verdict = KeeperEnvSafety.validateForInjection("NODE_OPTIONS", "--require=./pwn.js")
        assertTrue(verdict is KeeperEnvSafety.Verdict.Blocked)
        assertEquals("NODE_OPTIONS", (verdict as KeeperEnvSafety.Verdict.Blocked).key)
    }

    @Test fun `validateForInjection allows normal DATABASE_URL injection`() {
        val verdict = KeeperEnvSafety.validateForInjection("DATABASE_URL", "postgres://localhost/db")
        assertTrue(verdict is KeeperEnvSafety.Verdict.Allowed)
        assertEquals(
            "postgres://localhost/db",
            (verdict as KeeperEnvSafety.Verdict.Allowed).value,
        )
    }
}
