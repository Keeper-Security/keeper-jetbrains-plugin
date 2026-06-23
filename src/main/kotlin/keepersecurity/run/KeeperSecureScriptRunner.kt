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
import keepersecurity.util.KeeperCliSafety
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperEnvSafety
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperRecordFieldExtractor
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shared pipeline: read `.env` Keeper refs, fetch secrets via shell, run a command with injected env.
 * Used by [keepersecurity.action.KeeperSecretAction] and [KeeperSecureRunConfiguration].
 *
 * Concurrency: all Keeper shell commands route through [keepersecurity.service.KeeperShellService],
 * which serializes them behind a single ReentrantLock. Running two configurations simultaneously is
 * safe (no shell corruption), but the second run's secret-fetch phase will block until the first
 * releases the lock — it will appear to hang with "Fetching secrets…" until contention clears.
 */
@OptIn(ExperimentalSerializationApi::class)
object KeeperSecureScriptRunner {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /** Hard ceiling on a single Run Keeper Securely invocation; the Stop button kicks in earlier. */
    private const val SCRIPT_HARD_TIMEOUT_SECONDS: Long = 60L * 60L

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
        val keeperPattern = Regex("""keeper://([A-Za-z0-9_-]{22})/field/(\S+)""")
        val keeperRefs = mutableListOf<Triple<String, String, String>>()

        try {
            envFile.readLines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                val (key, value) = parts.map { it.trim() }
                val match = keeperPattern.matchEntire(value)
                if (match != null) {
                    KeeperEnvSafety.blockedEnvKeyReason(key)?.let { reason ->
                        errors.add("Skipped $key: $reason")
                        logger.warn("Blocked .env key '$key': $reason")
                        return@forEach
                    }
                    val uid = match.groupValues[1]
                    val field = match.groupValues[2]
                    if (!KeeperCliSafety.isValidRecordUid(uid)) {
                        errors.add(
                            "Skipped $key: invalid Keeper record UID '$uid' in keeper:// notation " +
                                "(expected exactly 22 characters: A-Z, a-z, 0-9, _, -)"
                        )
                        logger.warn("Invalid keeper:// UID for key '$key': '$uid'")
                        return@forEach
                    }
                    keeperRefs.add(Triple(key, uid, field))
                    logger.info("Found Keeper ref: $key -> keeper://$uid/field/$field")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to read .env file: ${envFile.absolutePath}", e)
            return ExecutionResult(0, listOf("Failed to read .env file: ${e.message}"), "", -1)
        }

        if (keeperRefs.isEmpty()) {
            val errorList = if (errors.isNotEmpty()) {
                errors
            } else {
                listOf("No Keeper references found in .env file")
            }
            return ExecutionResult(0, errorList, "", -1)
        }

        val envVars = mutableMapOf<String, String>()

        if (keeperRefs.isNotEmpty()) {
            if (!isKeeperReady(logger)) {
                if (indicator.isCanceled) throw ProcessCanceledException()
                return ExecutionResult(
                    0,
                    listOf("Keeper is not ready. Run 'Check Keeper Authorization' first."),
                    "",
                    -1,
                )
            }
            if (indicator.isCanceled) throw ProcessCanceledException()
        }
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
                    when (val verdict = KeeperEnvSafety.validateForInjection(key, secret)) {
                        is KeeperEnvSafety.Verdict.Allowed -> {
                            envVars[key] = verdict.value
                            logger.info("Injected: $key=****** (${secret.length} chars)")
                        }
                        is KeeperEnvSafety.Verdict.Blocked -> {
                            errors.add("Skipped $key: ${verdict.reason}")
                            logger.warn("Blocked env injection for '$key': ${verdict.reason}")
                        }
                    }
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
                val elapsedSeconds = (System.currentTimeMillis() - scriptStart) / 1000L
                if (elapsedSeconds > SCRIPT_HARD_TIMEOUT_SECONDS) {
                    logger.warn("Script exceeded hard timeout (${SCRIPT_HARD_TIMEOUT_SECONDS}s); destroying.")
                    process.destroyForcibly()
                    errors.add("Command exceeded ${SCRIPT_HARD_TIMEOUT_SECONDS}s timeout and was killed")
                    break
                }
                val remaining = SCRIPT_HARD_TIMEOUT_SECONDS - elapsedSeconds
                indicator.text = if (remaining > 120) {
                    "Running command… (${elapsedSeconds}s elapsed)"
                } else {
                    "Running command… ($remaining s until hard timeout)"
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
        if (!KeeperCliSafety.isValidRecordUid(uid)) {
            throw IllegalArgumentException("Invalid Keeper record UID: $uid")
        }
        KeeperCliSafety.requireSafe(uid, "record UID")
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
}
