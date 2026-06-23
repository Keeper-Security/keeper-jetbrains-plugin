package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import keepersecurity.util.KeeperCliSafety
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperRecordOutputValidators
import kotlinx.serialization.json.Json
import keepersecurity.model.GeneratedPassword
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class KeeperGenerateSecretsAction : AnAction("Keeper Generate Secrets") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caret = editor.caretModel.currentCaret
        val selectionStart = caret.selectionStart
        val selectionEnd = caret.selectionEnd

        // Ask whether the generated record should land in the Classic vault or
        // Nested Shared Folders before collecting any more input. The dialog title
        // matches the action label so the user knows what they're configuring.
        val target = when (val outcome = KeeperRecordTargetPrompt.promptForGenerateTarget(project)) {
            is KeeperRecordTargetPrompt.AddOutcome.Cancelled -> return
            is KeeperRecordTargetPrompt.AddOutcome.Classic -> GenerateTarget.Classic(outcome.folderUuid, outcome.folderName)
            is KeeperRecordTargetPrompt.AddOutcome.Drive -> GenerateTarget.Drive(outcome.folderUuid, outcome.folderName)
        }

        // Prompt for title *on the EDT* before background work
        val rawTitle = Messages.showInputDialog(
            project,
            "Enter Keeper record title:",
            "Record Title",
            null
        )?.takeIf { it.isNotBlank() } ?: run {
            showError("Title is required", project)
            return
        }

        val title = try {
            KeeperCliSafety.requireSafe(rawTitle, "record title")
        } catch (ex: KeeperCliSafety.UnsafeCliInputException) {
            showError(ex.message ?: "Record title is not safe.", project)
            return
        }

        val taskTitle = when (target) {
            is GenerateTarget.Classic -> "Generating Keeper Secret"
            is GenerateTarget.Drive -> "Generating Nested Shared Secret"
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val startTime = System.currentTimeMillis()

                    indicator.text = "Syncing with Keeper vault..."
                    KeeperCommandUtils.syncDownBestEffort(logger)

                    // Generate password using persistent shell
                    indicator.text = "Generating password..."
                    
                    val password = generatePasswordWithRetry() ?: throw RuntimeException("Could not generate password.")
                    
                    val generateDuration = System.currentTimeMillis() - startTime
                    logger.info("Password generation completed in ${generateDuration}ms")

                    // Create Keeper record using persistent shell
                    indicator.text = "Creating Keeper record..."
                    val recordStartTime = System.currentTimeMillis()
                    
                    val recordUid = addKeeperRecordWithRetry(target, title, password, project)
                    
                    val recordDuration = System.currentTimeMillis() - recordStartTime
                    logger.info("Record creation completed in ${recordDuration}ms")

                    if (recordUid != null) {
                        val keeperReference = "keeper://$recordUid/field/password"
                        val totalDuration = System.currentTimeMillis() - startTime
                        logger.info("Total operation completed in ${totalDuration}ms")

                        // Replace text in editor on EDT
                        ApplicationManager.getApplication().invokeLater {
                            WriteCommandAction.runWriteCommandAction(project) {
                                document.replaceString(selectionStart, selectionEnd, keeperReference)
                                FileDocumentManager.getInstance().saveDocument(document)
                            }
                            val location = when (target) {
                                is GenerateTarget.Classic ->
                                    target.folderName?.let { "Classic Vault · $it" } ?: "Classic Vault root"
                                is GenerateTarget.Drive ->
                                    target.folderName?.let { "Nested Shared Folder · $it" } ?: "Nested Shared Folder root"
                            }
                            Messages.showInfoMessage(
                                project,
                                "Keeper record created!\n\n$keeperReference\n\nGenerated in $location",
                                "Keeper Secret Generated"
                            )
                        }
                    } else {
                        showError("Failed to create Keeper record.", project)
                    }
                } catch (fatal: KeeperCommandUtils.FatalCommandException) {
                    logger.error("Keeper Commander reported a fatal error while generating the secret", fatal)
                    showError(
                        "Keeper Commander rejected the call:\n\n${fatal.message}\n\n" +
                            "This usually means the installed Keeper Commander build does not support the " +
                            "command we issued. Try upgrading Commander (`pip install --upgrade keepercommander`) " +
                            "or pick a Nested Shared Folder in the target prompt if you are generating a Nested Shared secret.",
                        project
                    )
                } catch (ex: Exception) {
                    logger.error("Error generating Keeper secret", ex)
                    showError("Error: ${ex.message}", project)
                }
            }
        })
    }

    private sealed class GenerateTarget {
        data class Classic(val folderUuid: String?, val folderName: String?) : GenerateTarget()
        data class Drive(val folderUuid: String?, val folderName: String?) : GenerateTarget()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * Generate password using persistent shell with retry logic
     */
    private fun generatePasswordWithRetry(): String? {
        return try {
            val output = KeeperCommandUtils.executeCommandWithRetry(
                "generate -f json",
                KeeperCommandUtils.RetryConfig(
                    maxRetries = 5, // Increased retries for first-time runs
                    timeoutSeconds = 30,
                    retryDelayMs = 2000, // Longer delay between retries
                    logLevel = KeeperCommandUtils.LogLevel.INFO,
                    validation = KeeperCommandUtils.ValidationConfig(
                        customValidator = { output ->
                            // Should contain actual password JSON, not sync status
                            val hasPassword = output.contains("password", ignoreCase = true)
                            val hasJson = output.contains("[") || output.contains("{")
                            val isNotSyncStatus = !output.contains("Decrypted [") && 
                                                !output.contains("record(s)") &&
                                                !output.contains("breachwatch list")
                            
                            val isValid = hasPassword && hasJson && isNotSyncStatus
                            
                            if (!isValid) {
                                logger.debug("Generate validation failed - hasPassword: $hasPassword, hasJson: $hasJson, isNotSyncStatus: $isNotSyncStatus")
                                logger.debug("Output: ${output.take(150)}...")
                            }
                            
                            isValid
                        },
                        fatalErrorDetector = KeeperRecordOutputValidators::isFatalError
                    )
                ),
                logger
            )
            
            // Extract password from output
            extractPasswordFromOutput(output)
            
        } catch (ex: Exception) {
            logger.error("Password generation failed after retries", ex)
            throw ex
        }
    }

    /**
     * Extract password from keeper generate output
     */
    private fun extractPasswordFromOutput(output: String): String? {
        try {
            logger.debug("Extracting password from output: ${output.take(200)}...")
            
            // Use KeeperJsonUtils to extract the JSON array properly
            val jsonString = KeeperJsonUtils.extractJsonArray(output, logger)
            logger.debug("Parsing generate JSON: ${jsonString.take(100)}...")

            val passwordList = json.decodeFromString<List<GeneratedPassword>>(jsonString)
            if (passwordList.isEmpty()) {
                logger.warn("Empty JSON array in generate output")
                return null
            }
            
            val password = passwordList.firstOrNull()?.password
            logger.info("Extracted password from JSON (length: ${password?.length ?: 0})")
            return password
            
        } catch (ex: Exception) {
            logger.error("Failed to parse generate JSON from output", ex)
            logger.error("Raw output was: $output")
            
            // Fallback: try regex extraction in case JSON parsing fails
            try {
                val passwordRegex = Regex(""""password":\s*"([^"]+)"""")
                val match = passwordRegex.find(output)
                if (match != null) {
                    val password = match.groupValues[1]
                    logger.info("Extracted password using regex fallback (length: ${password.length})")
                    return password
                }
            } catch (regexEx: Exception) {
                logger.debug("Regex fallback also failed: ${regexEx.message}")
            }
            
            return null
        }
    }

    /**
     * Add Keeper record using persistent shell with retry logic.
     *
     * Routes to either the classic `record-add` command or the Nested
     * Share Subfolders `nsf-record-add` command based on [target].
     */
    private fun addKeeperRecordWithRetry(
        target: GenerateTarget,
        title: String,
        password: String,
        project: Project
    ): String? {
        return try {
            // The password literal is the same for both branches; it must be
            // single-quoted so the CLI doesn't try to expand $-prefixed chars.
            val passwordField = "password='$password'"
            val command = when (target) {
                is GenerateTarget.Classic -> buildClassicAddCommand(title, passwordField, target.folderUuid)
                is GenerateTarget.Drive -> buildDriveAddCommand(title, passwordField, target.folderUuid)
            }
            logger.info("Issuing ${command.substringBefore(' ')} (target=${if (target is GenerateTarget.Drive) "Drive" else "Classic"})")
            
            val output = KeeperCommandUtils.executeCommandWithRetry(
                command,
                KeeperCommandUtils.RetryConfig(
                    maxRetries = 3,
                    timeoutSeconds = 45, // Longer timeout for record creation
                    retryDelayMs = 1000,
                    logLevel = KeeperCommandUtils.LogLevel.INFO,
                    validation = KeeperCommandUtils.ValidationConfig(
                        minLength = 10, // Should get some meaningful output
                        customValidator = { out ->
                            val ok = KeeperRecordOutputValidators.isRecordAddSuccess(out)
                            if (!ok) {
                                logger.debug("Generate record-add validation failed for output: ${out.take(150)}...")
                            }
                            ok
                        },
                        fatalErrorDetector = KeeperRecordOutputValidators::isFatalError
                    )
                ),
                logger
            )

            // Extract record UID from output
            extractRecordUidFromOutput(output)
            
        } catch (ex: Exception) {
            logger.error("Record creation failed after retries", ex)
            throw ex
        }
    }

    /**
     * Build the classic `record-add` command. The folder UUID is resolved
     * upstream by [KeeperRecordTargetPrompt] (with the project-scope vs
     * app-scope fallback and the "Pick Folder First" UX); this builder
     * just consumes the value. `null` / blank means "create at the
     * Classic Vault root" — no `--folder` flag emitted.
     */
    private fun buildClassicAddCommand(title: String, passwordField: String, classicFolderUuid: String?): String {
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
        parts.add(passwordField)
        return parts.joinToString(" ")
    }

    /**
     * Build the `nsf-record-add` command. `nsf-*` commands use space-separated
     * flag form per the Nested Shared Folders docs; the field literal still
     * uses the shared `field=value` syntax.
     */
    private fun buildDriveAddCommand(title: String, passwordField: String, driveFolderUuid: String?): String {
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
        parts.add(passwordField)
        return parts.joinToString(" ")
    }

    /**
     * Extract record UID from keeper record-add output
     */
    private fun extractRecordUidFromOutput(output: String): String? {
        try {
            // Look for typical Keeper record UID pattern (22 characters, alphanumeric + _ -)
            val uidMatch = Regex("""[A-Za-z0-9_-]{22}""").find(output)
            val recordUid = uidMatch?.value
            
            if (recordUid != null) {
                logger.info("Extracted record UID: $recordUid")
            } else {
                logger.warn("No record UID pattern found in output")
                logger.warn("Full output: $output")
            }
            
            return recordUid
            
        } catch (ex: Exception) {
            logger.error("Failed to extract record UID from output: $output", ex)
            return null
        }
    }

    private fun showError(message: String, project: Project) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Error")
        }
    }
}