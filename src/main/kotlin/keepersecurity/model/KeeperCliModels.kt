package keepersecurity.model

/**
 * Which Keeper Commander distribution a detected CLI belongs to.
 *
 *  - [COMMANDER]: the standard Commander (`pip install keepercommander` or the
 *    native installer), invoked as `keeper`.
 *  - [SMK]: Keeper SMK. Its deployment's `setup.sh` installs a per-server
 *    wrapper at `~/.keeper-smk/<host>/bin/keeper-smk`. The wrapper exports the
 *    server-specific environment (`KEEPER_DATA_HOME`, `ROUTER_URL`, ...) and then
 *    execs the venv's `keeper`. `<host>` can be any enterprise domain or `localhost`.
 *
 * Both print the same `--version` banner ("Keeper Commander, version X") and expose
 * the same shell, so the flavor is decided by executable name and install location
 * (see [KeeperCliDetector]), never by version text.
 */
enum class KeeperCliFlavor(val displayName: String, val baseName: String) {
    COMMANDER("Keeper Commander", "keeper"),
    SMK("Keeper SMK", "keeper-smk");

    /** File names to look for on disk. On Windows only real executables/scripts count. */
    fun executableNames(isWindows: Boolean): List<String> =
        if (isWindows) listOf("$baseName.exe", "$baseName.cmd", "$baseName.bat") else listOf(baseName)

    companion object {
        /** Directory that SMK's installer creates in the user's home. */
        const val SMK_ROOT_DIR_NAME = ".keeper-smk"
    }
}

/**
 * One working Keeper CLI found on this machine.
 *
 * @property path what the plugin executes: an absolute file path, or a
 *   space-separated Python module command such as `python3 -m keepercommander`
 *   when [isModule] is true.
 * @property canonicalPath symlink-resolved path, used to deduplicate installs.
 * @property smkHost the `<host>` directory under `~/.keeper-smk/` (any string:
 *   `localhost`, an enterprise domain, an IP, `host:port`). Null for Commander
 *   or for an SMK binary installed outside `~/.keeper-smk`.
 */
data class KeeperCliInstall(
    val path: String,
    val canonicalPath: String,
    val flavor: KeeperCliFlavor,
    val version: String?,
    val isModule: Boolean = false,
    val smkHost: String? = null,
) {
    /** Stable identity used to persist the user's choice (see [KeeperCliSelection]). */
    val id: String get() = if (isModule) "module:$path" else canonicalPath

    /** Full argv for running this CLI with [args]. */
    fun commandLine(vararg args: String): List<String> =
        (if (isModule) path.split(" ").filter { it.isNotBlank() } else listOf(path)) + args

    /** Short label for dialogs, e.g. `Keeper SMK: keeper.example.com (18.1.8)`. */
    fun displayLabel(): String = buildString {
        append(flavor.displayName)
        if (!smkHost.isNullOrBlank()) append(": ").append(smkHost)
        if (!version.isNullOrBlank()) append(" (").append(version).append(")")
    }

    /**
     * Command to show the user for terminal steps such as login. The SMK wrapper
     * is usually not on PATH, so it's given as a full path.
     */
    fun terminalCommand(): String = when {
        isModule -> path
        flavor == KeeperCliFlavor.SMK -> if (path.contains(' ')) "\"$path\"" else path
        else -> "keeper"
    }
}
