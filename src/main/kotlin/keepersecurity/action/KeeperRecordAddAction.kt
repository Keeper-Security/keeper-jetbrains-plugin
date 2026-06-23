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
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperCliSafety
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperRecordOutputValidators

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
        val rawSelection = caret.selectedText?.trim()

        if (rawSelection.isNullOrBlank()) {
            showError("Please select the text you want to store in Keeper.", project)
            return
        }

        val selectedText = try {
            KeeperCliSafety.requireSafe(rawSelection, "selected text")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Selected text is not safe to send to Keeper.", project)
            return
        }

        // Ask whether this record should land in the Classic vault or in a Nested Shared Folder
        // BEFORE prompting for any record metadata. This avoids wasting the user's
        // inputs if they cancel out of the target dialog.
        val target = when (val outcome = KeeperRecordTargetPrompt.promptForAddTarget(project)) {
            is KeeperRecordTargetPrompt.AddOutcome.Cancelled -> return
            is KeeperRecordTargetPrompt.AddOutcome.Classic -> AddTarget.Classic(outcome.folderUuid, outcome.folderName)
            is KeeperRecordTargetPrompt.AddOutcome.Drive -> AddTarget.Drive(outcome.folderUuid, outcome.folderName)
        }

        // Get input from user on EDT
        val rawTitle = Messages.showInputDialog(project, "Enter Keeper record title:", "Record Title", null)
            ?.takeIf { it.isNotBlank() } ?: return
        val title = try {
            KeeperCliSafety.requireSafe(rawTitle, "record title")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Record title is not safe.", project)
            return
        }

        val rawFieldName = Messages.showInputDialog(
            project,
            "Enter Keeper field type (e.g., login, password, url, or custom field name):",
            "Field Type",
            null
        )?.takeIf { it.isNotBlank() } ?: return
        val fieldName = try {
            KeeperCliSafety.requireSafe(rawFieldName, "field name")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Field name is not safe.", project)
            return
        }

        // Run the record creation in background
        val taskTitle = when (target) {
            is AddTarget.Classic -> "Creating Keeper Record..."
            is AddTarget.Drive -> "Creating Nested Shared Record..."
        }
        object : Task.Backgroundable(project, taskTitle, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val startTime = System.currentTimeMillis()

                    indicator.text = "Syncing with Keeper vault..."
                    KeeperCommandUtils.syncDownBestEffort(logger)

                    indicator.text = taskTitle
                    // Create the record using persistent shell
                    val recordUid = createKeeperRecord(project, target, title, fieldName, selectedText)
                    
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

                        val location = when (target) {
                            is AddTarget.Classic -> target.folderName?.let { "Classic Vault · $it" } ?: "Classic Vault root"
                            is AddTarget.Drive -> target.folderName?.let { "Nested Shared Folder · $it" } ?: "Nested Shared Folder root"
                        }
                        Messages.showInfoMessage(
                            project,
                            "Keeper record created!\n\n$keeperReference\n\nCreated in $location",
                            "Keeper Record Added"
                        )
                    }
                    
                } catch (fatal: KeeperCommandUtils.FatalCommandException) {
                    logger.error("Keeper Commander reported a fatal error while creating the record", fatal)
                    ApplicationManager.getApplication().invokeLater {
                        showError(
                            "Keeper Commander rejected the record-add call:\n\n${fatal.message}\n\n" +
                                "This usually means the installed Keeper Commander build does not support the " +
                                "command we issued. Try upgrading Commander (`pip install --upgrade keepercommander`) " +
                                "or pick a Nested Shared Folder in the target prompt if you are creating a Nested Shared record.",
                            project
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
     * Create a Keeper record using the persistent shell service. Routes to
     * either the classic `record-add` command or the Nested Shared Folder
     * Subfolders `nsf-record-add` command based on [target].
     */
    private fun createKeeperRecord(
        project: Project,
        target: AddTarget,
        title: String,
        fieldName: String,
        selectedText: String
    ): String {
        val formattedField = formatField(fieldName, selectedText)
        val command = when (target) {
            is AddTarget.Classic -> buildClassicAddCommand(title, formattedField, target.folderUuid)
            is AddTarget.Drive -> buildDriveAddCommand(title, formattedField, target.folderUuid)
        }

        val verb = command.substringBefore(' ')
        logger.info("Issuing $verb (target=${if (target is AddTarget.Drive) "Drive" else "Classic"}, " +
            "hasFolder=${command.contains("--folder")})")

        // Execute with proper validation for record creation
        val output = KeeperCommandUtils.executeCommandWithRetry(
            command,
            KeeperCommandUtils.RetryConfig(
                maxRetries = 5, // More retries for first-time runs
                timeoutSeconds = 45, // Longer timeout for record creation
                retryDelayMs = 2000, // Longer delay between retries
                logLevel = KeeperCommandUtils.LogLevel.INFO,
                validation = KeeperCommandUtils.ValidationConfig(
                    customValidator = { out ->
                        val ok = KeeperRecordOutputValidators.isRecordAddSuccess(out)
                        if (!ok) {
                            logger.debug("Record creation validation failed for output: ${out.take(150)}...")
                        }
                        ok
                    },
                    fatalErrorDetector = KeeperRecordOutputValidators::isFatalError
                )
            ),
            logger
        )

        // Extract UID from output
        val recordUid = KeeperRecordOutputValidators.extractRecordUid(output)
            ?: throw RuntimeException("Could not find UID in Keeper CLI output. Output: $output")

        logger.info("Created record with UID: $recordUid")
        return recordUid
    }

    /**
     * The classic folder UUID is now resolved (with the project-scope vs
     * app-scope fallback and the "Pick Folder First" UX) by
     * [KeeperRecordTargetPrompt]; this builder just consumes the value.
     * `null` / blank means "create at the Classic Vault root" — no
     * `--folder` flag emitted.
     */
    private fun buildClassicAddCommand(title: String, formattedField: String, classicFolderUuid: String?): String {
        val parts = mutableListOf(
            "record-add",
            "--title=\"$title\"",
            "--record-type=login"
        )
        if (!classicFolderUuid.isNullOrBlank()) {
            parts.add("--folder=\"$classicFolderUuid\"")
            logger.info("Using classic folder UUID: $classicFolderUuid")
        } else {
            logger.info("No classic folder UUID set; record will be created at the vault root")
        }
        parts.add(formattedField)
        return parts.joinToString(" ")
    }

    private fun buildDriveAddCommand(title: String, formattedField: String, driveFolderUuid: String?): String {
        // `nsf-record-add` uses space-separated flag form per the Nested Shared Folders docs.
        val parts = mutableListOf(
            "nsf-record-add",
            "--title", "\"$title\"",
            "--record-type", "login"
        )
        if (!driveFolderUuid.isNullOrBlank()) {
            parts += listOf("--folder", "\"$driveFolderUuid\"")
            logger.info("Using Nested Shared Folder UUID: $driveFolderUuid")
        } else {
            logger.info("Creating Nested Shared record at the Nested Shared Folder root (no folder UUID set)")
        }
        parts.add(formattedField)
        return parts.joinToString(" ")
    }

    private fun formatField(fieldName: String, selectedText: String): String = when {
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

    private sealed class AddTarget {
        data class Classic(val folderUuid: String?, val folderName: String?) : AddTarget()
        data class Drive(val folderUuid: String?, val folderName: String?) : AddTarget()
    }

    private fun showError(message: String, project: Project) {
        Messages.showErrorDialog(project, message, "Error")
    }
}
