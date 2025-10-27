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

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.VfsUtilCore

import java.io.File
import javax.swing.JScrollPane
import javax.swing.JTextArea
import kotlinx.serialization.ExperimentalSerializationApi

// Added imports
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.service.KeeperShellService

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

        val envFile = chooseEnvFile(project, file)
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

    private fun chooseEnvFile(project: Project, file: VirtualFile): File? {
        val defaultEnv = File(file.parent.path, ".env")
        val options = if (defaultEnv.exists()) arrayOf(".env", "Browse") else arrayOf("Browse")

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

    private fun buildSuccessMessage(result: ProcessResult, duration: Long): String {
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
        // FIXED REGEX: Now captures bracket notation like address[zip] and custom.field[key]
        val keeperPattern = Regex("""keeper://([A-Za-z0-9_-]+)/field/(\S+)""")
        
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
                logger.info("Raw JSON length: ${secretJson.length}")
                logger.info("Raw JSON preview (first 500 chars): ${secretJson.take(500)}")
                
                val jsonElement = json.parseToJsonElement(secretJson)
                logger.info("Successfully parsed JSON to JsonElement")
                
                // FIXED: Now handles bracket notation like address[zip]
                val secret = try {
                    extractFieldValue(jsonElement.jsonObject, field)
                } catch (e: Exception) {
                    logger.error("Failed to extract field '$field' from JSON", e)
                    logger.error("Stack trace:", e)
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

    /**
        * Extracts a field value from a Keeper JSON record.
        * Handles both legacy format (--legacy) and new format
        * Supports simple fields (e.g., `password`) and complex bracket notation (e.g., `address[zip]`, `custom.field[subkey]`)
        * Searches ALL possible locations in the JSON for maximum extensibility
        */
    private fun extractFieldValue(jsonObject: JsonObject, fieldPath: String): String? {
        logger.info("=== EXTRACTING FIELD: $fieldPath ===")
        logger.info("JSON keys available: ${jsonObject.keys.joinToString(", ")}")
        
        // Parse the field path
        val bracketIndex = fieldPath.indexOf('[')
        
        if (bracketIndex > 0 && fieldPath.endsWith(']')) {
            // Complex field with bracket notation: address[zip]
            val parentFieldName = fieldPath.substring(0, bracketIndex).removePrefix("custom.")
            val subFieldName = fieldPath.substring(bracketIndex + 1, fieldPath.length - 1)
            
            logger.info("Extracting complex field: parent='$parentFieldName', subField='$subFieldName'")
            
            // Search for the parent field everywhere in the JSON
            val parentObject = findFieldObjectAnywhere(jsonObject, parentFieldName)
            
            if (parentObject != null) {
                val result = parentObject[subFieldName]?.jsonPrimitive?.contentOrNull
                if (result != null) {
                    logger.info("Found sub-field '$subFieldName' in parent '$parentFieldName': ${result.take(10)}...")
                    return result
                } else {
                    logger.warn("Found parent '$parentFieldName' but sub-field '$subFieldName' not found in it")
                }
            } else {
                logger.warn("Parent field '$parentFieldName' not found anywhere in JSON")
            }
            
            return null
        } else {
            // Simple field: password, login, custom.field, etc.
            val fieldName = fieldPath.removePrefix("custom.")
            logger.info("Extracting simple field: '$fieldName'")
            
            // Search for the field value everywhere in the JSON
            val result = findFieldValueAnywhere(jsonObject, fieldName)
            
            if (result != null) {
                logger.info("Found field '$fieldName': ${result.take(10)}...")
            } else {
                logger.warn("Field '$fieldName' not found anywhere in JSON")
            }
            
            return result
        }
    }

    /**
    * Searches for a field object (JsonObject) by name in all possible locations
    * Returns the JsonObject value if found, null otherwise
    */
    private fun findFieldObjectAnywhere(jsonObject: JsonObject, fieldName: String): JsonObject? {
        logger.info("Searching for field object '$fieldName' in all locations...")
        
        // 1. Check top-level (legacy format: "address": {...})
        jsonObject[fieldName]?.let { value ->
            if (value is JsonObject) {
                logger.info("Found '$fieldName' as top-level object")
                return value
            }
        }
        
        // 2. Check in "fields" array (new format)
        jsonObject["fields"]?.jsonArray?.forEach { fieldElement ->
            val fieldObj = fieldElement.jsonObject
            val type = fieldObj["type"]?.jsonPrimitive?.contentOrNull
            
            if (type == fieldName) {
                logger.info("Found '$fieldName' in 'fields' array")
                // Extract the first value if it's an object
                val valueArray = fieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    if (firstValue is JsonObject) {
                        logger.info("Extracted object from 'value' array")
                        return firstValue
                    }
                }
            }
        }
        
        // 3. Check in "custom" array (new format) - CHECK BOTH type AND label
        jsonObject["custom"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val type = customFieldObj["type"]?.jsonPrimitive?.contentOrNull
            val label = customFieldObj["label"]?.jsonPrimitive?.contentOrNull
            
            // Match by type OR label (with or without space-to-underscore conversion)
            if (type == fieldName || 
                label == fieldName || 
                label?.replace("\\s".toRegex(), "_") == fieldName) {
                logger.info("Found '$fieldName' in 'custom' array (type='$type', label='$label')")
                // Extract the first value if it's an object
                val valueArray = customFieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    if (firstValue is JsonObject) {
                        return firstValue
                    }
                }
            }
        }
        
        // 4. Check in "custom_fields" array (legacy format)
        jsonObject["custom_fields"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val name = customFieldObj["name"]?.jsonPrimitive?.contentOrNull?.removeSuffix(":")
            
            // Match by name (with or without space-to-underscore conversion)
            if (name == fieldName || name?.replace("\\s".toRegex(), "_") == fieldName) {
                logger.info("Found '$fieldName' in 'custom_fields' array (name='$name')")
                val value = customFieldObj["value"]
                if (value is JsonObject) {
                    logger.info("Value is a JsonObject")
                    return value
                }
            }
        }
        
        logger.info("Field object '$fieldName' not found in any location")
        return null
    }

    /**
    * Searches for a simple field value (string) by name in all possible locations
    * Returns the string value if found, null otherwise
    */
    private fun findFieldValueAnywhere(jsonObject: JsonObject, fieldName: String): String? {
        logger.info("Searching for field value '$fieldName' in all locations...")
        
        // 1. Check top-level (legacy format: "password": "value")
        jsonObject[fieldName]?.jsonPrimitive?.contentOrNull?.let { value ->
            logger.info("Found '$fieldName' as top-level primitive")
            return value
        }
        
        // 2. Check in "fields" array (new format)
        jsonObject["fields"]?.jsonArray?.forEach { fieldElement ->
            val fieldObj = fieldElement.jsonObject
            val type = fieldObj["type"]?.jsonPrimitive?.contentOrNull
            
            if (type == fieldName) {
                logger.info("Found '$fieldName' in 'fields' array")
                // Extract the first value
                val valueArray = fieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    val content = firstValue.jsonPrimitive.contentOrNull
                    if (content != null) {
                        logger.info("Extracted string value from 'value' array in 'fields'")
                        return content
                    }
                }
            }
        }
        
        // 3. Check in "custom" array (new format) - CHECK BOTH type AND label
        jsonObject["custom"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val type = customFieldObj["type"]?.jsonPrimitive?.contentOrNull
            val label = customFieldObj["label"]?.jsonPrimitive?.contentOrNull
            
            // Match by type OR label (with or without space-to-underscore conversion)
            if (type == fieldName || 
                label == fieldName || 
                label?.replace("\\s".toRegex(), "_") == fieldName) {
                logger.info("Found '$fieldName' in 'custom' array (type='$type', label='$label')")
                // Extract the first value
                val valueArray = customFieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    val content = firstValue.jsonPrimitive.contentOrNull
                    if (content != null) {
                        logger.info("Extracted string value from 'value' array in 'custom'")
                        return content
                    }
                }
            }
        }
        
        // 4. Check in "custom_fields" array (legacy format)
        jsonObject["custom_fields"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val name = customFieldObj["name"]?.jsonPrimitive?.contentOrNull?.removeSuffix(":")
            
            // Match by name (with or without space-to-underscore conversion)
            if (name == fieldName || name?.replace("\\s".toRegex(), "_") == fieldName) {
                logger.info("Found '$fieldName' in 'custom_fields' array (name='$name')")
                val value = customFieldObj["value"]
                val content = value?.jsonPrimitive?.contentOrNull
                if (content != null) {
                    logger.info("Extracted string value from 'custom_fields'")
                    return content
                }
            }
        }
        
        logger.info("Field value '$fieldName' not found in any location")
        return null
    }
    
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
                "get $uid --format json", 
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