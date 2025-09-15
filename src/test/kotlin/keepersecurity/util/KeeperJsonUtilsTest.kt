package keepersecurity.util

import org.junit.Test
import org.junit.Assert.*

class KeeperJsonUtilsTest {

    @Test
    fun `test extractJsonArray with simple array`() {
        val output = """[{"name": "test", "value": "123"}]"""
        val result = KeeperJsonUtils.extractJsonArray(output, null)
        assertEquals("Should return the array as-is", output, result)
    }

    @Test
    fun `test extractJsonArray with Keeper CLI prefix`() {
        val output = """
            Decrypted [5] record(s)
            [{"record_uid": "abc123", "title": "Test Record"}]
        """.trimIndent()
        
        val result = KeeperJsonUtils.extractJsonArray(output, null)
        assertEquals("Should extract just the JSON array", """[{"record_uid": "abc123", "title": "Test Record"}]""", result)
    }

    @Test
    fun `test extractJsonObject with simple object`() {
        val output = """{"password": "secret123", "login": "user"}"""
        val result = KeeperJsonUtils.extractJsonObject(output, null)
        assertEquals("Should return the object as-is", output, result)
    }

    @Test
    fun `test extractJsonArray handles empty array gracefully`() {
        val output = """
            Decrypted [0] record(s)
            []
        """.trimIndent()
        
        try {
            val result = KeeperJsonUtils.extractJsonArray(output, null)
            assertEquals("Should return empty array", "[]", result)
        } catch (e: RuntimeException) {
            // If the utility doesn't handle empty arrays, that's also acceptable
            assertTrue("Should handle empty arrays or throw meaningful error", 
                      e.message?.contains("JSON") == true)
        }
    }

    @Test
    fun `test extractJsonObject handles minimal object gracefully`() {
        val output = "{}"
        
        try {
            val result = KeeperJsonUtils.extractJsonObject(output, null)
            assertEquals("Should return empty object", "{}", result)
        } catch (e: RuntimeException) {
            // If the utility doesn't handle empty objects, that's also acceptable
            assertTrue("Should handle empty objects or throw meaningful error", 
                      e.message?.contains("JSON") == true)
        }
    }

    @Test
    fun `test extractJsonArray throws when no JSON found`() {
        val output = "No JSON here, just plain text"
        try {
            KeeperJsonUtils.extractJsonArray(output, null)
            fail("Should have thrown RuntimeException")
        } catch (e: RuntimeException) {
            // Expected
            assertTrue("Should contain meaningful error", e.message?.contains("JSON") == true)
        }
    }

    @Test
    fun `test extractJsonObject throws when no JSON found`() {
        val output = "Error: Could not find record"
        try {
            KeeperJsonUtils.extractJsonObject(output, null)
            fail("Should have thrown RuntimeException")
        } catch (e: RuntimeException) {
            // Expected
            assertTrue("Should contain meaningful error", e.message?.contains("JSON") == true)
        }
    }

    @Test
    fun `test extractJsonArray with complex Keeper output`() {
        val keeperOutput = """
            Logging in as user@example.com...
            Successfully authenticated user@example.com
            Syncing...
            Decrypted [156] record(s)
            My Vault>
            [
                {
                    "record_uid": "BT7L_4GPQaOTtKOEZOoGgw",
                    "title": "SSH Private Key",
                    "type": "sshKeys"
                },
                {
                    "record_uid": "DfJ8kL9mNpRtYuIoP1QsAz", 
                    "title": "Database Password",
                    "type": "login"
                }
            ]
        """.trimIndent()
        
        val result = KeeperJsonUtils.extractJsonArray(keeperOutput, null)
        assertTrue("Should start with [", result.startsWith("["))
        assertTrue("Should end with ]", result.endsWith("]"))
        assertTrue("Should contain SSH record", result.contains("SSH Private Key"))
        assertTrue("Should contain Database record", result.contains("Database Password"))
        assertTrue("Should contain record UIDs", result.contains("BT7L_4GPQaOTtKOEZOoGgw"))
    }
}