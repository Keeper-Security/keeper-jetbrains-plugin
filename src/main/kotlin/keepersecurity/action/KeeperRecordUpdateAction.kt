package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperCommandUtils

class KeeperRecordUpdateAction : AnAction("Update Keeper Record") {
    private val logger = thisLogger()

    private val keeperStandardFields = setOf(
        "accountNumber", "address", "addressRef", "bankAccountItem", "bankAccount", "birthDate", "cardRef",
        "checkbox", "databaseType", "date", "directoryType", "email", "expirationDate", "fileRef", "secret",
        "host", "keyPair", "licenseNumber", "login", "multiline", "name", "oneTimeCode", "otp", "pamHostname",
        "pamResources", "passkey", "password", "paymentCardItem", "paymentCard", "phoneItem", "phone", "pinCode",
        "recordRef", "schedule", "script", "note", "securityQuestion", "text", "url"
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caret = editor.caretModel.currentCaret

        val selectionData = ReadAction.compute<SelectionData, RuntimeException> {
            val selText = caret.selectedText?.trim()
            val line = caret.logicalPosition.line
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val lineText = document.getText(TextRange(lineStart, lineEnd))
            val eqIndex = lineText.indexOf('=')

            if (!selText.isNullOrBlank()) {
                SelectionData(caret.selectionStart, caret.selectionEnd, selText, null)
            } else if (eqIndex != -1 && caret.offset > lineStart + eqIndex) {
                val rhsStart = lineStart + eqIndex + 1
                val rhsEnd = lineEnd
                val rhsText = document.getText(TextRange(rhsStart, rhsEnd)).trim()
                SelectionData(rhsStart, rhsEnd, rhsText, lineText.substring(0, eqIndex).trim())
            } else {
                SelectionData(null, null, null, null)
            }
        }

        if (selectionData.start == null || selectionData.end == null || selectionData.text.isNullOrBlank()) {
            showError("Please select the value after '=' or place caret on it.", project)
            return
        }

        val recordUid = Messages.showInputDialog(
            project,
            "Enter Keeper record UID:",
            "Record UID",
            null
        )?.trim()

        if (recordUid.isNullOrBlank()) {
            showError("Record UID is required", project)
            return
        }

        val fieldName = Messages.showInputDialog(
            project,
            "Enter Keeper field name (e.g., login, password, url, or custom):",
            "Field Name",
            null,
            selectionData.detectedFieldName ?: "",
            null
        )?.trim()

        if (fieldName.isNullOrBlank()) {
            showError("Field name is required", project)
            return
        }

        // Execute the update in background using persistent shell
        object : Task.Backgroundable(project, "Updating Keeper Record...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."
                
                try {
                    val startTime = System.currentTimeMillis()
                    
                    // Format the field properly for the shell command
                    val formattedField = if (fieldName in keeperStandardFields) {
                        "$fieldName=\"${selectionData.text}\""
                    } else {
                        "\"$fieldName\"=\"${selectionData.text}\""
                    }

                    // Build the command without "keeper" prefix since we're in the shell
                    val command = "record-update --record=\"$recordUid\" $formattedField"
                    
                    logger.info("Executing record update: $command")
                    
                    // Execute with retry logic for reliability
                    val output = KeeperCommandUtils.executeCommandWithRetry(
                        command,
                        KeeperCommandUtils.RetryConfig(
                            maxRetries = 5, // More retries for first-time runs
                            timeoutSeconds = 30,
                            retryDelayMs = 2000, // Longer delay between retries
                            logLevel = KeeperCommandUtils.LogLevel.INFO,
                            validation = KeeperCommandUtils.ValidationConfig(
                                customValidator = { output ->
                                    // For record-update, empty/minimal output often means success
                                    // We should REJECT output that contains sync status or errors
                                    val isSyncStatus = output.contains("Decrypted [") || 
                                                    output.contains("record(s)") ||
                                                    output.contains("breachwatch list") ||
                                                    output.contains("Use \"breachwatch list\" command")
                                    
                                    val isErrorMessage = output.contains("error", ignoreCase = true) ||
                                                    output.contains("failed", ignoreCase = true) ||
                                                    output.contains("invalid", ignoreCase = true) ||
                                                    output.contains("not found", ignoreCase = true)
                                    
                                    // Success means: no sync status AND no error messages
                                    // Empty output is actually GOOD for record-update
                                    val isValid = !isSyncStatus && !isErrorMessage
                                    
                                    if (!isValid) {
                                        logger.debug("Record update validation failed - isSyncStatus: $isSyncStatus, isErrorMessage: $isErrorMessage")
                                        logger.debug("Output: ${output.take(200)}...")
                                    } else {
                                        logger.debug("Record update validation passed - output length: ${output.length}")
                                    }
                                    
                                    isValid
                                }
                            )
                        ),
                        logger
                    )
                    
                    val duration = System.currentTimeMillis() - startTime
                    logger.info("Record update executed in ${duration}ms")
                    
                    val keeperReference = "keeper://$recordUid/field/$fieldName"

                    // Update the editor on the UI thread
                    ApplicationManager.getApplication().invokeLater({
                        WriteCommandAction.runWriteCommandAction(project) {
                            document.replaceString(selectionData.start!!, selectionData.end!!, keeperReference)
                            FileDocumentManager.getInstance().saveDocument(document)
                        }
                        Messages.showInfoMessage(
                            project, 
                            "Keeper record updated!\n\n$keeperReference\n\nExecuted in persistent shell in ${duration}ms!", 
                            "Keeper Record Updated"
                        )
                    }, ModalityState.defaultModalityState())

                } catch (ex: Exception) {
                    logger.error("Error updating Keeper record via persistent shell", ex)
                    ApplicationManager.getApplication().invokeLater({
                        showError("Failed to update Keeper record: ${ex.message}", project)
                    }, ModalityState.defaultModalityState())
                }
            }
        }.queue()
    }

    private fun showError(message: String, project: com.intellij.openapi.project.Project) {
        ApplicationManager.getApplication().invokeLater({
            Messages.showErrorDialog(project, message, "Error")
        }, ModalityState.defaultModalityState())
    }

    private data class SelectionData(
        val start: Int?,
        val end: Int?,
        val text: String?,
        val detectedFieldName: String?
    )
}