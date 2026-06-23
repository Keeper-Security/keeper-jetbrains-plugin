package keepersecurity.util

/**
 * CLI-safety helpers shared by every action that pipes user-controlled
 * strings into the persistent Commander shell.
 *
 * The persistent shell reads stdin one line at a time, so an embedded
 * `\n`, `\r`, or `\x00` in any concatenated argument splits the write
 * into two (or more) Commander commands. We refuse those characters at
 * the input boundary and again at the shell-write boundary as
 * defense-in-depth.
 */
object KeeperCliSafety {

    /**
     * Keeper record UID in `keeper://<uid>/field/...` notation: URL-safe
     * Base64, exactly 22 characters ([A-Za-z0-9_-]).
     */
    val KEEPER_RECORD_UID: Regex = Regex("""^[A-Za-z0-9_-]{22}$""")

    /** Characters that terminate or null-break a Commander command line. */
    private const val FORBIDDEN = "\r\n\u0000"

    /** True when [uid] matches [KEEPER_RECORD_UID]. */
    fun isValidRecordUid(uid: String): Boolean = KEEPER_RECORD_UID.matches(uid)

    /** Escape `\` and `"` before embedding a value in a double-quoted CLI argument. */
    fun escapeDoubleQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Escape `'` for embedding in a single-quoted CLI argument (POSIX shell rules). */
    fun escapeSingleQuoted(value: String): String =
        value.replace("'", "'\\''")

    /**
     * Thrown when a user-supplied string or an assembled command
     * contains a forbidden control character. The message is suitable
     * for surfacing in `Messages.showErrorDialog`.
     */
    class UnsafeCliInputException(message: String) : RuntimeException(message)

    /**
     * Validate a user-supplied value before it gets concatenated into a
     * Commander command. Returns the value unchanged on success, throws
     * [UnsafeCliInputException] when the value contains `\r`, `\n`, or
     * `\x00`.
     */
    fun requireSafe(value: String, fieldLabel: String): String {
        if (value.any { it in FORBIDDEN }) {
            throw UnsafeCliInputException(
                "The $fieldLabel contains a newline or null byte. " +
                    "Keeper records do not support multi-line values from this " +
                    "action — please remove line breaks (and any other control " +
                    "characters) and try again. For multi-line content use the " +
                    "Keeper web vault or the `note` field type."
            )
        }
        return value
    }

    /**
     * Last-line-of-defense check at the shell-write boundary. Refuses
     * to write any string that still contains a forbidden character at
     * the point where it would reach `keeper shell` stdin.
     */
    fun assertSingleLine(command: String) {
        if (command.any { it in FORBIDDEN }) {
            throw UnsafeCliInputException(
                "Refusing to write a multi-line command to the Keeper shell. " +
                    "This is a safety check; an upstream input boundary should " +
                    "have already rejected this value."
            )
        }
    }
}
