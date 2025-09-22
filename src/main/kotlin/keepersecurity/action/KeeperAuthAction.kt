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
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "Failed to start Keeper shell.\nPlease check that Keeper CLI is installed and you're authenticated.",
                                "Authorization Failed"
                            )
                        }
                        return
                    }

                    fun probe(timeout: Long): Pair<Boolean, String> {
                        val output = try {
                            try {
                                KeeperShellService.executeCommand("", timeout)
                            } catch (_: Exception) {
                                KeeperShellService.executeCommand("this-device", timeout)
                            }
                        } catch (e: Exception) {
                            return false to "Probe failed: ${e.message ?: "Unknown error"}"
                        }
                        val ok = output.contains("My Vault>", true) ||
                                 output.contains("Keeper>", true) ||
                                 output.contains("Status: SUCCESSFUL", true) ||
                                 output.contains("Device Name:", true) ||
                                 output.contains("Persistent Login: ON", true) ||
                                 output.contains("record(s)", true) ||
                                 (output.isNotBlank() && !output.contains("error", true) && !output.contains("failed", true))
                        return ok to output
                    }

                    val (ok, out) = probe(if (wasReady) 15 else 45)
                    if (ok) {
                        val msg = if (out.contains("Successfully authenticated with Biometric Login", true)) {
                            "Keeper shell is ready. Authentication passed using biometric."
                        } else if (out.contains("Not logged in>", true)) {
                            "Keeper shell is ready but not authenticated.\nPlease run 'keeper login' once to establish a persistent session."
                        } else {
                            "Keeper shell is ready."
                        }
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(project, msg, "Authorization Check")
                        }
                        return
                    }

                    // Last fallback: gentle newline probe then report as before...
                    try {
                        val finalOut = KeeperShellService.executeCommand("", 10)
                        ApplicationManager.getApplication().invokeLater {
                            if (finalOut.contains(">") || finalOut.isBlank()) {
                                Messages.showInfoMessage(project, "Keeper shell is responding. Authorization likely OK.", "Authorization Check")
                            } else {
                                Messages.showErrorDialog(project, "Keeper shell did not report ready.\n$finalOut", "Authorization Possibly Not Ready")
                            }
                        }
                    } catch (_: Exception) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, "Unable to verify Keeper shell readiness.", "Authorization Failed")
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