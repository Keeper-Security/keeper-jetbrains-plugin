package keepersecurity.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Runs [KeeperSecureScriptRunner] asynchronously and streams output into the Run tool window.
 *
 * Secret-fetching has to run before the user command can spawn, so the work is launched from
 * [startNotify]. The spawned [Process] and [ProgressIndicator] are stored as `@Volatile` fields
 * so [destroyProcessImpl] can cancel an in-flight fetch and terminate the user command.
 */
class KeeperSecureProcessHandler(
    private val project: Project,
    private val envFile: File,
    private val workingDir: File,
    private val command: String,
) : ProcessHandler() {

    private val logger = thisLogger()

    @Volatile
    private var userProcess: Process? = null

    @Volatile
    private var runIndicator: ProgressIndicator? = null

    override fun detachProcessImpl() {
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): java.io.OutputStream? = null

    override fun startNotify() {
        super.startNotify()
        object : Task.Backgroundable(project, "Run Keeper securely", true) {
            override fun run(indicator: ProgressIndicator) {
                runIndicator = indicator
                try {
                    val result = KeeperSecureScriptRunner.run(
                        project,
                        envFile,
                        workingDir,
                        command,
                        indicator,
                        logger,
                        onProcessStarted = { p -> userProcess = p },
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
                } catch (_: ProcessCanceledException) {
                    logger.info("Run Keeper Securely cancelled by user")
                    ApplicationManager.getApplication().invokeLater {
                        notifyTextAvailable("Cancelled by user\n", ProcessOutputTypes.STDERR)
                        notifyProcessTerminated(-1)
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
                } finally {
                    userProcess = null
                    runIndicator = null
                }
            }
        }.queue()
    }

    override fun destroyProcessImpl() {
        runIndicator?.cancel()
        userProcess?.destroyForcibly()
    }
}
