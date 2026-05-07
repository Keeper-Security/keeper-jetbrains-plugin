package keepersecurity.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import java.io.File

class KeeperSecureRunProfileState(
    private val environment: ExecutionEnvironment,
    private val configuration: KeeperSecureRunConfiguration,
) : RunProfileState {

    override fun execute(executor: Executor, programRunner: ProgramRunner<*>): ExecutionResult {
        val project = environment.project
        val base = project.basePath ?: throw ExecutionException("Project has no base path")
        val opts = configuration.options
        val envFile = KeeperSecurePathUtil.resolveToFile(opts.envFilePath.orEmpty(), base)
        val wdPath = opts.workingDirectoryPath.orEmpty().trim()
        val workDir = if (wdPath.isEmpty()) {
            File(base)
        } else {
            KeeperSecurePathUtil.resolveToFile(wdPath, base)
        }
        val handler = KeeperSecureProcessHandler(project, envFile, workDir, opts.command.orEmpty())
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        ProcessTerminatedListener.attach(handler, project)
        handler.startNotify()
        return DefaultExecutionResult(console, handler)
    }
}
