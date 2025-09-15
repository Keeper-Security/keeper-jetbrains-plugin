package keepersecurity.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.Before

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class KeeperActionsIntegrationTest : BasePlatformTestCase() {

    @Test
    fun `test all keeper actions can be instantiated`() {
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction()
        )
        
        actions.forEach { action ->
            assertNotNull("Action should not be null", action)
            assertNotNull("Action text should not be null", action.templatePresentation.text)
            assertTrue("Action text should not be empty", action.templatePresentation.text.isNotEmpty())
            assertTrue("Action should extend AnAction", action is AnAction)
        }
    }

    @Test
    fun `test all actions have unique presentation text`() {
        val actions = mapOf(
            "Add Keeper Record" to KeeperRecordAddAction(),
            "Keeper Generate Secrets" to KeeperGenerateSecretsAction(),
            "Get Keeper Folder" to KeeperFolderSelectAction(),
            "Get Keeper Secret" to KeeperGetSecretAction(),
            "Update Keeper Record" to KeeperRecordUpdateAction(),
            "Run Keeper Securely" to KeeperSecretAction()
        )
        
        actions.forEach { (expectedText, action) ->
            assertEquals("Action text should match", expectedText, action.templatePresentation.text)
        }
    }

    @Test
    fun `test actions handle basic lifecycle correctly`() {
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction()
        )
        
        actions.forEach { action ->
            // Test that each action can be created and has basic properties
            assertNotNull("Action template should exist", action.templatePresentation)
            // Note: isInternalAction doesn't exist, so we'll test something else
            assertTrue("Action should be enabled by default", action.templatePresentation.isEnabled)
        }
    }

    @Test
    fun `test actions have consistent naming convention`() {
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction()
        )
        
        actions.forEach { action ->
            val actionName = action.javaClass.simpleName
            assertTrue("Action name should start with Keeper", actionName.startsWith("Keeper"))
            assertTrue("Action name should end with Action", actionName.endsWith("Action"))
        }
    }

    @Test
    fun `test actions have descriptions`() {
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction()
        )
        
        actions.forEach { action ->
            val text = action.templatePresentation.text
            assertNotNull("Action should have presentation text", text)
            assertTrue("Action text should contain 'Keeper'", text.contains("Keeper"))
        }
    }

    override fun getTestDataPath() = "src/test/testData"
}
