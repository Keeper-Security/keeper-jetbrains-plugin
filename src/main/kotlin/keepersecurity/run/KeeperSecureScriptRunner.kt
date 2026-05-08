package keepersecurity.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /** Hard ceiling on a single Run Keeper Securely invocation; the Stop button kicks in earlier. */
    private const val SCRIPT_HARD_TIMEOUT_SECONDS: Long = 24L * 60L * 60L

    /** Maximum time to wait for the persistent shell prompt to appear after startShell(). */
    private const val SHELL_READY_POLL_TIMEOUT_MS: Long = 30_000L
    private const val SHELL_READY_POLL_INTERVAL_MS: Long = 200L

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
        onProcessStarted: (Process) -> Unit = {},
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
            if (indicator.isCanceled) throw ProcessCanceledException()
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

        if (indicator.isCanceled) throw ProcessCanceledException()

        indicator.text = "Running script with injected secrets..."
        val (scriptOutput, exitCode) = runScriptWithEnv(
            envVars,
            commandLine,
            errors,
            workingDirectory,
            indicator,
            logger,
            onProcessStarted,
        )
        return ExecutionResult(envVars.size, errors, scriptOutput, exitCode)
    }

    private fun runScriptWithEnv(
        envVars: Map<String, String>,
        commandLine: String,
        errors: MutableList<String>,
        fileParentDir: File,
        indicator: ProgressIndicator,
        logger: Logger,
        onProcessStarted: (Process) -> Unit,
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
            onProcessStarted(process)

            // Drain stdout on a separate thread so a hung child can't block cancellation behind
            // a synchronous readText() that's waiting for an EOF that may never arrive.
            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            val scriptStart = System.currentTimeMillis()
            while (process.isAlive) {
                if (indicator.isCanceled) {
                    process.destroyForcibly()
                    throw ProcessCanceledException()
                }
                if (System.currentTimeMillis() - scriptStart > SCRIPT_HARD_TIMEOUT_SECONDS * 1000L) {
                    logger.warn("Script exceeded hard timeout (${SCRIPT_HARD_TIMEOUT_SECONDS}s); destroying.")
                    process.destroyForcibly()
                    errors.add("Command exceeded ${SCRIPT_HARD_TIMEOUT_SECONDS}s timeout and was killed")
                    break
                }
                process.waitFor(1, TimeUnit.SECONDS)
            }

            // Stop both cancels the indicator and force-kills the process; if the kill races ahead
            // of the next isCanceled check the loop exits with the SIGKILL exit code. Re-check here
            // so user cancellation always surfaces cleanly.
            if (indicator.isCanceled) {
                throw ProcessCanceledException()
            }

            val output = try {
                outputFuture.get(2, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                outputFuture.cancel(true)
                ""
            }

            val exitCode = process.exitValue()
            logger.info("Script completed with exit code: $exitCode")
            if (exitCode != 0) {
                errors.add("Command exited with code $exitCode")
            }
            Pair(output, exitCode)
        } catch (pce: ProcessCanceledException) {
            throw pce
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
                logger.info("Shell started; polling until prompt appears...")
                pollUntilShellPromptSeen(SHELL_READY_POLL_TIMEOUT_MS, logger)
            }

            // Liveness check only. KeeperShellService.executeCommand strips the prompt strings
            // (My Vault>, Keeper>, Not logged in>) out of its return value, so matching the response
            // content is unreliable. KeeperShellService.isReady() is authoritative — it's set during
            // startup once the real prompt is observed on the raw output.
            val timeoutSeconds = if (wasAlreadyReady) 15L else 45L
            logger.info("Probing shell liveness (timeout: ${timeoutSeconds}s)...")
            try {
                KeeperShellService.executeCommand("", timeoutSeconds)
            } catch (e: Exception) {
                logger.warn("Shell liveness probe failed: ${e.message}")
                return false
            }
            KeeperShellService.isReady()
        } catch (ex: Exception) {
            logger.error("Error checking Keeper readiness: ${ex.message}")
            logger.debug("Full exception details", ex)
            false
        }
    }

    /**
     * Polls [KeeperShellService.getLastStartupOutput] for one of the known prompt strings;
     * returns as soon as the prompt is seen, or when [maxWaitMs] elapses.
     */
    private fun pollUntilShellPromptSeen(maxWaitMs: Long, logger: Logger): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            val out = KeeperShellService.getLastStartupOutput()
            if (out.contains("My Vault>") ||
                out.contains("Keeper>") ||
                out.contains("Not logged in>")
            ) {
                logger.info("Shell prompt detected after ${System.currentTimeMillis() - start}ms")
                return true
            }
            try {
                Thread.sleep(SHELL_READY_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        logger.warn("Shell prompt not seen within ${maxWaitMs}ms")
        return false
    }
}
