package keepersecurity.util

import com.intellij.openapi.diagnostic.Logger
import keepersecurity.model.KeeperCliFlavor
import keepersecurity.model.KeeperCliInstall
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds every working Keeper Commander / Keeper SMK CLI on this machine.
 *
 * Search order (earlier sources win when two candidates are the same install):
 *  1. `keeper-smk` in [Environment.pathDirs]
 *  2. `<home>/.keeper-smk/<host>/bin/keeper-smk` for each home in [Environment.homeDirs]
 *     (not on PATH by default, and an IDE launched from the Dock or Start menu
 *     usually doesn't inherit the shell PATH anyway)
 *  3. `keeper` in [Environment.pathDirs]
 *  4. [Environment.knownCommanderPaths] (Python user/framework/Homebrew dirs)
 *  5. [Environment.pythonModuleCommands]: only if no Commander executable was found
 *
 * Each candidate must run `<cli> --version` successfully with output containing
 * "Keeper Commander". The flavor comes from the name and location:
 *  - anything that resolves (after symlinks) into `~/.keeper-smk/<host>/` is SMK
 *    for that host. It's always executed through that host's `bin/keeper-smk`
 *    wrapper, never the venv's `keeper` directly, because the wrapper sets the
 *    server-specific environment. A candidate whose host has no wrapper is skipped.
 *  - any other executable named `keeper-smk` is SMK with an unknown host
 *  - everything else is Commander
 *
 * Installs are deduplicated by symlink-resolved path, so `keeper -> keeper-smk`
 * shows up once, as SMK.
 */
class KeeperCliDetector(
    private val env: Environment,
    private val versionProbe: (List<String>) -> String? = { probeVersion(it) },
) {
    data class Environment(
        val isWindows: Boolean,
        val homeDirs: List<File>,
        val pathDirs: List<File>,
        val knownCommanderPaths: List<String> = emptyList(),
        val pythonModuleCommands: List<String> = emptyList(),
    )

    private val logger = Logger.getInstance(KeeperCliDetector::class.java)

    fun detectAll(): List<KeeperCliInstall> {
        val found = LinkedHashMap<String, KeeperCliInstall>()
        val smkNames = KeeperCliFlavor.SMK.executableNames(env.isWindows)
        val commanderNames = KeeperCliFlavor.COMMANDER.executableNames(env.isWindows)

        for (dir in env.pathDirs) for (name in smkNames) consider(File(dir, name), found)

        for (home in env.homeDirs.distinctBy { it.absolutePath }) {
            val root = File(home, KeeperCliFlavor.SMK_ROOT_DIR_NAME)
            val hostDirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            for (hostDir in hostDirs) for (name in smkNames) consider(File(hostDir, "bin/$name"), found)
        }

        for (dir in env.pathDirs) for (name in commanderNames) consider(File(dir, name), found)
        for (path in env.knownCommanderPaths) consider(File(path), found)

        if (found.values.none { it.flavor == KeeperCliFlavor.COMMANDER }) {
            for (cmd in env.pythonModuleCommands) {
                val output = versionProbe(cmd.split(" ").filter { it.isNotBlank() } + "--version") ?: continue
                val install = KeeperCliInstall(
                    path = cmd,
                    canonicalPath = cmd,
                    flavor = KeeperCliFlavor.COMMANDER,
                    version = parseVersion(output),
                    isModule = true,
                )
                found[install.id] = install
                break
            }
        }

        logger.info("Keeper CLI detection found ${found.size} install(s): " +
            found.values.joinToString { "${it.displayLabel()} at ${it.path}" })
        return found.values.toList()
    }

    private fun consider(candidate: File, found: MutableMap<String, KeeperCliInstall>) {
        if (!candidate.isFile || !candidate.canExecute()) return
        val absolute = candidate.absoluteFile
        val canonical = runCatching { candidate.canonicalFile }.getOrDefault(absolute)

        val smkLocation = smkLocation(canonical) ?: smkLocation(absolute)
        val (execFile, flavor, host) = if (smkLocation != null) {
            val (hostDir, hostName) = smkLocation
            val wrapper = KeeperCliFlavor.SMK.executableNames(env.isWindows)
                .map { File(hostDir, "bin/$it") }
                .firstOrNull { it.isFile && it.canExecute() }
            if (wrapper == null) {
                logger.warn("Skipping $candidate: it belongs to SMK host '$hostName' but that host has no " +
                    "bin/keeper-smk wrapper, and running the venv binary directly would use the wrong server.")
                return
            }
            Triple(wrapper.absoluteFile, KeeperCliFlavor.SMK, hostName)
        } else {
            val isSmkName = isSmkExecutableName(absolute.name) || isSmkExecutableName(canonical.name)
            Triple(absolute, if (isSmkName) KeeperCliFlavor.SMK else KeeperCliFlavor.COMMANDER, null)
        }

        val execCanonical = runCatching { execFile.canonicalPath }.getOrDefault(execFile.path)
        if (found.containsKey(execCanonical)) return

        val output = versionProbe(listOf(execFile.path, "--version"))
        if (output == null) {
            logger.info("Ignoring $candidate: '--version' failed or isn't Keeper Commander")
            return
        }
        found[execCanonical] = KeeperCliInstall(
            path = execFile.path,
            canonicalPath = execCanonical,
            flavor = flavor,
            version = parseVersion(output),
            smkHost = host,
        )
    }

    /**
     * If [file] lives under `.../.keeper-smk/<host>/...`, return that host
     * directory and its name.
     */
    private fun smkLocation(file: File): Pair<File, String>? {
        var child: File = file
        var parent: File? = file.parentFile
        while (parent != null) {
            if (parent.name == KeeperCliFlavor.SMK_ROOT_DIR_NAME && child != file) {
                return child to child.name
            }
            child = parent
            parent = parent.parentFile
        }
        return null
    }

    /** `keeper-smk`, `keeper-smk.exe`, `keeper-smk.cmd`, ... */
    private fun isSmkExecutableName(name: String): Boolean =
        name.substringBeforeLast('.', name).equals(KeeperCliFlavor.SMK.baseName, ignoreCase = true)

    companion object {
        private val VERSION_REGEX = Regex("""version\s+([0-9][0-9A-Za-z.\-+]*)""", RegexOption.IGNORE_CASE)

        internal fun parseVersion(output: String): String? = VERSION_REGEX.find(output)?.groupValues?.get(1)

        /** Detector configured for the machine the IDE runs on. */
        fun forCurrentSystem(): KeeperCliDetector = KeeperCliDetector(currentEnvironment())

        private fun currentEnvironment(): Environment {
            val osName = System.getProperty("os.name").lowercase()
            val isWindows = osName.contains("windows")
            val isMacOS = osName.contains("mac")
            val username = System.getProperty("user.name")
            val userHome = File(System.getProperty("user.home"))

            // Under sudo, user.home is root's home but SMK is usually installed
            // in the invoking user's home, so scan both.
            val homeDirs = buildList {
                add(userHome)
                System.getenv("SUDO_USER")?.takeIf { it.isNotBlank() && it != username }?.let { sudoUser ->
                    add(File(if (isMacOS) "/Users/$sudoUser" else "/home/$sudoUser"))
                }
            }

            val pathDirs = buildList {
                System.getenv("PATH")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.forEach { add(File(it)) }
                // Dirs a Dock-launched IDE often lacks on PATH.
                if (!isWindows) {
                    listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "$userHome/.local/bin")
                        .forEach { add(File(it)) }
                }
            }.distinctBy { it.absolutePath }

            val knownCommanderPaths = when {
                isWindows -> listOf(
                    "C:\\Python39\\Scripts\\keeper.exe",
                    "C:\\Python310\\Scripts\\keeper.exe",
                    "C:\\Python311\\Scripts\\keeper.exe",
                    "C:\\Python312\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Local\\Programs\\Python\\Python39\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Local\\Programs\\Python\\Python310\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Local\\Programs\\Python\\Python311\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Roaming\\Python\\Python39\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Roaming\\Python\\Python310\\Scripts\\keeper.exe",
                )
                isMacOS -> listOf(
                    "/Users/$username/Library/Python/3.9/bin/keeper",
                    "/Users/$username/Library/Python/3.10/bin/keeper",
                    "/Users/$username/Library/Python/3.11/bin/keeper",
                    "/Users/$username/Library/Python/3.12/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.9/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.10/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.11/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.12/bin/keeper",
                    "/opt/homebrew/opt/python@3.9/bin/keeper",
                    "/opt/homebrew/opt/python@3.10/bin/keeper",
                    "/opt/homebrew/opt/python@3.11/bin/keeper",
                    "/opt/homebrew/opt/python@3.12/bin/keeper",
                )
                else -> listOf("/opt/keeper/bin/keeper")
            }

            val pythonModuleCommands = when {
                isMacOS -> listOf(
                    "python3 -m keepercommander",
                    "python -m keepercommander",
                    "/usr/bin/python3 -m keepercommander",
                    "/opt/homebrew/bin/python3 -m keepercommander",
                    "/Library/Frameworks/Python.framework/Versions/3.9/bin/python3 -m keepercommander",
                    "/Library/Frameworks/Python.framework/Versions/3.11/bin/python3 -m keepercommander",
                )
                isWindows -> listOf(
                    "python -m keepercommander",
                    "python3 -m keepercommander",
                    "py -m keepercommander",
                    "C:\\Python39\\python.exe -m keepercommander",
                    "C:\\Python310\\python.exe -m keepercommander",
                )
                else -> listOf("python3 -m keepercommander", "python -m keepercommander")
            }

            return Environment(isWindows, homeDirs, pathDirs, knownCommanderPaths, pythonModuleCommands)
        }

        /**
         * Runs `<cli> --version`. Returns the output if it exited 0 and looks like
         * Commander (SMK prints the same banner), otherwise null.
         */
        internal fun probeVersion(command: List<String>): String? = try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                val output = process.inputStream.bufferedReader().readText()
                output.takeIf { process.exitValue() == 0 && it.contains("Keeper Commander", ignoreCase = true) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
