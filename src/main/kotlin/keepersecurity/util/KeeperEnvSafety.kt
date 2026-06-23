package keepersecurity.util

import com.intellij.ide.util.PropertiesComponent

/**
 * Safety checks for **Run Keeper Securely**: vault-backed values are merged
 * into the spawned process environment via [ProcessBuilder], so both the
 * `.env` key and the fetched field value must be validated before injection.
 *
 * Shared Keeper records can supply malicious field content (e.g.
 * `--require=./pwn.js` in a Notes field referenced as `NODE_OPTIONS`), which
 * interpreters treat as code-load hooks. We block known hook variable names
 * and reject values that look like interpreter directives or shell injection.
 */
object KeeperEnvSafety {

    const val FIRST_RUN_WARNING_PROPERTY = "keeper.runSecurely.envWarningShown"

    const val FIRST_RUN_WARNING_MESSAGE =
        "Run Keeper Securely resolves keeper:// references from your .env file " +
            "and injects the fetched values into the environment of the command you run.\n\n" +
            "Only reference Keeper records you trust. Secrets from shared folders " +
            "can influence what runs in your process.\n\n" +
            "Interpreter hook variables (NODE_OPTIONS, LD_PRELOAD, PYTHONPATH, etc.) " +
            "are blocked automatically."

    private val EXACT_BLOCKED_KEYS = setOf(
        "BASH_ENV",
        "BROWSER",
        "EDITOR",
        "ENV",
        "GIT_EXTERNAL_DIFF",
        "GIT_SSH_COMMAND",
        "JAVA_TOOL_OPTIONS",
        "LD_LIBRARY_PATH",
        "LD_PRELOAD",
        "MANPAGER",
        "NODE_OPTIONS",
        "PAGER",
        "PERL5OPT",
        "PROMPT_COMMAND",
        "PS0",
        "PS1",
        "PS2",
        "PS4",
        "PYTHONPATH",
        "PYTHONSTARTUP",
        "RUBYOPT",
        "VISUAL",
        "_JAVA_OPTIONS",
    )

    /** Characters that must not appear in injected env values. */
    private const val FORBIDDEN_VALUE_CHARS = "\r\n\u0000"

    sealed class Verdict {
        data class Allowed(val key: String, val value: String) : Verdict()
        data class Blocked(val key: String, val reason: String) : Verdict()
    }

    fun shouldShowFirstRunWarning(): Boolean =
        !PropertiesComponent.getInstance().getBoolean(FIRST_RUN_WARNING_PROPERTY, false)

    fun markFirstRunWarningShown() {
        PropertiesComponent.getInstance().setValue(FIRST_RUN_WARNING_PROPERTY, true)
    }

    /**
     * Validate an env key from the `.env` file before fetching the Keeper secret.
     * Returns a human-readable rejection reason, or `null` when the key is allowed.
     */
    fun blockedEnvKeyReason(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "environment variable name is empty"

        val upper = trimmed.uppercase()
        if (upper in EXACT_BLOCKED_KEYS) {
            return "'$trimmed' is a blocked interpreter or shell hook variable"
        }
        if (upper.startsWith("DYLD_")) {
            return "'$trimmed' is a blocked dynamic-linker hook (DYLD_*)"
        }
        if (upper.endsWith("_OPTIONS")) {
            return "'$trimmed' matches the blocked *_OPTIONS hook pattern"
        }
        if (upper.endsWith("_DEBUGGER")) {
            return "'$trimmed' matches the blocked *_DEBUGGER hook pattern"
        }
        return null
    }

    /**
     * Validate a fetched vault value before it is written into the process env.
     * Returns a human-readable rejection reason, or `null` when the value is allowed.
     */
    fun blockedEnvValueReason(value: String): String? {
        if (value.any { it in FORBIDDEN_VALUE_CHARS }) {
            return "value contains control characters (newline or null byte)"
        }
        if (value.startsWith("-")) {
            return "value looks like an interpreter flag (starts with '-')"
        }
        if (value.contains("$(")) {
            return "value contains command substitution (\$(...))"
        }
        if (value.contains('`')) {
            return "value contains command substitution (backticks)"
        }
        return null
    }

    /**
     * Combined key + value check used at injection time.
     */
    fun validateForInjection(key: String, value: String): Verdict {
        blockedEnvKeyReason(key)?.let { return Verdict.Blocked(key, it) }
        blockedEnvValueReason(value)?.let { return Verdict.Blocked(key, it) }
        return Verdict.Allowed(key, value)
    }
}
