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
import keepersecurity.util.KeeperCliSafety
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperRecordOutputValidators
import keepersecurity.util.KeeperRecordValidator

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

        val safeSelectionText = try {
            KeeperCliSafety.requireSafe(selectionData.text!!, "selected value")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Selected value is not safe to send to Keeper.", project)
            return
        }

        val rawRecordUid = Messages.showInputDialog(
            project,
            "Enter Keeper record UID:",
            "Record UID",
            null
        )?.trim()

        if (rawRecordUid.isNullOrBlank()) {
            showError("Record UID is required", project)
            return
        }

        val recordUid = try {
            KeeperCliSafety.requireSafe(rawRecordUid, "record UID")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Record UID is not safe.", project)
            return
        }

        // Look up the UID in the vault to decide which CLI command to use.
        // `list --format json` includes a `record_category` discriminator
        // (`Classic` / `Nested`, plus older legacy wire values) that maps 1:1 onto
        // `record-update` vs `nsf-record-update`, so the user never has to tell us
        // which namespace their UID lives in.
        // The lookup also rejects typo'd or deleted UIDs up-front instead
        // of letting them surface as a confusing "record not found"
        // mid-update.
        val isDrive = when (val verdict = KeeperRecordValidator.lookupRecord(project, recordUid)) {
            is KeeperRecordValidator.Verdict.Found -> {
                logger.info("Auto-dispatching update for record '${verdict.title}' ($recordUid) " +
                    "to ${if (verdict.kind == KeeperRecordValidator.Kind.DRIVE) "Nested Shared Folder" else "Classic Vault"}")
                verdict.kind == KeeperRecordValidator.Kind.DRIVE
            }
            is KeeperRecordValidator.Verdict.NotFound -> {
                showError(
                    "Record UID '$recordUid' was not found in your vault. " +
                        "Double-check the value (Tools \u2192 Keeper Vault \u2192 Get Keeper Secret " +
                        "lists every record with its UID).",
                    project
                )
                return
            }
            is KeeperRecordValidator.Verdict.Unknown -> {
                showError(
                    "Couldn't verify the record UID against your vault " +
                        "(${verdict.reason}). Make sure Keeper Commander is healthy and try again.",
                    project
                )
                return
            }
        }

        val rawFieldName = Messages.showInputDialog(
            project,
            "Enter Keeper field name (e.g., login, password, url, or custom):",
            "Field Name",
            null,
            selectionData.detectedFieldName ?: "",
            null
        )?.trim()

        if (rawFieldName.isNullOrBlank()) {
            showError("Field name is required", project)
            return
        }

        val fieldName = try {
            KeeperCliSafety.requireSafe(rawFieldName, "field name")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Field name is not safe.", project)
            return
        }

        val taskTitle = if (isDrive) "Updating Nested Shared Record..." else "Updating Keeper Record..."
        // Execute the update in background using persistent shell
        object : Task.Backgroundable(project, taskTitle, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val startTime = System.currentTimeMillis()

                    indicator.text = "Syncing with Keeper vault..."
                    KeeperCommandUtils.syncDownBestEffort(logger)

                    indicator.text = taskTitle
                    // Format the field properly for the shell command
                    val formattedField = if (fieldName in keeperStandardFields) {
                        "$fieldName=\"$safeSelectionText\""
                    } else {
                        "\"$fieldName\"=\"$safeSelectionText\""
                    }

                    // Build the command without "keeper" prefix since we're in the shell.
        // Classic uses `record-update --record="UID"`; Nested Shared Folders
        // uses `nsf-record-update -r <UID>` per the nsf-* docs.
                    val command = if (isDrive) {
                        "nsf-record-update -r \"$recordUid\" $formattedField"
                    } else {
                        "record-update --record=\"$recordUid\" $formattedField"
                    }
                    
                    logger.info("Issuing ${command.substringBefore(' ')} for record $recordUid")
                    
                    // Execute with retry logic for reliability
                    val output = KeeperCommandUtils.executeCommandWithRetry(
                        command,
                        KeeperCommandUtils.RetryConfig(
                            maxRetries = 5, // More retries for first-time runs
                            timeoutSeconds = 30,
                            retryDelayMs = 2000, // Longer delay between retries
                            logLevel = KeeperCommandUtils.LogLevel.INFO,
                            validation = KeeperCommandUtils.ValidationConfig(
                                customValidator = { out ->
                                    val ok = KeeperRecordOutputValidators.isRecordUpdateSuccess(out)
                                    if (!ok) {
                                        logger.debug("Record update validation failed for output: ${out.take(200)}...")
                                    } else {
                                        logger.debug("Record update validation passed - output length: ${out.length}")
                                    }
                                    ok
                                },
                                fatalErrorDetector = KeeperRecordOutputValidators::isFatalError
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
                        val source = if (isDrive) "Nested Shared record" else "Keeper record"
                        Messages.showInfoMessage(
                            project,
                            "$source updated!\n\n$keeperReference",
                            "Keeper Record Updated"
                        )
                    }, ModalityState.defaultModalityState())

                } catch (fatal: KeeperCommandUtils.FatalCommandException) {
                    logger.error("Keeper Commander reported a fatal error while updating the record", fatal)
                    ApplicationManager.getApplication().invokeLater({
                        showError(
                            "Keeper Commander rejected the record-update call:\n\n${fatal.message}\n\n" +
                                "This usually means the installed Keeper Commander build does not support the " +
                                "command we issued. Try upgrading Commander (`pip install --upgrade keepercommander`).",
                            project
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