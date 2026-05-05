package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.VfsUtilCore

import java.io.File
import javax.swing.JScrollPane
import javax.swing.JTextArea
import kotlinx.serialization.ExperimentalSerializationApi

import keepersecurity.run.KeeperSecureScriptRunner

@OptIn(ExperimentalSerializationApi::class)
class KeeperSecretAction : AnAction("Run Keeper Securely") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        val envFile = chooseEnvFile(project, file)
        if (envFile == null || !envFile.exists()) {
            Messages.showErrorDialog(project, "No valid .env file selected.", "Error")
            return
        }

        val commandInput = Messages.showInputDialog(
            project,
            "Enter the command to run your script (e.g., python3 example.py):",
            "Run Script Command",
            Messages.getQuestionIcon(),
            "python3 ${file.name}",
            null,
        )?.trim()

        if (commandInput.isNullOrEmpty()) {
            Messages.showWarningDialog(project, "No command provided, aborting.", "Cancelled")
            return
        }

        ApplicationManager.getApplication().runWriteAction {
            val fileDocumentManager = FileDocumentManager.getInstance()
            fileDocumentManager.saveDocument(document)
            logger.info("Saved main script file to disk")

            val envVirtualFile = file.parent.findChild(envFile.name)
            if (envVirtualFile != null) {
                val envDocument = fileDocumentManager.getDocument(envVirtualFile)
                if (envDocument != null) {
                    fileDocumentManager.saveDocument(envDocument)
                    logger.info("Saved .env file to disk")
                }
            }
        }

        object : Task.Backgroundable(project, "Fetching Keeper Secrets...", false) {
            override fun run(indicator: ProgressIndicator) {
                val startTime = System.currentTimeMillis()
                val workingDir = File(file.parent.path)
                val result = KeeperSecureScriptRunner.run(
                    project,
                    envFile,
                    workingDir,
                    commandInput,
                    indicator,
                    logger,
                )
                val totalDuration = System.currentTimeMillis() - startTime
                logger.info("Total secret processing completed in ${totalDuration}ms")

                ApplicationManager.getApplication().invokeLater {
                    if (result.replacements > 0) {
                        val successMessage = buildSuccessMessage(result, totalDuration)
                        Messages.showInfoMessage(project, successMessage, "Secrets Injected")

                        if (result.scriptOutput.isNotBlank()) {
                            val textArea = JTextArea(result.scriptOutput.trim())
                            textArea.isEditable = false
                            val scrollPane = JScrollPane(textArea)
                            scrollPane.preferredSize = java.awt.Dimension(700, 420)
                            javax.swing.JOptionPane.showMessageDialog(
                                null,
                                scrollPane,
                                "Script Output",
                                javax.swing.JOptionPane.INFORMATION_MESSAGE,
                            )
                        }
                    } else {
                        Messages.showWarningDialog(project, buildFailureMessage(result), "No Secrets Found")
                    }
                }
            }
        }.queue()
    }

    private fun chooseEnvFile(project: Project, file: VirtualFile): File? {
        val defaultEnv = File(file.parent.path, ".env")
        val options = if (defaultEnv.exists()) arrayOf(".env", "Browse") else arrayOf("Browse")

        val selectedOption = Messages.showEditableChooseDialog(
            "Select .env file:",
            "Choose .env File",
            null,
            options,
            if (defaultEnv.exists()) ".env" else "Browse",
            null,
        ) ?: return null

        return when (selectedOption) {
            ".env" -> defaultEnv
            "Browse" -> browseForEnvFile(project)
            else -> null
        }
    }

    private fun browseForEnvFile(project: Project): File? {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().apply {
            title = "Select .env File"
            withFileFilter { vf ->
                val name = vf.name
                name.equals(".env", true) || name.endsWith(".env", true) || name.substringAfterLast('.', "").equals("env", true)
            }
        }
        val vFile = FileChooser.chooseFile(descriptor, project, null) ?: return null
        return VfsUtilCore.virtualToIoFile(vFile)
    }

    private fun buildSuccessMessage(result: KeeperSecureScriptRunner.ExecutionResult, duration: Long): String {
        return buildString {
            appendLine("Successfully injected ${result.replacements} secret(s)!")
            appendLine("Script executed with latest saved changes!")
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine("Some errors occurred:")
                result.errors.take(3).forEach { appendLine("• $it") }
                if (result.errors.size > 3) appendLine("• ... and ${result.errors.size - 3} more")
            }
        }
    }

    private fun buildFailureMessage(result: KeeperSecureScriptRunner.ExecutionResult): String {
        return if (result.errors.isNotEmpty()) {
            "No secrets were injected.\n\nErrors:\n" +
                result.errors.take(3).joinToString("\n") { "• $it" }
        } else {
            "No Keeper references found in .env file!\n\nExpected format:\nKEY=keeper://UID/field/FieldName"
        }
    }
}
