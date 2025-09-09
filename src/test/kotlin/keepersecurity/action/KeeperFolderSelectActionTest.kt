package keepersecurity.action

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperFolderSelectAction
 */
class KeeperFolderSelectActionTest {

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
        val action = KeeperFolderSelectAction()
        assertNotNull("Action should be created successfully", action)
        assertEquals("Action text should be correct", "Get Keeper Folder", action.templatePresentation.text)
    }

    @Test
    fun `test action presentation properties`() {
        val action = KeeperFolderSelectAction()
        val presentation = action.templatePresentation
        
        assertNotNull("Presentation should not be null", presentation)
        assertNotNull("Text should not be null", presentation.text)
        assertTrue("Text should contain Folder", presentation.text.contains("Folder"))
        assertTrue("Text should contain Keeper", presentation.text.contains("Keeper"))
    }

    @Test
    fun `test multiple action instances are independent`() {
        val action1 = KeeperFolderSelectAction()
        val action2 = KeeperFolderSelectAction()
        
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
            val action = KeeperFolderSelectAction()
            assertNotNull("Action should be created without exceptions", action)
            assertTrue("Should complete action creation", true)
        } catch (e: Exception) {
            fail("Action creation should not throw exceptions: ${e.message}")
        }
    }
}
