package keepersecurity.action

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperRecordUpdateAction
 */
class KeeperRecordUpdateActionTest {

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
        val action = KeeperRecordUpdateAction()
        assertNotNull("Action should be created successfully", action)
        assertEquals("Action text should be correct", "Update Keeper Record", action.templatePresentation.text)
    }

    @Test
    fun `test standard fields contains expected fields`() {
        val action = KeeperRecordUpdateAction()
        val standardFields = getPrivateField(action, "keeperStandardFields") as Set<*>
        
        assertTrue("Should contain password field", standardFields.contains("password"))
        assertTrue("Should contain login field", standardFields.contains("login"))
        assertTrue("Should contain url field", standardFields.contains("url"))
        assertTrue("Should contain note field", standardFields.contains("note"))
        assertTrue("Should contain email field", standardFields.contains("email"))
        assertTrue("Should have reasonable number of fields", standardFields.size > 20)
    }

    @Test
    fun `test action presentation properties`() {
        val action = KeeperRecordUpdateAction()
        val presentation = action.templatePresentation
        
        assertNotNull("Presentation should not be null", presentation)
        assertEquals("Text should be 'Update Keeper Record'", "Update Keeper Record", presentation.text)
    }

    @Test
    fun `test multiple action instances have same standard fields`() {
        val action1 = KeeperRecordUpdateAction()
        val action2 = KeeperRecordUpdateAction()
        
        val fields1 = getPrivateField(action1, "keeperStandardFields") as Set<*>
        val fields2 = getPrivateField(action2, "keeperStandardFields") as Set<*>
        
        assertEquals("Both instances should have same standard fields", fields1, fields2)
        assertTrue("Standard fields should not be empty", fields1.isNotEmpty())
    }

    @Test
    fun `test action can be created without throwing exceptions`() {
        try {
            val action = KeeperRecordUpdateAction()
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

    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }
}
