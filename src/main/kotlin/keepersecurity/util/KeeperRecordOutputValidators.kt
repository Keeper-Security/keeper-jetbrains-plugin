package keepersecurity.util

/**
 * Pure-function output validators for Keeper record-creation and record-update
 * commands. Lives in `util/` so it can be unit-tested without spinning up
 * IntelliJ test infrastructure and so the same matching logic runs for both
 * the classic (`record-add` / `record-update`) and Nested Shared Folders
 * (`nsf-record-add` / `nsf-record-update`) namespaces.
 *
 * The validators are *advisory* — they return `true` when the output looks
 * like a real success result and `false` for transient sync banners,
 * BreachWatch hints, CLI error strings, and `nsf-record-add`'s warning-aborted
 * output. The caller (`KeeperCommandUtils.executeCommandWithRetry`) re-runs
 * the command on `false` and ultimately throws after `maxRetries` attempts.
 *
 * These heuristics intentionally err on the side of *rejecting* questionable
 * output: a false negative just costs a retry, while a false positive ends up
 * inserting a `keeper://` reference to a record that may not actually exist.
 */
object KeeperRecordOutputValidators {

    /** Length and shape of a Keeper record UID (Base64-URL-safe, 22 chars). */
    private val UID_PATTERN = Regex("""[A-Za-z0-9_-]{22}""")

    /**
     * Sync banner / chatter that Commander emits while it's still settling its
     * decryption cache after login. Hitting any of these strings means we
     * should retry — the real command result is just about to follow.
     */
    private val SYNC_BANNER_MARKERS = listOf(
        "Decrypted [",
        "synced record(s)",
        "breachwatch list",
        "Use \"breachwatch list\" command",
    )

    /**
     * Error markers that the CLI prints for explicit failure cases (auth,
     * validation, command typos, nsf-* warnings aborting the create). Simple
     * substring markers are case-insensitive; [INVALID_PHRASE_PATTERNS] use
     * word boundaries so success lines like "skipped: 2 invalid fields" are
     * not treated as failures.
     */
    private val ERROR_MARKERS = listOf(
        "error",
        "failed",
        "not found",
        // `nsf-record-add` aborts on attachment / unknown-field warnings unless
        // `-f` is supplied; the abort line begins with "Warning:" or "Aborted".
        "warning:",
        "aborted",
        // Older Commander builds that pre-date Nested Shared Folders reject
        // the nsf-* namespace with one of these phrases.
        "unknown command",
        "no such command",
        "command not found",
    )

    private val INVALID_PHRASE_PATTERNS = listOf(
        Regex("""invalid record\b""", RegexOption.IGNORE_CASE),
        Regex("""invalid uid\b""", RegexOption.IGNORE_CASE),
        Regex("""invalid field\b""", RegexOption.IGNORE_CASE),
        Regex("""invalid command\b""", RegexOption.IGNORE_CASE),
        Regex("""invalid folder\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Subset of error markers that are *definitely* unrecoverable — retrying
     * the same command will produce the same failure. Used by
     * [isFatalError] so the retry loop can short-circuit instead of burning
     * `maxRetries` attempts on a Commander state that won't change.
     *
     * "An unexpected error occurred" is Commander's catch-all wrapper for
     * Python exceptions that escape a command handler. Once we see it, the
     * underlying problem is on the user's CLI install (e.g. missing module,
     * incompatible build) and no amount of retrying will fix it within the
     * same Commander session.
     */
    private val FATAL_ERROR_MARKERS = listOf(
        "an unexpected error occurred",
        "no module named",
        "modulenotfounderror",
        "unknown command",
        "no such command",
        "command not found",
        "not logged in",
        "you are not logged in"
    )

    /**
     * True when the [output] of a `record-add` / `nsf-record-add` call contains
     * a freshly created record UID and is not a sync banner or error string.
     */
    fun isRecordAddSuccess(output: String): Boolean {
        if (output.isBlank()) return false
        val hasUid = UID_PATTERN.containsMatchIn(output)
        return hasUid && !isSyncBanner(output) && !looksLikeError(output)
    }

    /**
     * True when the [output] of a `record-update` / `nsf-record-update` call
     * looks like a successful operation. Both classic and Nested Shared Folder
     * Subfolders commands frequently return empty output on success, so the
     * validator's job here is mostly to *reject* sync banners and error
     * strings — empty output is accepted by default.
     */
    fun isRecordUpdateSuccess(output: String): Boolean =
        !isSyncBanner(output) && !looksLikeError(output)

    /** Returns true when the output is dominated by Commander's startup chatter. */
    fun isSyncBanner(output: String): Boolean =
        SYNC_BANNER_MARKERS.any { output.contains(it) }

    /** Returns true when the output contains any of the known error markers. */
    fun looksLikeError(output: String): Boolean =
        ERROR_MARKERS.any { output.contains(it, ignoreCase = true) } ||
            INVALID_PHRASE_PATTERNS.any { it.containsMatchIn(output) }

    /**
     * Returns true when the output looks like an unrecoverable Commander
     * failure — typically a Python exception escaping a command handler, a
     * missing module, or an unknown / disabled command. Used by the retry
     * loop to bail early instead of repeating the same broken call.
     */
    fun isFatalError(output: String): Boolean =
        FATAL_ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }

    /**
     * Trim Commander output down to a single human-readable line suitable for
     * surfacing in a `Messages.showErrorDialog`. Drops common prompt strings
     * and the verbose "Type "debug" to toggle verbose error output" trailer.
     */
    fun summariseError(output: String): String {
        val trimmed = output
            .replace("My Vault>", "")
            .replace("Keeper>", "")
            .replace("Not logged in>", "")
            .replace("Type \"debug\" to toggle verbose error output", "")
            .replace("Type 'debug' to toggle verbose error output", "")
            .lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: output.trim()
        return trimmed.take(300)
    }

    /**
     * Extract the first Keeper record UID found in [output], or null when no
     * 22-character UID-shaped token is present. Used by record-creation flows
     * to surface the new UID in the editor reference.
     */
    fun extractRecordUid(output: String): String? = UID_PATTERN.find(output)?.value
}
