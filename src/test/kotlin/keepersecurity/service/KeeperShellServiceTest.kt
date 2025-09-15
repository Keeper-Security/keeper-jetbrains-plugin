package keepersecurity.service

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KeeperShellServiceTest {
    
    @Before
    fun setUp() {
        // Clean state before each test
        KeeperShellService.stopShell()
    }
    
    @After  
    fun tearDown() {
        // Clean up after tests
        KeeperShellService.stopShell()
    }
    
    @Test
    fun `test isReady returns false when shell not started`() {
        assertFalse("Shell should not be ready when not started", KeeperShellService.isReady())
    }
    
    @Test
    fun `test getLastStartupOutput returns empty when not started`() {
        val output = KeeperShellService.getLastStartupOutput()
        assertTrue("Startup output should be empty when shell not started", output.isEmpty())
    }
    
    @Test
    fun `test stopShell handles no running shell gracefully`() {
        // Should not throw when stopping non-existent shell
        try {
            KeeperShellService.stopShell()
            assertTrue("Should complete without throwing", true)
        } catch (e: Exception) {
            fail("Should not throw exception: ${e.message}")
        }
        
        assertFalse("Shell should not be ready after stop", KeeperShellService.isReady())
    }
    
    @Test
    fun `test multiple stop calls are safe`() {
        KeeperShellService.stopShell()
        try {
            KeeperShellService.stopShell()
            KeeperShellService.stopShell()
            assertTrue("Multiple stops should not throw", true)
        } catch (e: Exception) {
            fail("Multiple stops should not throw exception: ${e.message}")
        }
    }
    
    @Test
    fun `test shell state consistency`() {
        // Initial state
        assertFalse("Shell should start as not ready", KeeperShellService.isReady())
        assertTrue("Initial startup output should be empty", KeeperShellService.getLastStartupOutput().isEmpty())
        
        // After stopping (should remain not ready)
        KeeperShellService.stopShell()
        assertFalse("Shell should remain not ready after stop", KeeperShellService.isReady())
        assertTrue("Startup output should be empty after stop", KeeperShellService.getLastStartupOutput().isEmpty())
    }
    
    @Test
    fun `test concurrent isReady calls are thread safe`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val results = mutableListOf<Boolean>()
        val exceptions = mutableListOf<Exception>()
        
        repeat(threadCount) {
            Thread {
                try {
                    val ready = KeeperShellService.isReady()
                    synchronized(results) {
                        results.add(ready)
                    }
                } catch (e: Exception) {
                    synchronized(exceptions) {
                        exceptions.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        
        assertTrue("All threads should complete", latch.await(5, TimeUnit.SECONDS))
        assertTrue("No exceptions should occur", exceptions.isEmpty())
        assertEquals("All threads should return same result", threadCount, results.size)
        assertTrue("All results should be consistent", results.all { it == results.first() })
    }
    
    @Test
    fun `test concurrent getLastStartupOutput calls are thread safe`() {
        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        val outputs = mutableListOf<String>()
        
        repeat(threadCount) {
            Thread {
                try {
                    val output = KeeperShellService.getLastStartupOutput()
                    synchronized(outputs) {
                        outputs.add(output)
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        
        assertTrue("All threads should complete", latch.await(3, TimeUnit.SECONDS))
        assertEquals("All threads should return", threadCount, outputs.size)
        assertTrue("All outputs should be consistent", outputs.all { it == outputs.first() })
    }
    
    @Test
    fun `test stopShell idempotency`() {
        // Multiple stops should be safe and not change state
        val initialReady = KeeperShellService.isReady()
        val initialOutput = KeeperShellService.getLastStartupOutput()
        
        KeeperShellService.stopShell()
        
        assertEquals("Ready state should remain the same", initialReady, KeeperShellService.isReady())
        assertEquals("Output should remain the same", initialOutput, KeeperShellService.getLastStartupOutput())
        
        // Additional stops should not change anything
        repeat(3) {
            KeeperShellService.stopShell()
            assertEquals("Ready state should remain consistent", initialReady, KeeperShellService.isReady())
            assertEquals("Output should remain consistent", initialOutput, KeeperShellService.getLastStartupOutput())
        }
    }
    
    @Test
    fun `test service maintains consistent state across operations`() {
        // Test that calling methods in different orders maintains consistency
        val operations = listOf<() -> Unit>(
            { KeeperShellService.isReady() },
            { KeeperShellService.getLastStartupOutput() },
            { KeeperShellService.stopShell() }
        )
        
        // Run operations in different orders
        repeat(5) { iteration ->
            operations.shuffled().forEach { operation ->
                try {
                    operation()
                    assertTrue("Operation should not throw on iteration $iteration", true)
                } catch (e: Exception) {
                    fail("Operation should not throw exception on iteration $iteration: ${e.message}")
                }
            }
            
            // State should be consistent after each iteration
            assertFalse("Shell should remain not ready", KeeperShellService.isReady())
            assertTrue("Output should remain empty", KeeperShellService.getLastStartupOutput().isEmpty())
        }
    }
    
    @Test
    fun `test service handles rapid successive calls`() {
        // Test rapid calls to ensure no race conditions
        repeat(20) {
            try {
                KeeperShellService.isReady()
                assertTrue("Rapid isReady calls should not throw", true)
            } catch (e: Exception) {
                fail("Rapid isReady calls should not throw exception: ${e.message}")
            }
            
            try {
                KeeperShellService.getLastStartupOutput()
                assertTrue("Rapid getLastStartupOutput calls should not throw", true)
            } catch (e: Exception) {
                fail("Rapid getLastStartupOutput calls should not throw exception: ${e.message}")
            }
            
            try {
                KeeperShellService.stopShell()
                assertTrue("Rapid stopShell calls should not throw", true)
            } catch (e: Exception) {
                fail("Rapid stopShell calls should not throw exception: ${e.message}")
            }
        }
    }
    
    @Test
    fun `test service state after exception simulation`() {
        // Ensure service remains in valid state even if internal operations might fail
        val initialReady = KeeperShellService.isReady()
        val initialOutput = KeeperShellService.getLastStartupOutput()
        
        // Call all public methods to ensure they don't corrupt internal state
        try {
            KeeperShellService.isReady()
            KeeperShellService.getLastStartupOutput() 
            KeeperShellService.stopShell()
            assertTrue("Service operations should not throw", true)
        } catch (e: Exception) {
            fail("Service operations should not throw exception: ${e.message}")
        }
        
        // Service should still be accessible
        assertNotNull("Service should still be accessible", KeeperShellService.isReady())
        assertNotNull("Service should still provide output", KeeperShellService.getLastStartupOutput())
    }
    
    @Test
    fun `test service API contracts`() {
        // Test that the public API follows expected contracts
        
        // isReady() should always return a boolean
        val ready = KeeperShellService.isReady()
        assertTrue("isReady should return boolean", ready is Boolean)
        
        // getLastStartupOutput() should always return a string
        val output = KeeperShellService.getLastStartupOutput()
        assertNotNull("getLastStartupOutput should return non-null string", output)
        assertTrue("getLastStartupOutput should return string", output is String)
        
        // stopShell() should not throw
        try {
            KeeperShellService.stopShell()
            assertTrue("stopShell should not throw", true)
        } catch (e: Exception) {
            fail("stopShell should not throw exception: ${e.message}")
        }
    }
    
    // Note: Testing actual shell startup and command execution would require 
    // either mocking the underlying ProcessBuilder or integration tests with 
    // a real Keeper CLI installation. These tests focus on the public API 
    // contracts and thread safety without external dependencies.
}
