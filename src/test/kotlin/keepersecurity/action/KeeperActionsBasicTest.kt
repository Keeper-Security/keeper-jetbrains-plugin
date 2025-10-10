package keepersecurity.action

import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*

/**
 * Basic tests for Keeper actions that don't require IntelliJ Platform test framework
 */
class KeeperActionsBasicTest {

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
    fun `test all actions can be instantiated`() {
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction(),
            KeeperAuthAction()
        )
        
        actions.forEach { action ->
            assertNotNull("Action should not be null", action)
            assertNotNull("Action text should not be null", action.templatePresentation.text)
            assertTrue("Action text should not be empty", action.templatePresentation.text.isNotEmpty())
        }
    }

    @Test
    fun `test action names are correct`() {
        val expectedNames = mapOf(
            KeeperRecordAddAction() to "Add Keeper Record",
            KeeperGenerateSecretsAction() to "Keeper Generate Secrets",
            KeeperFolderSelectAction() to "Get Keeper Folder",
            KeeperGetSecretAction() to "Get Keeper Secret",
            KeeperRecordUpdateAction() to "Update Keeper Record",
            KeeperSecretAction() to "Run Keeper Securely",
            KeeperAuthAction() to "Check Keeper Authorization"
        )
        
        expectedNames.forEach { (action, expectedText) ->
            assertEquals("Action text should match", expectedText, action.templatePresentation.text)
        }
    }

    @Test
    fun `test actions extend AnAction`() {
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction(),
            KeeperAuthAction()
        )
        
        actions.forEach { action ->
            assertTrue("Action should extend AnAction", action is com.intellij.openapi.actionSystem.AnAction)
        }
    }
}
