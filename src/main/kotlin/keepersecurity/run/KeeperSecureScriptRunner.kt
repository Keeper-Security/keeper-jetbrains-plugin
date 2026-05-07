package keepersecurity.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperRecordFieldExtractor
import java.io.File

/**
 * Shared pipeline: read `.env` Keeper refs, fetch secrets via shell, run a command with injected env.
 * Used by [keepersecurity.action.KeeperSecretAction] and [KeeperSecureRunConfiguration].
 */
@OptIn(ExperimentalSerializationApi::class)
object KeeperSecureScriptRunner {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    data class ExecutionResult(
        val replacements: Int,
        val errors: List<String>,
        val scriptOutput: String,
        val exitCode: Int,
    )

    fun run(
        project: Project?,
        envFile: File,
        workingDirectory: File,
        commandLine: String,
        indicator: ProgressIndicator,
        logger: Logger,
    ): ExecutionResult {
        val errors = mutableListOf<String>()
        val keeperPattern = Regex("""keeper://([A-Za-z0-9_-]+)/field/(\S+)""")
        val keeperRefs = mutableListOf<Triple<String, String, String>>()

        try {
            envFile.readLines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEach
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
            return ExecutionResult(0, listOf("Failed to read .env file: ${e.message}"), "", -1)
        }

        if (keeperRefs.isEmpty()) {
            return ExecutionResult(0, listOf("No Keeper references found in .env file"), "", -1)
        }

        if (!isKeeperReady(logger)) {
            return ExecutionResult(
                0,
                listOf("Keeper is not ready. Run 'Check Keeper Authorization' first."),
                "",
                -1,
            )
        }

        val envVars = mutableMapOf<String, String>()
        logger.info("Found ${keeperRefs.size} Keeper references to process")
        indicator.text = "Fetching ${keeperRefs.size} secrets via persistent shell..."

        keeperRefs.forEachIndexed { index, (key, uid, field) ->
            indicator.text = "Fetching secret ${index + 1}/${keeperRefs.size}: $key"
            try {
                val secretJson = getKeeperJsonFromShell(uid, logger)
                val jsonElement = json.parseToJsonElement(secretJson)
                val secret = try {
                    KeeperRecordFieldExtractor.extractFieldValue(jsonElement.jsonObject, field, logger)
                } catch (e: Exception) {
                    logger.error("Failed to extract field '$field' from JSON", e)
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
        val (scriptOutput, exitCode) = runScriptWithEnv(envVars, commandLine, errors, workingDirectory, logger)
        return ExecutionResult(envVars.size, errors, scriptOutput, exitCode)
    }

    private fun runScriptWithEnv(
        envVars: Map<String, String>,
        commandLine: String,
        errors: MutableList<String>,
        fileParentDir: File,
        logger: Logger,
    ): Pair<String, Int> {
        return try {
            if (commandLine.isBlank()) {
                errors.add("Command is empty")
                return Pair("", -1)
            }

            // Wrap in a login shell so the full user PATH (nvm, homebrew, pyenv, etc.) is available.
            // ProcessBuilder does a direct exec() and only sees the IDE's stripped-down launch PATH,
            // which means bare names like "node" or "python" fail unless an absolute path is given.
            val commandParts = if (SystemInfo.isWindows) {
                listOf("cmd", "/c", commandLine)
            } else {
                val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/bash"
                listOf(shell, "-lc", commandLine)
            }

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
            Pair(output, exitCode)
        } catch (ex: Exception) {
            logger.error("Failed to run script", ex)
            errors.add("Failed to execute script: ${ex.message}")
            Pair("", -1)
        }
    }

    private fun getKeeperJsonFromShell(uid: String, logger: Logger): String {
        val output = KeeperCommandUtils.executeCommandWithRetry(
            "get $uid --format json",
            KeeperCommandUtils.Presets.jsonObject(maxRetries = 3),
            logger,
        )
        return KeeperJsonUtils.extractJsonObject(output, logger)
    }

    private fun isKeeperReady(logger: Logger): Boolean {
        return try {
            logger.info("Checking if Keeper shell is ready...")
            val wasAlreadyReady = KeeperShellService.isReady()

            if (!wasAlreadyReady) {
                logger.info("Starting Keeper shell...")
                if (!KeeperShellService.startShell()) {
                    logger.error("Failed to start Keeper shell")
                    return false
                }
                logger.info("Shell started successfully, waiting for full initialization...")
                Thread.sleep(5000)
            }

            val timeoutSeconds = if (wasAlreadyReady) 15L else 45L
            logger.info("Verifying shell readiness (timeout: ${timeoutSeconds}s)...")

            val output = try {
                KeeperShellService.executeCommand("", timeoutSeconds)
            } catch (e: Exception) {
                logger.warn("Empty command failed, trying 'this-device': ${e.message}")
                KeeperShellService.executeCommand("this-device", timeoutSeconds)
            }

            val isReady = output.contains("My Vault>", ignoreCase = true) ||
                output.contains("Keeper>", ignoreCase = true) ||
                output.contains("Not logged in>", ignoreCase = true) ||
                output.contains("Persistent Login: ON", ignoreCase = true) ||
                output.contains("Status: SUCCESSFUL", ignoreCase = true) ||
                output.contains("Device Name:", ignoreCase = true) ||
                output.contains("Decrypted [", ignoreCase = true) ||
                output.contains("record(s)", ignoreCase = true) ||
                (output.isNotBlank() && !output.contains("error", ignoreCase = true) && !output.contains("failed", ignoreCase = true))

            if (!isReady) {
                try {
                    val testOutput = KeeperShellService.executeCommand("", 10)
                    if (testOutput.contains(">") || testOutput.isBlank()) {
                        return true
                    }
                } catch (e: Exception) {
                    logger.warn("Final test failed: ${e.message}")
                }
            }

            isReady
        } catch (ex: Exception) {
            logger.error("Error checking Keeper readiness: ${ex.message}")
            logger.debug("Full exception details", ex)
            false
        }
    }
}
