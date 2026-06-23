package keepersecurity.action

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Simple unit tests for KeeperGenerateSecretsAction
 */
class KeeperGenerateSecretsActionTest {

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
        val action = KeeperGenerateSecretsAction()
        assertNotNull("Action should be created successfully", action)
        // Fix: The actual text is "Keeper Generate Secrets"
        assertEquals("Action text should be correct", "Keeper Generate Secrets", action.templatePresentation.text)
    }

    @Test
    fun `test action presentation properties`() {
        val action = KeeperGenerateSecretsAction()
        val presentation = action.templatePresentation
        
        assertNotNull("Presentation should not be null", presentation)
        assertNotNull("Text should not be null", presentation.text)
        assertTrue("Text should contain Generate", presentation.text.contains("Generate"))
        assertTrue("Text should contain Secrets", presentation.text.contains("Secrets"))
    }

    @Test
    fun `test multiple action instances are independent`() {
        val action1 = KeeperGenerateSecretsAction()
        val action2 = KeeperGenerateSecretsAction()
        
        assertNotNull("First action should be created", action1)
        assertNotNull("Second action should be created", action2)
        assertNotSame("Actions should be different instances", action1, action2)
        
        // Both should have the same presentation text
        assertEquals("Both should have same text", 
            action1.templatePresentation.text, 
            action2.templatePresentation.text)
    }

    @Test
    fun `test action exposes Classic and Drive GenerateTarget branches`() {
        val classicTarget = Class.forName(
            "keepersecurity.action.KeeperGenerateSecretsAction\$GenerateTarget\$Classic"
        )
        val driveTarget = Class.forName(
            "keepersecurity.action.KeeperGenerateSecretsAction\$GenerateTarget\$Drive"
        )
        assertNotNull("Classic GenerateTarget branch should exist", classicTarget)
        assertNotNull("Drive GenerateTarget branch should exist", driveTarget)
    }

    @Test
    fun `test action declares Drive and Classic record-add command builders`() {
        val methods = KeeperGenerateSecretsAction::class.java.declaredMethods.map { it.name }
        assertTrue(
            "Classic command builder should be declared",
            methods.any { it == "buildClassicAddCommand" }
        )
        assertTrue(
            "Drive command builder should be declared",
            methods.any { it == "buildDriveAddCommand" }
        )
    }
}
