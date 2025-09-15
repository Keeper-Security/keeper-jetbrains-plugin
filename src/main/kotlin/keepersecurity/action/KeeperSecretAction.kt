package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperCommandUtils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import java.io.File
import javax.swing.JFileChooser
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.UIManager
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class KeeperSecretAction : AnAction("Run Keeper Securely") {
    private val logger = thisLogger()
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        val envFile = chooseEnvFile(file)
        if (envFile == null || !envFile.exists()) {
            Messages.showErrorDialog(project, "No valid .env file selected.", "Error")
            return
        }

        // Ask user for the command to run their script
        val commandInput = Messages.showInputDialog(
            project,
            "Enter the command to run your script (e.g., python3 example.py):",
            "Run Script Command",
            Messages.getQuestionIcon(),
            "python3 ${file.name}",
            null
        )?.trim()

        if (commandInput.isNullOrEmpty()) {
            Messages.showWarningDialog(project, "No command provided, aborting.", "Cancelled")
            return
        }

        // 🔧 FIX: Save both files on EDT BEFORE starting background task
        ApplicationManager.getApplication().runWriteAction {
            val fileDocumentManager = FileDocumentManager.getInstance()
            
            // Save the main script file
            fileDocumentManager.saveDocument(document)
            logger.info("Saved main script file to disk")
            
            // Save .env file if it's open in the IDE
            val envVirtualFile = file.parent.findChild(envFile.name)
            if (envVirtualFile != null) {
                val envDocument = fileDocumentManager.getDocument(envVirtualFile)
                if (envDocument != null) {
                    fileDocumentManager.saveDocument(envDocument)
                    logger.info("Saved .env file to disk")
                }
            }
        }

        val originalContent = document.text

        object : Task.Backgroundable(project, "Fetching Keeper Secrets...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."
                
                // Check if Keeper shell is ready
                if (!isKeeperReady()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Keeper is not ready!\nPlease run 'Check Keeper Authorization' first.",
                            "Keeper Not Ready"
                        )
                    }
                    return
                }

                indicator.text = "Processing .env and fetching secrets via persistent shell..."
                val startTime = System.currentTimeMillis()
                val result = processKeeperSecrets(originalContent, envFile, file, commandInput, indicator)
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
                                javax.swing.JOptionPane.INFORMATION_MESSAGE
                            )
                        }
                    } else {
                        Messages.showWarningDialog(project, buildFailureMessage(result), "No Secrets Found")
                    }
                }
            }
        }.queue()
    }

    // ... chooseEnvFile and browseForEnvFile methods remain the same ...

    private fun chooseEnvFile(file: VirtualFile): File? {
        val defaultEnv = File(file.parent.path, ".env")
        val options = if (defaultEnv.exists()) {
            arrayOf(".env", "Browse")
        } else {
            arrayOf("Browse")
        }

        val selectedOption = Messages.showEditableChooseDialog(
            "Select .env file:",
            "Choose .env File",
            null,
            options,
            if (defaultEnv.exists()) ".env" else "Browse",
            null
        ) ?: return null

        return when (selectedOption) {
            ".env" -> defaultEnv
            "Browse" -> browseForEnvFile()
            else -> null
        }
    }

    private fun browseForEnvFile(): File? {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())

        val chooser = JFileChooser().apply {
            dialogTitle = "Select .env File"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("*.env", "env")
            preferredSize = java.awt.Dimension(700, 500)
        }

        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun buildSuccessMessage(result: ProcessResult, duration: Long): String {
        return buildString {
            appendLine("Successfully injected ${result.replacements} secret(s)!")
            appendLine("Completed in ${duration}ms using persistent shell!")
            appendLine("Script executed with latest saved changes!")
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine("Some errors occurred:")
                result.errors.take(3).forEach { appendLine("• $it") }
                if (result.errors.size > 3) appendLine("• ... and ${result.errors.size - 3} more")
            }
        }
    }

    private fun buildFailureMessage(result: ProcessResult): String {
        return if (result.errors.isNotEmpty()) {
            "No secrets were injected.\n\nErrors:\n" +
                    result.errors.take(3).joinToString("\n") { "• $it" }
        } else {
            "No Keeper references found in .env file!\n\nExpected format:\nKEY=keeper://UID/field/FieldName"
        }
    }

    private data class ProcessResult(
        val updatedContent: String,
        val replacements: Int,
        val errors: List<String>,
        val scriptOutput: String
    )

    private fun processKeeperSecrets(
        originalContent: String, 
        envFile: File, 
        sourceFile: VirtualFile, 
        commandLine: String,
        indicator: ProgressIndicator
    ): ProcessResult {
        val errors = mutableListOf<String>()
        val envVars = mutableMapOf<String, String>()
        val keeperPattern = Regex("""keeper://([A-Za-z0-9_-]+)/field/(\w+)""")
        
        // Parse .env file and identify Keeper references (files are already saved)
        val keeperRefs = mutableListOf<Triple<String, String, String>>() // key, uid, field
        
        try {
            envFile.readLines().forEachIndexed { index, line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEachIndexed
                val (key, value) = parts.map { it.trim() }
                val match = keeperPattern.matchEntire(value)
                if (match != null) {
                    val uid = match.groupValues[1]
                    val field = match.groupValues[2]
                    keeperRefs.add(Triple(key, uid, field))
                    logger.info("Found Keeper ref: $key -> keeper://$uid/field/$field")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to read .env file: ${envFile.absolutePath}", e)
            return ProcessResult(originalContent, 0, listOf("Failed to read .env file: ${e.message}"), "")
        }
        
        if (keeperRefs.isEmpty()) {
            return ProcessResult(originalContent, 0, listOf("No Keeper references found in .env file"), "")
        }
        
        logger.info("Found ${keeperRefs.size} Keeper references to process")
        indicator.text = "Fetching ${keeperRefs.size} secrets via persistent shell..."
        
        // Process each Keeper reference
        keeperRefs.forEachIndexed { index, (key, uid, field) ->
            indicator.text = "Fetching secret ${index + 1}/${keeperRefs.size}: $key"
            
            try {
                val secretStartTime = System.currentTimeMillis()
                val secretJson = getKeeperJsonFromShell(uid)
                val secretDuration = System.currentTimeMillis() - secretStartTime
                
                logger.info("Secret $key fetched in ${secretDuration}ms")
                
                val jsonElement = json.parseToJsonElement(secretJson)
                val secret = try {
                    jsonElement.jsonObject[field]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    logger.warn("Failed to extract field '$field' from JSON", e)
                    null
                }
                
                if (!secret.isNullOrEmpty()) {
                    envVars[key] = secret
                    logger.info("Injected: $key=****** (${secret.length} chars)")
                } else {
                    errors.add("Field '$field' not found in Keeper record $uid")
                    logger.warn("Field '$field' not found in record $uid")
                }
            } catch (e: Exception) {
                errors.add("Error fetching $uid/$field - ${e.message}")
                logger.error("Error fetching Keeper secret for $uid/$field", e)
            }
        }
        
        indicator.text = "Running script with injected secrets..."
        val scriptOutput = runScriptWithEnv(envVars, commandLine, errors, File(sourceFile.parent.path))
        
        return ProcessResult(originalContent, envVars.size, errors, scriptOutput)
    }

    // ... rest of the methods remain the same ...
    
    private fun runScriptWithEnv(
        envVars: Map<String, String>, 
        commandLine: String, 
        errors: MutableList<String>, 
        fileParentDir: File
    ): String {
        return try {
            val commandParts = commandLine.split("""\s+""".toRegex())

            val pb = ProcessBuilder(commandParts).redirectErrorStream(true)
            pb.directory(fileParentDir)

            val env = pb.environment()
            env.putAll(envVars)
            
            logger.info("Running script with ${envVars.size} injected secrets")
            logger.info("Working directory: ${fileParentDir.absolutePath}")
            logger.info("Command: ${commandParts.joinToString(" ")}")
            
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            logger.info("Script completed with exit code: $exitCode")
            if (exitCode != 0) {
                errors.add("Command exited with code $exitCode")
            }
            output
        } catch (ex: Exception) {
            logger.error("Failed to run script", ex)
            errors.add("Failed to execute script: ${ex.message}")
            ""
        }
    }

    private fun getKeeperJsonFromShell(uid: String): String {
        return try {
            val output = KeeperCommandUtils.executeCommandWithRetry(
                "get $uid --format json --legacy", 
                KeeperCommandUtils.Presets.jsonObject(maxRetries = 3),
                logger
            )
            
            // Use the utility to extract JSON, handling prefixes like "[79] record(s)"
            val jsonString = KeeperJsonUtils.extractJsonObject(output, logger)
            logger.debug("Extracted JSON for $uid (${jsonString.length} chars)")
            
            return jsonString
            
        } catch (ex: Exception) {
            logger.error("Failed to get Keeper JSON for $uid", ex)
            throw RuntimeException("Failed to fetch Keeper record $uid: ${ex.message}")
        }
    }

    private fun isKeeperReady(): Boolean {
        return try {
            logger.info("Checking if Keeper shell is ready...")
            
            val wasAlreadyReady = KeeperShellService.isReady()
            
            if (!wasAlreadyReady) {
                logger.info("Starting Keeper shell...")
                if (!KeeperShellService.startShell()) {
                    logger.error("Failed to start Keeper shell")
                    return false
                }
                
                // Shell just started - give it extra time to initialize
                logger.info("Shell started successfully, waiting for full initialization...")
                Thread.sleep(5000) // Increased from 3 to 5 seconds
            }
            
            // Use longer timeout for first-time startup, shorter for already running shell
            val timeoutSeconds = if (wasAlreadyReady) 15L else 45L
            logger.info("Verifying shell readiness (timeout: ${timeoutSeconds}s)...")
            
            // Try a simple command first to test basic responsiveness
            val output = try {
                KeeperShellService.executeCommand("", timeoutSeconds) // Send empty command to get prompt
            } catch (e: Exception) {
                logger.warn("Empty command failed, trying 'this-device': ${e.message}")
                KeeperShellService.executeCommand("this-device", timeoutSeconds)
            }
            
            // Log the FULL output for debugging
            logger.info("FULL READINESS CHECK OUTPUT")
            logger.info("Output length: ${output.length} chars")
            logger.info("Raw output: '$output'")
            logger.info("END OUTPUT")
            
            // More comprehensive readiness checks
            val isReady = output.contains("My Vault>", ignoreCase = true) ||
                        output.contains("Keeper>", ignoreCase = true) ||
                        output.contains("Not logged in>", ignoreCase = true) ||
                        output.contains("Persistent Login: ON", ignoreCase = true) ||
                        output.contains("Status: SUCCESSFUL", ignoreCase = true) ||
                        output.contains("Device Name:", ignoreCase = true) ||
                        output.contains("Decrypted [", ignoreCase = true) ||
                        output.contains("record(s)", ignoreCase = true) ||
                        (output.isNotBlank() && !output.contains("error", ignoreCase = true) && !output.contains("failed", ignoreCase = true))
            
            if (isReady) {
                logger.info("Keeper shell is ready and authenticated")
            } else {
                logger.warn("Shell readiness check failed")
                logger.warn("Expected patterns not found in output")
                
                // Try one more simple test - just send a newline
                try {
                    logger.info("Attempting final readiness test...")
                    val testOutput = KeeperShellService.executeCommand("", 10)
                    logger.info("Final test output: '$testOutput'")
                    
                    if (testOutput.contains(">") || testOutput.isBlank()) {
                        logger.info("Final test passed - shell appears ready")
                        return true
                    }
                } catch (e: Exception) {
                    logger.warn("Final test failed: ${e.message}")
                }
            }
            
            return isReady
            
        } catch (ex: Exception) {
            logger.error("Error checking Keeper readiness: ${ex.message}")
            logger.debug("Full exception details", ex)
            false
        }
    }
}