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
import com.intellij.ide.util.PropertiesComponent
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperJsonUtils
import kotlinx.serialization.json.Json  
import kotlinx.serialization.decodeFromString
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

        // Prompt for title *on the EDT* before background work
        val title = Messages.showInputDialog(
            project,
            "Enter Keeper record title:",
            "Record Title",
            null
        )?.takeIf { it.isNotBlank() } ?: run {
            showError("Title is required", project)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Keeper Secret", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Using persistent Keeper shell..."
                    
                    // Generate password using persistent shell
                    indicator.text = "Generating password..."
                    val startTime = System.currentTimeMillis()
                    
                    val password = generatePasswordWithRetry() ?: throw RuntimeException("Could not generate password.")
                    
                    val generateDuration = System.currentTimeMillis() - startTime
                    logger.info("Password generation completed in ${generateDuration}ms")

                    // Create Keeper record using persistent shell
                    indicator.text = "Creating Keeper record..."
                    val recordStartTime = System.currentTimeMillis()
                    
                    val recordUid = addKeeperRecordWithRetry(title, password, project)
                    
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
                            Messages.showInfoMessage(
                                project,
                                "Keeper record created!\n\n$keeperReference\n\nGenerated via persistent shell in ${totalDuration}ms!",
                                "Keeper Secret Generated"
                            )
                        }
                    } else {
                        showError("Failed to create Keeper record.", project)
                    }
                } catch (ex: Exception) {
                    logger.error("Error generating Keeper secret", ex)
                    showError("Error: ${ex.message}", project)
                }
            }
        })
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
                        }
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
     * Add Keeper record using persistent shell with retry logic
     */
    private fun addKeeperRecordWithRetry(title: String, password: String, project: Project): String? {
        return try {
            val props = PropertiesComponent.getInstance(project)
            val folderUUID = props.getValue("keeper.folder.uuid")

            // Build command without 'keeper' prefix (we're in the shell)
            val cmdParts = mutableListOf(
                "record-add",
                "--title=\"$title\"",
                "--record-type=login"
            )

            if (!folderUUID.isNullOrBlank()) {
                cmdParts.add("--folder=\"$folderUUID\"")
                logger.info("Using folder UUID: $folderUUID")
            }

            cmdParts.add("password='$password'") // password must be single-quoted for CLI

            val command = cmdParts.joinToString(" ")
            logger.info("🔧 Running command: $command")
            
            val output = KeeperCommandUtils.executeCommandWithRetry(
                command,
                KeeperCommandUtils.RetryConfig(
                    maxRetries = 3,
                    timeoutSeconds = 45, // Longer timeout for record creation
                    retryDelayMs = 1000,
                    logLevel = KeeperCommandUtils.LogLevel.INFO,
                    validation = KeeperCommandUtils.ValidationConfig(
                        minLength = 10, // Should get some meaningful output
                        customValidator = { output ->
                            // Should contain a record UID pattern
                            Regex("""[A-Za-z0-9_-]{22}""").containsMatchIn(output)
                        }
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