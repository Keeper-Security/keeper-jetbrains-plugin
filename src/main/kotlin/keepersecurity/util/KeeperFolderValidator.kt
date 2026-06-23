package keepersecurity.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import keepersecurity.model.KeeperFolder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Verifies that a previously-saved Keeper folder UUID still corresponds
 * to a real folder in the user's vault before we issue
 * `record-add --folder=<uuid>` / `nsf-record-add --folder <uuid>` with it.
 *
 * The validator deliberately re-uses the *same* `ls --format=json -f -R`
 * call that [keepersecurity.action.KeeperFolderSelectAction] makes when
 * populating the picker. Using one source of truth means:
 *
 *  1. A folder visible in the picker is also "valid" to the validator —
 *     no risk of the two sides disagreeing on what "exists" means.
 *  2. We don't need a per-folder `get <uuid> --format json` round-trip,
 *     which would also need a custom parser since Commander's folder-`get`
 *     JSON doesn't match its record-`get` JSON shape (see KeeperJsonUtils
 *     heuristics — they assume record fields).
 *  3. The classic ↔ Nested Shared Folder *category* of each folder is already
 *     attached to the picker payload via [KeeperFolder.isKeeperDrive], so
 *     we can also detect "saved as classic but is actually a Nested Shared Folder
 *     folder" mismatches in the same pass.
 */
@OptIn(ExperimentalSerializationApi::class)
object KeeperFolderValidator {

    /** Which slot we're validating. */
    enum class ExpectedKind { CLASSIC, DRIVE }

    /** Outcome of a single [verifySavedFolder] / [verifyBlocking] call. */
    sealed class Verdict {
        /** UUID is still present and matches the expected slot. */
        data class Valid(
            val uuid: String,
            val name: String,
            val isDrive: Boolean
        ) : Verdict()

        /** UUID is not present in the current `ls` payload. */
        object Missing : Verdict()

        /**
         * UUID exists but lives in the *other* vault — e.g. the project
         * has a classic UUID saved but the folder is actually a Keeper
         * Drive folder. The caller should clear the stale slot.
         */
        data class Mismatch(
            val actualKind: ExpectedKind,
            val name: String
        ) : Verdict()

        /**
         * The CLI lookup itself failed (shell unhealthy, network down,
         * etc.). Callers should usually fail-soft and let the downstream
         * `record-add` raise the real error rather than wiping a possibly
         * valid UUID just because the IDE couldn't reach Commander right
         * now.
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
     * Run the verification under a small modal progress dialog so the EDT
     * stays responsive while Commander walks the vault. Safe to call from
     * the EDT.
     *
     * @param project   Anchor project for the progress modal.
     * @param uuid      The UUID we have saved in [PropertiesComponent].
     * @param expected  Which slot it was saved under.
     */
    fun verifySavedFolder(project: Project, uuid: String, expected: ExpectedKind): Verdict {
        val verdictHolder = arrayOf<Verdict>(Verdict.Unknown("not run"))
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                val indicator = ProgressManager.getInstance().progressIndicator
                indicator?.text = "Refreshing Keeper vault\u2026"
                verdictHolder[0] = verifyBlocking(uuid, expected) { phase ->
                    indicator?.text = phase
                }
            },
            "Verifying saved Keeper folder\u2026",
            false,
            project
        )
        return verdictHolder[0]
    }

    /**
     * Blocking variant. Intended for non-UI callers (e.g. unit tests or
     * code already running on a background thread). The body must not be
     * called from the EDT — it issues a synchronous `sync-down` + `ls`
     * against the persistent shell.
     *
     * **A `sync-down` is required before the `ls`**: without it the
     * plugin's persistent shell happily lists a folder that was deleted
     * from another session (e.g. the user's standalone `keeper shell`
     * terminal), then the *Add* dispatch issues `record-add --folder`
     * with that ghost UUID — Commander reports local "success" but
     * nothing materialises in the actual vault. The cost is one extra
     * round-trip per Add / Generate, which the existing
     * `KeeperFolderSelectAction` already accepts when it populates the
     * picker.
     *
     * @param onPhase optional progress hook; invoked with a short
     *                human-readable status string before each phase
     *                (sync, then ls).
     */
    internal fun verifyBlocking(
        uuid: String,
        expected: ExpectedKind,
        onPhase: (String) -> Unit = {}
    ): Verdict {
        if (uuid.isBlank()) return Verdict.Unknown("empty uuid")

        onPhase("Refreshing Keeper vault\u2026")
        KeeperCommandUtils.syncDownBestEffort(logger)

        onPhase("Verifying saved Keeper folder\u2026")
        val output = try {
            KeeperCommandUtils.executeCommandWithRetry(
                "ls --format=json -f -R",
                KeeperCommandUtils.Presets.jsonArray(maxRetries = 2, timeoutSeconds = 60),
                logger
            )
        } catch (ex: Exception) {
            logger.warn("Could not fetch folder list while verifying saved UUID '$uuid': ${ex.message}")
            return Verdict.Unknown(ex.message ?: ex.javaClass.simpleName)
        }

        val folders = try {
            val jsonText = KeeperJsonUtils.extractJsonArray(output, logger)
            json.decodeFromString<List<KeeperFolder>>(jsonText)
        } catch (ex: Exception) {
            logger.warn("Could not parse folder list while verifying saved UUID '$uuid': ${ex.message}")
            return Verdict.Unknown(ex.message ?: ex.javaClass.simpleName)
        }

        return classify(folders, uuid, expected)
    }

    /**
     * Pure classifier extracted so the parsing-only branch can be tested
     * without spinning up [KeeperShellService] / [ProgressManager].
     */
    internal fun classify(
        folders: List<KeeperFolder>,
        uuid: String,
        expected: ExpectedKind
    ): Verdict {
        val match = folders.firstOrNull { it.folderUid == uuid } ?: return Verdict.Missing
        val actual = if (match.isKeeperDrive) ExpectedKind.DRIVE else ExpectedKind.CLASSIC
        return if (actual == expected) {
            Verdict.Valid(uuid = uuid, name = match.name, isDrive = actual == ExpectedKind.DRIVE)
        } else {
            Verdict.Mismatch(actualKind = actual, name = match.name)
        }
    }
}
