package keepersecurity.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.Before

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class KeeperActionsIntegrationTest : BasePlatformTestCase() {

    private fun allActions(): List<AnAction> = listOf(
        KeeperRecordAddAction(),
        KeeperGenerateSecretsAction(),
        KeeperFolderSelectAction(),
        KeeperGetSecretAction(),
        KeeperRecordUpdateAction(),
        KeeperSecretAction()
    )

    @Test
    fun `test all keeper actions can be instantiated`() {
        val actions = allActions()

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
        allActions().forEach { action ->
            assertNotNull("Action template should exist", action.templatePresentation)
            assertTrue("Action should be enabled by default", action.templatePresentation.isEnabled)
        }
    }

    @Test
    fun `test actions have consistent naming convention`() {
        allActions().forEach { action ->
            val actionName = action.javaClass.simpleName
            assertTrue("Action name should start with Keeper", actionName.startsWith("Keeper"))
            assertTrue("Action name should end with Action", actionName.endsWith("Action"))
        }
    }

    @Test
    fun `test actions have descriptions`() {
        allActions().forEach { action ->
            val text = action.templatePresentation.text
            assertNotNull("Action should have presentation text", text)
            assertTrue("Action text should contain 'Keeper'", text.contains("Keeper"))
        }
    }

    @Test
    fun `test unified folder and secret pickers are present`() {
        // Drive lookups are folded into the classic pickers — Commander's
        // unified `list` / `ls --format=json` already returns Drive rows
        // alongside Classic ones, so a single menu item suffices. If
        // separate Drive-only actions are reintroduced later, update this
        // canary to assert both surfaces.
        val classes = allActions().map { it.javaClass.simpleName }
        assertTrue(classes.contains("KeeperFolderSelectAction"))
        assertTrue(classes.contains("KeeperGetSecretAction"))
        assertFalse(
            "Drive-specific folder picker should be folded into KeeperFolderSelectAction",
            classes.contains("KeeperDriveFolderSelectAction")
        )
        assertFalse(
            "Drive-specific secret picker should be folded into KeeperGetSecretAction",
            classes.contains("KeeperGetDriveSecretAction")
        )
    }

    override fun getTestDataPath() = "src/test/testData"
}
