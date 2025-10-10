package keepersecurity.action

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperSecretAction
 */
class KeeperSecretActionTest {

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
    fun `test action creation`() {
        val action = KeeperSecretAction()
        assertNotNull("Action should be created successfully", action)
        assertEquals("Action text should be correct", "Run Keeper Securely", action.templatePresentation.text)
    }

    @Test
    fun `test action presentation properties`() {
        val action = KeeperSecretAction()
        val presentation = action.templatePresentation
        
        assertNotNull("Presentation should not be null", presentation)
        assertNotNull("Text should not be null", presentation.text)
        assertTrue("Text should contain Keeper", presentation.text.contains("Keeper"))
        assertTrue("Text should contain Securely", presentation.text.contains("Securely"))
    }

    @Test
    fun `test multiple action instances are independent`() {
        val action1 = KeeperSecretAction()
        val action2 = KeeperSecretAction()
        
        assertNotNull("First action should be created", action1)
        assertNotNull("Second action should be created", action2)
        assertNotSame("Actions should be different instances", action1, action2)
        
        // Both should have the same presentation text
        assertEquals("Both should have same text", 
            action1.templatePresentation.text, 
            action2.templatePresentation.text)
    }

    @Test
    fun `test action can be created without throwing exceptions`() {
        try {
            val action = KeeperSecretAction()
            assertNotNull("Action should be created without exceptions", action)
            
            // Test that logger is properly initialized
            val loggerField = action.javaClass.getDeclaredField("logger")
            loggerField.isAccessible = true
            val logger = loggerField.get(action)
            assertNotNull("Logger should be initialized", logger)
            
        } catch (e: Exception) {
            fail("Action creation should not throw exceptions: ${e.message}")
        }
    }

    @Test
    fun `test action has required action properties`() {
        val action = KeeperSecretAction()
        
        // Verify the action is properly set up
        assertNotNull("Template presentation should exist", action.templatePresentation)
        
        // Check that it extends AnAction properly
        assertTrue("Should extend AnAction", action is com.intellij.openapi.actionSystem.AnAction)
    }
}
