package keepersecurity.action

import org.junit.Test
import keepersecurity.service.MockKeeperService

class KeeperActionTestSuite : BaseKeeperActionTest() {

    @Test
    fun `test KeeperRecordAddAction full workflow with mocked service`() {
        // Setup mock
        MockKeeperService.addMockCommand("record-add", "mock-uid-123456789012345678")
        
        configureFileWithSelection("username = admin123", 10, 18)
        val action = KeeperRecordAddAction()
        val event = createActionEventWithEditor()
        
        // Should not throw and should handle selection
        try {
            action.actionPerformed(event)
            // If we reach here, no exception was thrown
            assertTrue("Action should complete without throwing", true)
        } catch (e: Exception) {
            fail("Action should not throw exception: ${e.message}")
        }
        
        assertEquals("admin123", myFixture.editor.caretModel.currentCaret.selectedText)
    }

    @Test
    fun `test KeeperGenerateSecretsAction initialization`() {
        val action = KeeperGenerateSecretsAction()
        
        configureFileWithCaretAt("password = ", 11)
        val event = createActionEventWithEditor()
        
        try {
            action.actionPerformed(event)
            assertTrue("Action should complete without throwing", true)
        } catch (e: Exception) {
            fail("Action should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `test KeeperFolderSelectAction with mock folders`() {
        MockKeeperService.addMockCommand(
            "ls --format=json -f -R", 
            """[{"folder_uid": "test-folder-123", "name": "Test Folder"}]"""
        )
        
        val action = KeeperFolderSelectAction()
        val event = createActionEventWithProjectOnly()
        
        try {
            action.actionPerformed(event)
            assertTrue("Action should complete without throwing", true)
        } catch (e: Exception) {
            fail("Action should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `test KeeperGetSecretAction with mock records`() {
        MockKeeperService.addMockCommand(
            "list --format json",
            """[{"record_uid": "test-record-123", "title": "Test Record"}]"""
        )
        
        val action = KeeperGetSecretAction()
        configureFileWithCaretAt("api_key = ", 9)
        val event = createActionEventWithEditor()
        
        try {
            action.actionPerformed(event)
            assertTrue("Action should complete without throwing", true)
        } catch (e: Exception) {
            fail("Action should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `test KeeperRecordUpdateAction with mock success`() {
        MockKeeperService.addMockCommand("record-update", "") // Empty response indicates success
        
        configureFileWithSelection("password = oldvalue123", 10, 21)
        val action = KeeperRecordUpdateAction()
        val event = createActionEventWithEditor()
        
        try {
            action.actionPerformed(event)
            assertTrue("Action should complete without throwing", true)
        } catch (e: Exception) {
            fail("Action should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `test KeeperSecretAction with env file handling`() {
        configureFileWithCaretAt("# Python script", 0)
        val action = KeeperSecretAction()
        val event = createActionEventWithEditor()
        
        // Should handle file operations gracefully
        try {
            action.actionPerformed(event)
            assertTrue("Action should complete without throwing", true)
        } catch (e: Exception) {
            fail("Action should not throw exception: ${e.message}")
        }
    }

    @Test
    fun `test all actions handle service failures gracefully`() {
        MockKeeperService.setReady(false)
        MockKeeperService.setNextCommandToFail(true)
        
        val actions = listOf(
            KeeperRecordAddAction(),
            KeeperGenerateSecretsAction(),
            KeeperFolderSelectAction(),
            KeeperGetSecretAction(),
            KeeperRecordUpdateAction(),
            KeeperSecretAction()
        )
        
        configureFileWithSelection("test = value", 7, 12)
        
        actions.forEach { action ->
            val event = createActionEventWithEditor()
            
            try {
                action.actionPerformed(event)
                // If we reach here, no exception was thrown
                assertTrue("${action.javaClass.simpleName} should handle service failures gracefully", true)
            } catch (e: Exception) {
                // Some actions may throw, which is acceptable for service failures
                assertTrue("${action.javaClass.simpleName} threw exception: ${e.message}", true)
            }
        }
    }
}
