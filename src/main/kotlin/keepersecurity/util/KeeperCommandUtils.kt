package keepersecurity.util

import com.intellij.openapi.diagnostic.Logger
import keepersecurity.service.KeeperShellService

/**
 * Utility for executing Keeper CLI commands with retry logic and validation
 */
object KeeperCommandUtils {
    
    /**
     * Configuration for command execution with retries
     */
    data class RetryConfig(
        val maxRetries: Int = 3,
        val timeoutSeconds: Long = 30,
        val retryDelayMs: Long = 1000,
        val logLevel: LogLevel = LogLevel.INFO,
        val validation: ValidationConfig? = null
    )
    
    /**
     * Validation configuration for command output
     */
    data class ValidationConfig(
        val requiredContent: String? = null,
        val forbiddenContent: String? = null,
        val minLength: Int = 0,
        val customValidator: ((String) -> Boolean)? = null,
        /**
         * Optional predicate that, when it matches, short-circuits the retry
         * loop with a [FatalCommandException] carrying the actual CLI
         * output. Use this for failures that won't change between attempts
         * (e.g. Commander Python exceptions, "unknown command", "not logged
         * in") so the user sees the real error message instead of
         * "validation failed after N attempts".
         */
        val fatalErrorDetector: ((String) -> Boolean)? = null
    )

    /**
     * Thrown when a command's output matches [ValidationConfig.fatalErrorDetector].
     * Carries the CLI output so the calling action can surface it verbatim.
     */
    class FatalCommandException(val output: String, message: String) : RuntimeException(message)
    
    /**
     * Log level for retry operations
     */
    enum class LogLevel { DEBUG, INFO, WARN }
    
    /**
     * Execute a Keeper CLI command with retry logic and validation
     */
    fun executeCommandWithRetry(
        command: String,
        config: RetryConfig = RetryConfig(),
        logger: Logger? = null
    ): String {
        var lastException: Exception? = null
        
        for (attempt in 1..config.maxRetries) {
            try {
                val commandVerb = command.substringBefore(' ')
                when (config.logLevel) {
                    LogLevel.DEBUG -> logger?.debug("Attempt $attempt/${config.maxRetries}: $commandVerb")
                    LogLevel.INFO -> logger?.info("Attempt $attempt/${config.maxRetries}: $commandVerb")
                    LogLevel.WARN -> logger?.warn("Attempt $attempt/${config.maxRetries}: $commandVerb")
                }
                
                val output = KeeperShellService.executeCommand(command, config.timeoutSeconds)
                
                // Log raw output for debugging (only in DEBUG or INFO level)
                if (config.logLevel != LogLevel.WARN && logger != null) {
                    val preview = "${output.take(200)}${if (output.length > 200) "..." else ""}"
                    logger.info("Raw output (${output.length} chars): $preview")
                }
                
                // Basic validation - check if output is blank
                // BUT: Some commands (like record-update) succeed with no output
                if (output.isBlank() && config.validation == null) {
                    // Only treat empty output as error if no custom validator is provided
                    throw RuntimeException("Command returned empty output")
                }

                // If custom validation is provided, let it decide if empty output is valid
                if (output.isBlank() && config.validation != null) {
                    logger?.debug("Empty output received, deferring to custom validator")
                }
                
                // Apply custom validation if configured
                if (config.validation != null) {
                    // Short-circuit on fatal Commander errors — retrying a
                    // missing-module / "unknown command" call just wastes the
                    // user's time and buries the real CLI message under our
                    // "validation failed after N attempts" wrapper.
                    config.validation.fatalErrorDetector?.let { isFatal ->
                        if (isFatal(output)) {
                            val excerpt = output.lineSequence()
                                .map { it.trim() }
                                .firstOrNull { it.isNotBlank() }
                                ?.take(300)
                                ?: output.take(300)
                            logger?.warn("Fatal Commander error detected, aborting retries: $excerpt")
                            throw FatalCommandException(output, excerpt)
                        }
                    }

                    if (!isValidOutput(output, config.validation, logger)) {
                        if (attempt < config.maxRetries) {
                            logger?.info("Validation failed, retrying in ${config.retryDelayMs}ms...")
                            Thread.sleep(config.retryDelayMs)
                            continue
                        } else {
                            throw RuntimeException("Output validation failed after ${config.maxRetries} attempts")
                        }
                    }
                }
                
                when (config.logLevel) {
                    LogLevel.DEBUG -> logger?.debug("Got valid output on attempt $attempt")
                    LogLevel.INFO -> logger?.info("Got valid output on attempt $attempt")
                    LogLevel.WARN -> logger?.warn("Got output on attempt $attempt")
                }
                
                return output
                
            } catch (fatal: FatalCommandException) {
                // Hard Commander failures bypass the retry loop entirely.
                throw fatal
            } catch (ex: Exception) {
                lastException = ex
                logger?.warn("Attempt $attempt failed: ${ex.message}")
                
                if (attempt < config.maxRetries) {
                    logger?.info("Retrying in ${config.retryDelayMs}ms...")
                    Thread.sleep(config.retryDelayMs)
                } else {
                    logger?.error("All ${config.maxRetries} attempts failed")
                }
            }
        }
        
        throw lastException ?: RuntimeException("Command failed after ${config.maxRetries} attempts")
    }
    
    /**
     * Best-effort `sync-down` against the persistent Commander shell.
     *
     * The plugin keeps its own long-lived Commander process (see
     * `KeeperShellService`), which holds a per-session vault cache. Records
     * or folders that were created in a separate terminal session — even
     * after the user runs `sync-down` there — won't be visible to the
     * plugin's shell until *it* also syncs. Listing actions that drive
     * picker dialogs should call this before fetching so the picker
     * reflects the live vault.
     *
     * Failures are swallowed and logged: a failed sync should never block
     * the user from at least attempting the listing call that follows
     * (the stale cache is still better than nothing).
     */
    fun syncDownBestEffort(logger: Logger? = null, timeoutSeconds: Long = 30) {
        try {
            val startedAt = System.currentTimeMillis()
            KeeperShellService.executeCommand("sync-down", timeoutSeconds)
            val elapsed = System.currentTimeMillis() - startedAt
            logger?.info("sync-down completed in ${elapsed}ms")
        } catch (ex: Exception) {
            logger?.warn("sync-down failed (continuing with stale cache): ${ex.message}")
        }
    }

    /**
     * Validate command output based on configuration
     */
    private fun isValidOutput(output: String, validation: ValidationConfig, logger: Logger?): Boolean {
        // Check minimum length
        if (output.length < validation.minLength) {
            logger?.debug("Output too short: ${output.length} < ${validation.minLength}")
            return false
        }
        
        // Check required content
        validation.requiredContent?.let { required ->
            if (!output.contains(required, ignoreCase = true)) {
                logger?.debug("Required content '$required' not found in output")
                return false
            }
        }
        
        // Check forbidden content
        validation.forbiddenContent?.let { forbidden ->
            if (output.contains(forbidden, ignoreCase = true)) {
                logger?.debug("Forbidden content '$forbidden' found in output")
                return false
            }
        }
        
        // Apply custom validator
        validation.customValidator?.let { validator ->
            if (!validator(output)) {
                logger?.debug("Custom validator failed")
                return false
            }
        }
        
        return true
    }
    
    // Convenient preset configurations
    object Presets {
        /**
         * Configuration for commands that should return JSON objects (like 'get' commands)
         */
        fun jsonObject(
            maxRetries: Int = 3,
            timeoutSeconds: Long = 30,
            retryDelayMs: Long = 1000
        ) = RetryConfig(
            maxRetries = maxRetries,
            timeoutSeconds = timeoutSeconds,
            retryDelayMs = retryDelayMs,
            logLevel = LogLevel.DEBUG,
            validation = ValidationConfig(
                requiredContent = "{",
                minLength = 5
            )
        )
        
        /**
         * Configuration for commands that should return JSON arrays (like 'list' commands)
         */
        fun jsonArray(
            maxRetries: Int = 3,
            timeoutSeconds: Long = 45,
            retryDelayMs: Long = 2000
        ) = RetryConfig(
            maxRetries = maxRetries,
            timeoutSeconds = timeoutSeconds,
            retryDelayMs = retryDelayMs,
            logLevel = LogLevel.INFO,
            validation = ValidationConfig(
                requiredContent = "[",
                minLength = 5
            )
        )
        
        /**
         * Configuration for general commands without specific validation
         */
        fun general(
            maxRetries: Int = 3,
            timeoutSeconds: Long = 30,
            retryDelayMs: Long = 1000
        ) = RetryConfig(
            maxRetries = maxRetries,
            timeoutSeconds = timeoutSeconds,
            retryDelayMs = retryDelayMs,
            logLevel = LogLevel.INFO
        )
        
        /**
         * Configuration for password generation commands
         */
        fun passwordGeneration(
            maxRetries: Int = 3,
            timeoutSeconds: Long = 30,
            retryDelayMs: Long = 1000
        ) = RetryConfig(
            maxRetries = maxRetries,
            timeoutSeconds = timeoutSeconds,
            retryDelayMs = retryDelayMs,
            logLevel = LogLevel.INFO,
            validation = ValidationConfig(
                customValidator = { output ->
                    // Should contain generated password JSON
                    output.contains("password", ignoreCase = true) && 
                    (output.contains("{") || output.contains("["))
                }
            )
        )
    }
}