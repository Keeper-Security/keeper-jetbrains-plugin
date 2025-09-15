package keepersecurity.action

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperAuthAction that don't require IntelliJ Platform test framework
 */
class KeeperAuthActionTest {

    @Before
    fun setUp() {
        // Enable test mode to prevent biometric prompts
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
        val action = KeeperAuthAction()
        assertNotNull("Action should be created successfully", action)
        assertEquals("Action text should be correct", "Check Keeper Authorization", action.templatePresentation.text)
    }

    @Test
    fun `test action template presentation has correct description`() {
        val action = KeeperAuthAction()
        val presentation = action.templatePresentation
        
        assertNotNull("Presentation should not be null", presentation)
        assertNotNull("Text should not be null", presentation.text)
        assertTrue("Text should contain Authorization", presentation.text.contains("Authorization"))
    }

    @Test
    fun `test action can be instantiated multiple times`() {
        val action1 = KeeperAuthAction()
        val action2 = KeeperAuthAction()
        
        assertNotNull("First action should be created", action1)
        assertNotNull("Second action should be created", action2)
        assertNotSame("Actions should be different instances", action1, action2)
    }
}
