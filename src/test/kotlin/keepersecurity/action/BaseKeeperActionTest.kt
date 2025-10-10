package keepersecurity.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import keepersecurity.service.MockKeeperService

/**
 * Base test class for Keeper actions with common testing utilities
 */
@TestDataPath("\$CONTENT_ROOT/src/test/testData")
abstract class BaseKeeperActionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        MockKeeperService.reset()
        MockKeeperService.setReady(true)
    }

    override fun tearDown() {
        MockKeeperService.reset()
        super.tearDown()
    }

    protected fun createActionEventWithEditor(): AnActionEvent {
        return AnActionEvent.createFromDataContext(
            "test",
            null,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, myFixture.project)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .build()
        )
    }

    protected fun createActionEventWithProjectOnly(): AnActionEvent {
        return AnActionEvent.createFromDataContext(
            "test",
            null,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, myFixture.project)
                .build()
        )
    }

    protected fun createActionEventEmpty(): AnActionEvent {
        return AnActionEvent.createFromDataContext(
            "test",
            null,
            SimpleDataContext.builder().build()
        )
    }

    protected fun configureFileWithSelection(content: String, selectionStart: Int, selectionEnd: Int) {
        myFixture.configureByText("test.txt", content)
        myFixture.editor.selectionModel.setSelection(selectionStart, selectionEnd)
    }

    protected fun configureFileWithCaretAt(content: String, caretPosition: Int) {
        myFixture.configureByText("test.txt", content)
        myFixture.editor.caretModel.moveToOffset(caretPosition)
    }

    protected fun assertDoesNotThrow(message: String? = null, action: () -> Unit) {
        try {
            action()
            assertTrue("Should complete without throwing", true)
        } catch (e: Exception) {
            fail("${message ?: "Operation"} should not throw exception: ${e.message}")
        }
    }

    override fun getTestDataPath() = "src/test/testData"
}
