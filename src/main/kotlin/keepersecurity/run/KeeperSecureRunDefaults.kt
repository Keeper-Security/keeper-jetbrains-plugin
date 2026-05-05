package keepersecurity.run

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Fills empty fields when a new run configuration is created: prefers an existing `.env` in the
 * project root, leaves working directory empty (project root), and sets [KeeperSecureRunConfigurationOptions.command]
 * when empty using (1) the **Python SDK** on the first module that has one (PyCharm / Python plugin),
 * else (2) a **venv** layout under the project root (`.venv`, `venv`, `env`).
 * If the command is still interpreter-only, appends `main.py` / `app.py` / `run.py` when that file exists
 * in the project root (common entry points — not a guarantee for every repo).
 */
object KeeperSecureRunDefaults {

    fun applyDefaults(project: Project, options: KeeperSecureRunConfigurationOptions) {
        val base = project.basePath ?: return
        val baseFile = File(base)
        if (options.envFilePath.isNullOrBlank()) {
            options.envFilePath = findEnvRelativePath(baseFile) ?: ".env"
        }
        if (options.workingDirectoryPath.isNullOrBlank()) {
            options.workingDirectoryPath = ""
        }
        if (options.command.isNullOrBlank()) {
            // ModuleRootManager / ProjectRootManager require a read lock; applyDefaults is invoked
            // from run config template setup on a background thread (CannotReadException otherwise).
            val fromSdk = ReadAction.compute<String?, RuntimeException> {
                interpreterFromProjectSdk(project)
            }
            var cmd = fromSdk
                ?: detectVenvPythonRelative(baseFile)
                ?: ""
            if (cmd.isNotBlank()) {
                cmd = maybeAppendPythonEntryScript(baseFile, cmd)
            }
            options.command = cmd
        }
    }

    /**
     * If [command] looks like an interpreter path only, append a conventional root script when present.
     * Does not guess arbitrary filenames — users still set the command for non-standard layouts.
     */
    private fun maybeAppendPythonEntryScript(base: File, command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return command
        if (trimmed.contains(".py")) return command
        if (trimmed.contains(" -m ") || trimmed.contains(" -c ")) return command
        val script = detectPythonEntryScriptRelative(base) ?: return command
        return "$trimmed $script"
    }

    private fun detectPythonEntryScriptRelative(base: File): String? {
        for (name in listOf("main.py", "app.py", "run.py")) {
            if (File(base, name).isFile) {
                return name
            }
        }
        return null
    }

    /**
     * Uses [ModuleRootManager.getSdk] — the same interpreter shown in PyCharm / IDEA with Python as the module SDK.
     */
    private fun interpreterFromProjectSdk(project: Project): String? {
        for (module in ModuleManager.getInstance(project).modules) {
            val sdk = ModuleRootManager.getInstance(module).sdk ?: continue
            if (!isPythonSdk(sdk)) continue
            val home = sdk.homePath?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return quoteForShellCommand(home)
        }
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk != null && isPythonSdk(projectSdk)) {
            val home = projectSdk.homePath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return quoteForShellCommand(home)
        }
        return null
    }

    private fun isPythonSdk(sdk: Sdk): Boolean {
        val type = sdk.sdkType
        if (type.name.contains("Python", ignoreCase = true)) return true
        if (type.javaClass.name.contains("python", ignoreCase = true)) return true
        val home = sdk.homePath ?: return false
        return looksLikePythonExecutablePath(home)
    }

    private fun looksLikePythonExecutablePath(path: String): Boolean {
        val p = path.lowercase().replace('\\', '/')
        return p.endsWith("/python") || p.endsWith("/python3") ||
            p.endsWith("/python.exe") || p.endsWith("/python3.exe")
    }

    private fun quoteForShellCommand(path: String): String =
        if (path.contains(' ') || path.contains('\t')) "\"$path\"" else path

    private fun findEnvRelativePath(base: File): String? {
        for (name in listOf(".env", ".env.local", ".env.development", ".env.production")) {
            if (File(base, name).isFile) {
                return name
            }
        }
        return null
    }

    /** Relative path from project root, forward slashes (works as a shell command token on Unix; Windows accepts `/` in many cases). */
    private fun detectVenvPythonRelative(base: File): String? {
        val unix = listOf(
            ".venv/bin/python3",
            ".venv/bin/python",
            "venv/bin/python3",
            "venv/bin/python",
            "env/bin/python3",
            "env/bin/python",
        )
        val windows = listOf(
            ".venv/Scripts/python.exe",
            "venv/Scripts/python.exe",
            "env/Scripts/python.exe",
        )
        val rels = if (SystemInfo.isWindows) windows else unix
        for (rel in rels) {
            val normalized = rel.replace('/', File.separatorChar)
            if (File(base, normalized).isFile) {
                return rel.replace('\\', '/')
            }
        }
        return null
    }
}
