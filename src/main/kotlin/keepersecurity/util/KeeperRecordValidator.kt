package keepersecurity.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import keepersecurity.model.KeeperRecord
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Looks up a user-supplied record UID in the current vault to decide
 * whether `record-update` (classic) or `nsf-record-update` (Nested Shared Folders)
 * is the right CLI command. Replaces the *Update Keeper Record* Classic vs
 * Nested Shared Folder prompt — the user only enters the UID, the
 * plugin works out the rest.
 *
 * Uses the same `list --format json` payload that
 * [keepersecurity.action.KeeperGetSecretAction] consumes when populating
 * its picker: each row carries a `record_category` discriminator
 * (`Classic` / `Nested`, plus older legacy wire values) that maps directly to the right
 * command, and the payload is the only source of truth that
 * empirically includes that field. We could try a single `get <uid>
 * --format json` round-trip for speed, but the get-record JSON shape
 * isn't guaranteed to include `record_category` on every Commander
 * release, whereas `list --format json` does.
 */
@OptIn(ExperimentalSerializationApi::class)
object KeeperRecordValidator {

    /** Which command we should dispatch to. */
    enum class Kind { CLASSIC, DRIVE }

    /** Outcome of [lookupRecord] / [lookupBlocking]. */
    sealed class Verdict {
        /** UID found; [kind] tells the caller which update command to use. */
        data class Found(
            val uuid: String,
            val kind: Kind,
            val title: String
        ) : Verdict()

        /** UID not present in the current vault listing. */
        object NotFound : Verdict()

        /**
         * The CLI lookup itself failed (shell unhealthy, network down,
         * etc.). Callers should usually surface the reason to the user
         * rather than guess the namespace.
         */
        data class Unknown(val reason: String) : Verdict()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    private val logger: Logger = thisLogger()

    /**
     * Run the lookup under a small modal progress dialog so the EDT
     * stays responsive while Commander walks the vault. Safe to call
     * from the EDT.
     *
     * @param project Anchor project for the progress modal.
     * @param uuid    The UID the user typed in the *Record UID* prompt.
     */
    fun lookupRecord(project: Project, uuid: String): Verdict {
        val verdictHolder = arrayOf<Verdict>(Verdict.Unknown("not run"))
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                val indicator = ProgressManager.getInstance().progressIndicator
                indicator?.text = "Refreshing Keeper vault\u2026"
                verdictHolder[0] = lookupBlocking(uuid) { phase -> indicator?.text = phase }
            },
            "Looking up Keeper record\u2026",
            false,
            project
        )
        return verdictHolder[0]
    }

    /**
     * Blocking variant. Intended for non-UI callers (e.g. unit tests or
     * code already running on a background thread). The body must not
     * be called from the EDT — it issues a synchronous `sync-down` +
     * `list` against the persistent shell.
     *
     * The `sync-down` is required for the same reason the folder
     * validator needs it (see [KeeperFolderValidator.verifyBlocking]):
     * without it the persistent shell happily lists records that were
     * deleted from another session, and we'd dispatch into a
     * Commander-side phantom that reports local success but no vault
     * change.
     *
     * @param onPhase optional progress hook; invoked with a short
     *                human-readable status string before each phase
     *                (sync, then list).
     */
    internal fun lookupBlocking(
        uuid: String,
        onPhase: (String) -> Unit = {}
    ): Verdict {
        if (uuid.isBlank()) return Verdict.NotFound

        onPhase("Refreshing Keeper vault\u2026")
        KeeperCommandUtils.syncDownBestEffort(logger)

        onPhase("Looking up Keeper record\u2026")
        val output = try {
            KeeperCommandUtils.executeCommandWithRetry(
                "list --format json",
                KeeperCommandUtils.Presets.jsonArray(maxRetries = 2, timeoutSeconds = 60),
                logger
            )
        } catch (ex: Exception) {
            logger.warn("Could not fetch record list while looking up UID '$uuid': ${ex.message}")
            return Verdict.Unknown(ex.message ?: ex.javaClass.simpleName)
        }

        val records = try {
            val jsonText = KeeperJsonUtils.extractJsonArray(output, logger)
            json.decodeFromString<List<KeeperRecord>>(jsonText)
        } catch (ex: Exception) {
            logger.warn("Could not parse record list while looking up UID '$uuid': ${ex.message}")
            return Verdict.Unknown(ex.message ?: ex.javaClass.simpleName)
        }

        return classify(records, uuid)
    }

    /**
     * Pure classifier extracted so the parsing-only branch can be
     * tested without spinning up [KeeperShellService] / [ProgressManager].
     */
    internal fun classify(records: List<KeeperRecord>, uuid: String): Verdict {
        val match = records.firstOrNull { it.recordUid == uuid } ?: return Verdict.NotFound
        val kind = if (match.isKeeperDrive) Kind.DRIVE else Kind.CLASSIC
        return Verdict.Found(
            uuid = uuid,
            kind = kind,
            title = match.title.ifBlank { uuid }
        )
    }
}
