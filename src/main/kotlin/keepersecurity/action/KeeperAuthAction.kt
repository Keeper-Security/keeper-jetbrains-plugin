package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import keepersecurity.service.KeeperShellService
import java.util.concurrent.TimeUnit

/**
 * Three-state outcome of the Keeper shell probe used by [KeeperAuthAction].
 *
 *  - `AUTHENTICATED` — shell is up *and* the user is logged in. Safe to
 *    proceed with any other Keeper action.
 *  - `NOT_LOGGED_IN` — shell process exists, but Commander reports the
 *    "Not logged in>" prompt. The user must run `keeper login` once.
 *  - `FAILED` — shell did not respond, returned blank output, or otherwise
 *    failed to demonstrate a working authenticated session. We refuse to
 *    declare success on weak evidence (older code's catch-all rule led to
 *    false-positive "Authorization OK" dialogs against a half-dead shell).
 */
private enum class ProbeResult { AUTHENTICATED, NOT_LOGGED_IN, FAILED }

/**
 * Action to check and diagnose Keeper authentication status
 */
class KeeperAuthAction : AnAction("Check Keeper Authorization") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        object : Task.Backgroundable(project, "Checking Keeper Authorization...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Diagnosing Keeper shell status..."
                
                try {
                    logger.info("Starting Keeper authorization check")
                    diagnoseTTYAndInteractivity()
                    
                    indicator.text = "Starting/validating Keeper shell..."
                    val wasReady = KeeperShellService.isReady()
                    val started = if (!wasReady) KeeperShellService.startShell() else true
                    if (!started) {
                        // Differentiate "Commander is asking for a master password
                        // and we can't supply one over pipes" from "the CLI is
                        // missing / crashed". The former is the common case after
                        // `keeper logout` and the user just needs to refresh their
                        // persistent-login token once in a real terminal.
                        val (title, msg) = if (KeeperShellService.isAuthRequired()) {
                            "Not Logged In to Keeper" to (
                                "Keeper Commander is asking for a master password, " +
                                "but the plugin can't supply one over a piped shell.\n\n" +
                                "Open a terminal and run:\n" +
                                "    keeper login --persistent\n\n" +
                                "Once you're logged in there (and Persistent Login is " +
                                "enabled on your account), come back and try this action again."
                            )
                        } else {
                            "Authorization Failed" to (
                                "Failed to start Keeper shell.\n" +
                                "Please check that Keeper CLI is installed and you're authenticated."
                            )
                        }
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, msg, title)
                        }
                        return
                    }

                    /**
                     * Classify the probe output. Important: by the time the
                     * output reaches us, [KeeperShellService.executeCommand]
                     * has already filtered out the literal prompt strings
                     * (`My Vault>`, `Keeper>`, `Not logged in>`) — see the
                     * stripper at `KeeperShellService.kt#L678/685`. So we
                     * can't rely on prompts as positive markers; we look at
                     * the surrounding banner text instead.
                     *
                     * Positive evidence of an authenticated session (any of
                     * these only appear *after* Commander successfully
                     * authenticates and starts syncing):
                     *   - "Decrypted N records" / "Updated security data"
                     *   - "Quick Start:" help banner
                     *   - "breachwatch" / "high-risk passwords" warnings
                     *   - "Successfully authenticated" /
                     *     "Status: SUCCESSFUL" / "Device Name:" /
                     *     "Persistent Login: ON"
                     *
                     * Negative evidence — Commander text emitted *before* the
                     * stripped "Not logged in>" prompt:
                     *   - "you are not logged in"
                     *   - "please login" / "use `login`"
                     *
                     * Falls back to [KeeperShellService.isReady] when the
                     * banner text alone is inconclusive — the service flips
                     * its ready flag only after observing a real prompt on
                     * the raw stream, so it's the most trustworthy signal.
                     */
                    fun probe(timeout: Long): Pair<ProbeResult, String> {
                        val output = try {
                            try {
                                KeeperShellService.executeCommand("", timeout)
                            } catch (_: Exception) {
                                KeeperShellService.executeCommand("this-device", timeout)
                            }
                        } catch (e: Exception) {
                            return ProbeResult.FAILED to "Probe failed: ${e.message ?: "Unknown error"}"
                        }

                        val notLoggedIn = output.contains("you are not logged in", true) ||
                            output.contains("please login", true) ||
                            output.contains("not authenticated", true) ||
                            output.contains("use `login`", true) ||
                            output.contains("use 'login'", true)

                        val authMarkers = listOf(
                            // Post-login sync / decrypt banner
                            "Decrypted",
                            "Updated security data",
                            "high-risk passwords",
                            "breachwatch list",
                            // Post-login help banner (Commander v18+)
                            "Quick Start:",
                            // `this-device` / login-status output
                            "Status: SUCCESSFUL",
                            "Device Name:",
                            "Persistent Login: ON",
                            "Successfully authenticated"
                        )
                        val authenticated = authMarkers.any { output.contains(it, ignoreCase = true) }

                        return when {
                            authenticated && !notLoggedIn -> ProbeResult.AUTHENTICATED to output
                            notLoggedIn -> ProbeResult.NOT_LOGGED_IN to output
                            // Last resort: the persistent shell flips its
                            // ready flag only after seeing a real
                            // authenticated prompt on the raw stream. Trust
                            // that over an inconclusive stripped-output
                            // sample.
                            KeeperShellService.isReady() -> ProbeResult.AUTHENTICATED to output
                            else -> ProbeResult.FAILED to output
                        }
                    }

                    val (result, out) = probe(if (wasReady) 15 else 45)
                    when (result) {
                        ProbeResult.AUTHENTICATED -> {
                            val msg = if (out.contains("Successfully authenticated with Biometric Login", true)) {
                                "Keeper shell is ready. Authentication passed using biometric."
                            } else {
                                "Keeper shell is ready and authenticated."
                            }
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showInfoMessage(project, msg, "Authorization Check")
                            }
                        }
                        ProbeResult.NOT_LOGGED_IN -> {
                            // Not an OK state — surface as an error so the
                            // user doesn't mistake the info icon for success.
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    "Keeper shell is running but you are not logged in.\n\n" +
                                        "Please run `keeper login` in a terminal once to establish a " +
                                        "persistent session, then try this action again.",
                                    "Not Logged In to Keeper"
                                )
                            }
                        }
                        ProbeResult.FAILED -> {
                            // Don't paper over a half-dead shell as "likely
                            // OK". The user's own commands will fail next;
                            // it's better to flag it up front.
                            val detail = if (out.isBlank()) {
                                "The shell did not produce any output within the probe timeout."
                            } else {
                                "Output:\n${out.take(500)}"
                            }
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    project,
                                    "Keeper shell did not report a logged-in state.\n\n$detail",
                                    "Authorization Not Confirmed"
                                )
                            }
                        }
                    }
                    
                } catch (ex: Exception) {
                    logger.error("Authorization check failed", ex)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Authorization check failed: ${ex.message}",
                            "Error"
                        )
                    }
                }
            }
        }.queue()
    }

    /**
     * Diagnose TTY and interactivity issues that might prevent biometric authentication
     */
    private fun diagnoseTTYAndInteractivity() {
        logger.info("=== TTY AND INTERACTIVITY DIAGNOSTICS ===")
        
        try {
            // Check if we have a TTY
            val hasTTY = checkIfHasTTY()
            logger.info("TTY CHECK: Has TTY: $hasTTY")
            
            // Check stdin/stdout/stderr properties
            val stdinInfo = checkStreamInfo("stdin", System.`in`)
            val stdoutInfo = checkStreamInfo("stdout", System.out)
            val stderrInfo = checkStreamInfo("stderr", System.err)
            
            logger.info("STREAM CHECK: stdin - $stdinInfo")
            logger.info("STREAM CHECK: stdout - $stdoutInfo")
            logger.info("STREAM CHECK: stderr - $stderrInfo")
            
            // Check environment variables that indicate interactivity
            val termEnv = System.getenv("TERM")
            val displayEnv = System.getenv("DISPLAY")
            val sessionTypeEnv = System.getenv("XDG_SESSION_TYPE")
            val sshConnectionEnv = System.getenv("SSH_CONNECTION")
            
            logger.info("ENV CHECK: TERM = $termEnv")
            logger.info("ENV CHECK: DISPLAY = $displayEnv")
            logger.info("ENV CHECK: XDG_SESSION_TYPE = $sessionTypeEnv")
            logger.info("ENV CHECK: SSH_CONNECTION = $sshConnectionEnv")
            
            // Check if we can run the 'tty' command
            val ttyCommandResult = checkTTYCommand()
            logger.info("TTY COMMAND: $ttyCommandResult")
            
            // Check process properties
            val processInfo = getProcessInfo()
            logger.info("PROCESS INFO: $processInfo")
            
            // Summary
            val isLikelyInteractive = hasTTY && termEnv != null && !termEnv.equals("dumb", ignoreCase = true)
            logger.info("INTERACTIVITY ASSESSMENT: Likely interactive = $isLikelyInteractive")
            
            if (!isLikelyInteractive) {
                logger.warn("NON-INTERACTIVE ENVIRONMENT DETECTED - This may prevent biometric authentication")
                logger.warn("Reasons: TTY=$hasTTY, TERM=$termEnv")
            }
            
        } catch (e: Exception) {
            logger.warn("Failed to complete TTY/interactivity diagnostics", e)
        }
        
        logger.info("=== END TTY/INTERACTIVITY DIAGNOSTICS ===")
    }

    private fun checkIfHasTTY(): Boolean {
        return try {
            // Try multiple methods to detect TTY
            
            // Method 1: Check if stdin is a terminal using Java
            val consoleExists = System.console() != null
            
            // Method 2: Check standard streams
            val stdinIsTTY = System.`in`.available() >= 0 // Basic check
            
            // Method 3: Environment check
            val hasTermEnv = System.getenv("TERM") != null
            
            logger.debug("TTY DETECTION: console=$consoleExists, stdin=$stdinIsTTY, TERM=$hasTermEnv")
            
            consoleExists || (stdinIsTTY && hasTermEnv)
        } catch (e: Exception) {
            logger.debug("TTY detection failed", e)
            false
        }
    }

    private fun checkStreamInfo(streamName: String, stream: Any): String {
        return try {
            val className = stream.javaClass.simpleName
            val details = when (stream) {
                is java.io.InputStream -> "available=${stream.available()}"
                is java.io.PrintStream -> "autoFlush=${(stream as java.io.PrintStream).checkError()}"
                else -> "type=$className"
            }
            "$className($details)"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun checkTTYCommand(): String {
        return try {
            val process = ProcessBuilder("tty").start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                return "timeout"
            }
            
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.exitValue()
            
            "exitCode=$exitCode, output='$output'"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun getProcessInfo(): String {
        return try {
            val processHandle = ProcessHandle.current()
            
            val pid = processHandle.pid()
            val parentPid = processHandle.parent().map { it.pid() }.orElse(-1)
            val command = processHandle.info().command().orElse("unknown")
            val user = processHandle.info().user().orElse("unknown")
            
            "PID=$pid, ParentPID=$parentPid, Command=$command, User=$user"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    /**
     * Test if we can create a process with pseudo-TTY
     */
    private fun testProcessWithPTY(): Boolean {
        return try {
            logger.info("TESTING: Attempting to create process with PTY allocation")
            
            // Try to start a simple command that would show if we have TTY
            val testProcess = ProcessBuilder("env")
                .apply {
                    environment().apply {
                        // Try to force TTY-like environment
                        put("TERM", "xterm-256color")
                        put("FORCE_COLOR", "1")
                    }
                    redirectErrorStream(true)
                }
                .start()
            
            val completed = testProcess.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                testProcess.destroyForcibly()
                return false
            }
            
            val output = testProcess.inputStream.bufferedReader().readText()
            logger.info("PTY TEST: Process completed, output length: ${output.length}")
            
            true
        } catch (e: Exception) {
            logger.warn("PTY TEST: Failed to test PTY allocation", e)
            false
        }
    }
}