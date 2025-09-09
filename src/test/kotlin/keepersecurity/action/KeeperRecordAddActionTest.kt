package keepersecurity.action

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperRecordAddAction
 */
class KeeperRecordAddActionTest {

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
        val action = KeeperRecordAddAction()
        assertNotNull("Action should be created successfully", action)
        assertEquals("Action text should be correct", "Add Keeper Record", action.templatePresentation.text)
    }

    @Test
    fun `test standard fields contain expected values`() {
        val action = KeeperRecordAddAction()
        
        // Use reflection to access private field for testing
        val field = action.javaClass.getDeclaredField("keeperStandardFields")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val standardFields = field.get(action) as Set<String>
            
        // Test that important standard fields are included
        assertTrue("Should contain login field", standardFields.contains("login"))
        assertTrue("Should contain password field", standardFields.contains("password"))
        assertTrue("Should contain url field", standardFields.contains("url"))
        assertTrue("Should contain email field", standardFields.contains("email"))
        assertTrue("Should contain note field", standardFields.contains("note"))
        
        // Test field count is reasonable
        assertTrue("Should have reasonable number of standard fields", standardFields.size > 20)
    }

    @Test
    fun `test action presentation properties`() {
        val action = KeeperRecordAddAction()
        val presentation = action.templatePresentation
        
        assertNotNull("Presentation should not be null", presentation)
        assertEquals("Text should be 'Add Keeper Record'", "Add Keeper Record", presentation.text)
    }

    @Test
    fun `test multiple action instances are independent`() {
        val action1 = KeeperRecordAddAction()
        val action2 = KeeperRecordAddAction()
        
        assertNotNull("First action should be created", action1)
        assertNotNull("Second action should be created", action2)
        assertNotSame("Actions should be different instances", action1, action2)
        
        // Both should have the same standard fields
        val field1 = action1.javaClass.getDeclaredField("keeperStandardFields")
        field1.isAccessible = true
        val field2 = action2.javaClass.getDeclaredField("keeperStandardFields")
        field2.isAccessible = true
        
        @Suppress("UNCHECKED_CAST")
        val fields1 = field1.get(action1) as Set<String>
        @Suppress("UNCHECKED_CAST")
        val fields2 = field2.get(action2) as Set<String>
        
        assertEquals("Both instances should have same standard fields", fields1, fields2)
    }
}