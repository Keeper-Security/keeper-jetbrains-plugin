package keepersecurity.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Runs [KeeperSecureScriptRunner] asynchronously and streams output into the Run tool window.
 */
class KeeperSecureProcessHandler(
    private val project: Project,
    private val envFile: File,
    private val workingDir: File,
    private val command: String,
) : ProcessHandler() {

    private val logger = thisLogger()

    override fun detachProcessImpl() {
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): java.io.OutputStream? = null

    override fun startNotify() {
        super.startNotify()
        object : Task.Backgroundable(project, "Run Keeper Securely", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = KeeperSecureScriptRunner.run(
                        project,
                        envFile,
                        workingDir,
                        command,
                        indicator,
                        logger,
                    )
                    ApplicationManager.getApplication().invokeLater {
                        if (result.scriptOutput.isNotEmpty()) {
                            notifyTextAvailable(result.scriptOutput, ProcessOutputTypes.STDOUT)
                        }
                        if (result.errors.isNotEmpty()) {
                            notifyTextAvailable(
                                result.errors.joinToString("\n", postfix = "\n"),
                                ProcessOutputTypes.STDERR,
                            )
                        }
                        val code = if (result.exitCode >= 0) result.exitCode else 1
                        notifyProcessTerminated(code)
                    }
                } catch (e: Exception) {
                    logger.error("Run Keeper Securely failed", e)
                    ApplicationManager.getApplication().invokeLater {
                        notifyTextAvailable(
                            (e.message ?: e.toString()) + "\n",
                            ProcessOutputTypes.STDERR,
                        )
                        notifyProcessTerminated(-1)
                    }
                }
            }
        }.queue()
    }

    override fun destroyProcessImpl() {
        // Background task cancellation could be wired here if needed
    }
}
