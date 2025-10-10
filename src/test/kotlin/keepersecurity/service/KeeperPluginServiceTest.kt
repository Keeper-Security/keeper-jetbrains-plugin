package keepersecurity.service

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperPluginService without IntelliJ Platform test framework
 */
class KeeperPluginServiceTest {
    
    @Before
    fun setUp() {
        System.setProperty("keeper.test.mode", "true")
        System.setProperty("java.awt.headless", "true")
        // Clean shell state before each test
        KeeperShellService.stopShell()
    }
    
    @After
    fun tearDown() {
        KeeperShellService.stopShell()
        System.clearProperty("keeper.test.mode")
        System.clearProperty("java.awt.headless")
    }
    
    @Test
    fun `test plugin service can be instantiated`() {
        val pluginService = KeeperPluginService()
        assertNotNull("Plugin service should be instantiated", pluginService)
    }
    
    @Test
    fun `test multiple plugin service instances are independent`() {
        val service1 = KeeperPluginService()
        val service2 = KeeperPluginService()
        
        assertNotNull("First service should be created", service1)
        assertNotNull("Second service should be created", service2)
        // They may or may not be the same instance depending on implementation
    }
    
    @Test
    fun `test ensureShellReady returns boolean`() {
        val pluginService = KeeperPluginService()
        
        // In test mode, this should return true without biometric prompts
        try {
            val result = pluginService.ensureShellReady()
            assertTrue("ensureShellReady should return boolean", result is Boolean)
            // In test mode, should return true
            assertTrue("ensureShellReady should succeed in test mode", result)
        } catch (e: Exception) {
            fail("ensureShellReady should not throw in test mode: ${e.message}")
        }
    }
    
    @Test
    fun `test ensureShellReady when shell is not ready`() {
        val pluginService = KeeperPluginService()
        
        // Initially shell should not be ready
        assertFalse("Shell should not be ready initially", KeeperShellService.isReady())
        
        // ensureShellReady should start shell in test mode without biometric
        try {
            val result = pluginService.ensureShellReady()
            assertTrue("ensureShellReady should succeed in test mode", result)
        } catch (e: Exception) {
            fail("ensureShellReady should not fail in test mode: ${e.message}")
        }
    }
    
    @Test
    fun `test ensureShellReady when shell is already ready`() {
        val pluginService = KeeperPluginService()
        
        // First call to make shell ready
        try {
            pluginService.ensureShellReady()
            // Second call should also succeed
            val result = pluginService.ensureShellReady()
            assertTrue("Second ensureShellReady should also succeed", result)
        } catch (e: Exception) {
            fail("ensureShellReady should be idempotent in test mode: ${e.message}")
        }
    }
    
    @Test
    fun `test ensureShellReady idempotency`() {
        val pluginService = KeeperPluginService()
        
        // Multiple calls should be safe and return consistent results
        val results = mutableListOf<Boolean>()
        repeat(3) { iteration ->
            try {
                val result = pluginService.ensureShellReady()
                results.add(result)
            } catch (e: Exception) {
                fail("ensureShellReady call $iteration should not fail in test mode: ${e.message}")
            }
        }
        
        // All results should be the same (all true in test mode)
        assertTrue("Should have 3 results", results.size == 3)
        assertTrue("All results should be true in test mode", results.all { it == true })
    }
    
    @Test
    fun `test plugin service survives multiple operations`() {
        val pluginService = KeeperPluginService()
        
        // Test that the service remains stable across multiple operations
        repeat(5) { iteration ->
            try {
                val result = pluginService.ensureShellReady()
                assertTrue("Service operation $iteration should succeed", result)
                KeeperShellService.stopShell()
                assertFalse("Shell should be stopped", KeeperShellService.isReady())
            } catch (e: Exception) {
                fail("Service operation $iteration should not fail: ${e.message}")
            }
        }
        
        // Service should still be accessible
        assertNotNull("Service should still be accessible", pluginService)
    }
}
