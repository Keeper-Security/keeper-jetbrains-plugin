package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ide.util.PropertiesComponent
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperCommandUtils

class KeeperRecordAddAction : AnAction("Add Keeper Record") {
    private val logger = thisLogger()

    private val keeperStandardFields = setOf(
        "accountNumber", "address", "addressRef", "bankAccountItem", "bankAccount", "birthDate", "cardRef",
        "checkbox", "databaseType", "date", "directoryType", "email", "expirationDate", "fileRef", "secret",
        "host", "keyPair", "licenseNumber", "login", "multiline", "name", "oneTimeCode", "otp", "pamHostname",
        "pamResources", "passkey", "password", "paymentCardItem", "paymentCard", "phoneItem", "phone", "pinCode",
        "recordRef", "schedule", "script", "note", "securityQuestion", "text", "url"
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caret = editor.caretModel.currentCaret
        val selectedText = caret.selectedText?.trim()

        if (selectedText.isNullOrBlank()) {
            showError("Please select the text you want to store in Keeper.", project)
            return
        }

        // Get input from user on EDT
        val title = Messages.showInputDialog(project, "Enter Keeper record title:", "Record Title", null)
            ?.takeIf { it.isNotBlank() } ?: return

        val fieldName = Messages.showInputDialog(
            project, 
            "Enter Keeper field type (e.g., login, password, url, or custom field name):", 
            "Field Type", 
            null
        )?.takeIf { it.isNotBlank() } ?: return

        // Run the record creation in background
        object : Task.Backgroundable(project, "Creating Keeper Record...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."
                
                try {
                    val startTime = System.currentTimeMillis()
                    
                    // Create the record using persistent shell
                    val recordUid = createKeeperRecord(title, fieldName, selectedText)
                    
                    val duration = System.currentTimeMillis() - startTime
                    logger.info("Record created in ${duration}ms")
                    
                    // Update editor on EDT
                    ApplicationManager.getApplication().invokeLater {
                        val keeperReference = "keeper://$recordUid/field/$fieldName"
                        val start = caret.selectionStart
                        val end = caret.selectionEnd

                        WriteCommandAction.runWriteCommandAction(project) {
                            document.replaceString(start, end, keeperReference)
                            FileDocumentManager.getInstance().saveDocument(document)
                        }

                        Messages.showInfoMessage(
                            project, 
                            "Keeper record created!\n\n$keeperReference\n\nCreated via persistent shell in ${duration}ms!", 
                            "Keeper Record Added"
                        )
                    }
                    
                } catch (ex: Exception) {
                    logger.error("Failed to create Keeper record", ex)
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to create Keeper record: ${ex.message}", project)
                    }
                }
            }
        }.queue()
    }

    /**
     * Create a Keeper record using the persistent shell service
     */
    private fun createKeeperRecord(title: String, fieldName: String, selectedText: String): String {
        // Load folder UUID from local storage
        val props = PropertiesComponent.getInstance()
        val folderUUID = props.getValue("keeper.folder.uuid")

        // Build keeper command (without "keeper" prefix since we're in the shell)
        val commandParts = mutableListOf(
            "record-add",
            "--title=\"$title\"",
            "--record-type=login"
        )

        if (!folderUUID.isNullOrBlank()) {
            commandParts.add("--folder=\"$folderUUID\"")
            logger.info("Using folder UUID: $folderUUID")
        }

        // Format the field based on type and content
        val formattedField: String = when {
            fieldName == "password" && selectedText.startsWith("\$GEN", ignoreCase = true) -> {
                // Special case: random password generation
                "$fieldName='$selectedText'"
            }
            fieldName in keeperStandardFields -> {
                "$fieldName=\"$selectedText\""
            }
            else -> {
                // Custom field
                "\"$fieldName\"=\"$selectedText\""
            }
        }

        commandParts.add(formattedField)

        val command = commandParts.joinToString(" ")
        logger.info("Creating record with command: $command")

        // Execute with proper validation for record creation
        val output = KeeperCommandUtils.executeCommandWithRetry(
            command,
            KeeperCommandUtils.RetryConfig(
                maxRetries = 5, // More retries for first-time runs
                timeoutSeconds = 45, // Longer timeout for record creation
                retryDelayMs = 2000, // Longer delay between retries
                logLevel = KeeperCommandUtils.LogLevel.INFO,
                validation = KeeperCommandUtils.ValidationConfig(
                    customValidator = { output ->
                        // Should contain a record UID and NOT be sync status
                        val hasUid = Regex("""[A-Za-z0-9_-]{22}""").containsMatchIn(output)
                        val isNotSyncStatus = !output.contains("Decrypted [") && 
                                            !output.contains("record(s)") &&
                                            !output.contains("breachwatch list") &&
                                            !output.contains("Use \"breachwatch list\" command")
                        
                        val isValid = hasUid && isNotSyncStatus
                        
                        if (!isValid) {
                            logger.debug("Record creation validation failed - hasUid: $hasUid, isNotSyncStatus: $isNotSyncStatus")
                            logger.debug("Output: ${output.take(150)}...")
                        }
                        
                        isValid
                    }
                )
            ),
            logger
        )

        // Extract UID from output
        val recordUid = Regex("""[A-Za-z0-9_-]{22}""").find(output)?.value
            ?: throw RuntimeException("Could not find UID in Keeper CLI output. Output: $output")

        logger.info("Created record with UID: $recordUid")
        return recordUid
    }

    private fun showError(message: String, project: Project) {
        Messages.showErrorDialog(project, message, "Error")
    }
}