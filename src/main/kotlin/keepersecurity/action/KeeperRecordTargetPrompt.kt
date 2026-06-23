package keepersecurity.action

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import keepersecurity.util.KeeperFolderValidator

/**
 * Shared helper that resolves the vault target (Classic vs Nested Shared
 * Subfolder) for *Add Keeper Record* and *Generate Keeper Secret*.
 *
 * Keeper maintains two parallel folder/permission models — Classic and Nested
 * Shared Subfolders (v3 API, `nsf-*` commands). Both models have folders
 * and records; Commander tags folder rows with `source` and record rows with
 * `record_category`. Since *Get Keeper Folder* is now unified — picking a
 * folder writes exactly one of the Classic / Nested Shared Folder slots
 * and clears the other — the saved folder already encodes the user's intent. We validate the saved
 * UUID against the current `ls --format=json -f -R` payload via
 * [KeeperFolderValidator] and dispatch directly. If the saved folder
 * has been deleted, moved between vaults, or simply doesn't exist for
 * this account, we clear it and surface a single "run *Get Keeper
 * Folder* first" info dialog (no "use root" escape hatch — root
 * creation can be expressed by deliberately picking a top-level folder).
 *
 * The corresponding *Update Keeper Record* prompt was retired in favour
 * of [keepersecurity.util.KeeperRecordValidator], which looks up the
 * user-supplied record UID in `list --format json` and dispatches to
 * `record-update` vs `nsf-record-update` based on the row's
 * `record_category`.
 */
object KeeperRecordTargetPrompt {

    private val logger = thisLogger()

    /**
     * Preference keys (per-project).
     */
    const val PREF_DRIVE_FOLDER_UUID = "keeper.drive.folder.uuid"
    const val PREF_DRIVE_FOLDER_NAME = "keeper.drive.folder.name"
    const val PREF_CLASSIC_FOLDER_UUID = "keeper.folder.uuid"
    const val PREF_CLASSIC_FOLDER_NAME = "keeper.folder.name"

    /**
     * Outcome of [promptForAddTarget] / [promptForGenerateTarget].
     *
     * Both [Classic] and [Drive] carry the resolved folder UUID + display
     * name so the caller doesn't have to re-read `PropertiesComponent`
     * (and pick the wrong scope — see the project-scope vs app-scope
     * footgun fixed earlier).
     *
     * `folderUuid == null` is no longer reachable for Add / Generate
     * because we won't dispatch to those branches without a verified
     * folder — the nullability is preserved on the data class for API
     * compatibility with callers that build outcomes from tests.
     */
    sealed class AddOutcome {
        object Cancelled : AddOutcome()
        data class Classic(val folderUuid: String?, val folderName: String?) : AddOutcome()
        data class Drive(val folderUuid: String?, val folderName: String?) : AddOutcome()
    }

    /**
     * Resolve the target for an *Add Keeper Record* action.
     *
     * Auto-dispatches based on the saved folder slot. Returns
     * [AddOutcome.Cancelled] when no folder is saved (after showing a
     * single "run *Get Keeper Folder* first" info dialog) or when the
     * saved folder is stale (slot is cleared, same dialog is shown with
     * a prefix explaining what was cleared).
     *
     * Must be called on the EDT — both the validator's progress modal
     * and the info dialog require it.
     */
    fun promptForAddTarget(project: Project, dialogTitle: String = "Add Keeper Record"): AddOutcome =
        dispatchByFolder(project, dialogTitle)

    /**
     * Same dispatch logic as [promptForAddTarget]; separate signature for
     * call-site clarity in `KeeperGenerateSecretsAction`.
     */
    fun promptForGenerateTarget(project: Project, dialogTitle: String = "Generate Keeper Secret"): AddOutcome =
        dispatchByFolder(project, dialogTitle)

    /**
     * Resolve the Add / Generate dispatch based on the saved folder slot.
     * Drive slot wins if (somehow) both are set — in practice
     * `KeeperFolderSelectAction.persistFolderSelection` clears the other
     * side, so only one is populated at any time.
     */
    private fun dispatchByFolder(project: Project, dialogTitle: String): AddOutcome {
        val driveAttempt = tryDriveSlot(project)
        if (driveAttempt is SlotResult.Resolved) return driveAttempt.outcome

        val classicAttempt = tryClassicSlot(project)
        if (classicAttempt is SlotResult.Resolved) return classicAttempt.outcome

        // Either no slot was populated, or the populated slot was stale
        // and got cleared. Surface the stale reason if we have one — it
        // explains *why* the user is being told to re-pick.
        val stalePrefix = (driveAttempt as? SlotResult.Cleared)?.reason
            ?: (classicAttempt as? SlotResult.Cleared)?.reason

        showFolderPickerHint(
            project = project,
            dialogTitle = dialogTitle,
            stalePrefix = stalePrefix
        )
        return AddOutcome.Cancelled
    }

    /** What happened when we examined a slot. */
    private sealed class SlotResult {
        /** Slot was empty — nothing to do. */
        object Empty : SlotResult()

        /** Slot resolved to a valid folder; downstream should use it. */
        data class Resolved(val outcome: AddOutcome) : SlotResult()

        /**
         * Slot was populated but stale and has been cleared. [reason] is
         * a user-facing sentence that the caller can prepend to the
         * next dialog.
         */
        data class Cleared(val reason: String) : SlotResult()
    }

    private fun tryDriveSlot(project: Project): SlotResult {
        val props = PropertiesComponent.getInstance(project)
        val savedUuid = props.getValue(PREF_DRIVE_FOLDER_UUID)?.takeIf { it.isNotBlank() }
            ?: return SlotResult.Empty
        val savedName = props.getValue(PREF_DRIVE_FOLDER_NAME)?.takeIf { it.isNotBlank() }

        return when (val verdict = KeeperFolderValidator.verifySavedFolder(
            project = project,
            uuid = savedUuid,
            expected = KeeperFolderValidator.ExpectedKind.DRIVE
        )) {
            is KeeperFolderValidator.Verdict.Valid -> {
                logger.info("Auto-dispatching to Nested Shared Folder: '${verdict.name}' ($savedUuid)")
                SlotResult.Resolved(AddOutcome.Drive(folderUuid = savedUuid, folderName = verdict.name))
            }
            is KeeperFolderValidator.Verdict.Missing -> {
                logger.warn("Saved Nested Shared Folder '${savedName ?: savedUuid}' ($savedUuid) " +
                    "is no longer in the vault listing; clearing the saved slot")
                clearDriveFolder(project)
                SlotResult.Cleared(
                    "The previously selected Nested Shared Folder " +
                        "'${savedName ?: savedUuid}' is no longer available and has been cleared. "
                )
            }
            is KeeperFolderValidator.Verdict.Mismatch -> {
                logger.warn("Saved Nested Shared Folder UUID '$savedUuid' actually points to a Classic folder " +
                    "('${verdict.name}'); clearing the Nested Shared Folder slot")
                clearDriveFolder(project)
                SlotResult.Cleared(
                    "The folder previously saved as Nested Shared Folder ('${verdict.name}') " +
                        "is actually a Classic Vault folder. The Nested Shared Folder selection has been cleared. "
                )
            }
            is KeeperFolderValidator.Verdict.Unknown -> {
                // Fail-soft: a transient CLI hiccup shouldn't wipe a
                // possibly valid UUID. Let downstream `record-add` raise
                // the real error if the folder is genuinely gone.
                logger.warn("Could not verify saved Nested Shared Folder ($savedUuid): " +
                    "${verdict.reason}; proceeding with the saved value")
                SlotResult.Resolved(AddOutcome.Drive(folderUuid = savedUuid, folderName = savedName))
            }
        }
    }

    private fun tryClassicSlot(project: Project): SlotResult {
        val (savedUuid, savedName) = readSavedClassicFolder(project)
        if (savedUuid == null) return SlotResult.Empty

        return when (val verdict = KeeperFolderValidator.verifySavedFolder(
            project = project,
            uuid = savedUuid,
            expected = KeeperFolderValidator.ExpectedKind.CLASSIC
        )) {
            is KeeperFolderValidator.Verdict.Valid -> {
                logger.info("Auto-dispatching to classic folder: '${verdict.name}' ($savedUuid)")
                SlotResult.Resolved(AddOutcome.Classic(folderUuid = savedUuid, folderName = verdict.name))
            }
            is KeeperFolderValidator.Verdict.Missing -> {
                logger.warn("Saved classic folder '${savedName ?: savedUuid}' ($savedUuid) " +
                    "is no longer in the vault listing; clearing the saved slot")
                clearClassicFolder(project)
                SlotResult.Cleared(
                    "The previously selected Classic Vault folder " +
                        "'${savedName ?: savedUuid}' is no longer available and has been cleared. "
                )
            }
            is KeeperFolderValidator.Verdict.Mismatch -> {
                logger.warn("Saved classic UUID '$savedUuid' actually points to a Nested Shared Folder " +
                    "('${verdict.name}'); clearing the classic slot")
                clearClassicFolder(project)
                SlotResult.Cleared(
                    "The folder previously saved as Classic ('${verdict.name}') " +
                        "now lives in a Nested Shared Folder. The Classic selection has been cleared. "
                )
            }
            is KeeperFolderValidator.Verdict.Unknown -> {
                logger.warn("Could not verify saved classic folder ($savedUuid): " +
                    "${verdict.reason}; proceeding with the saved value")
                SlotResult.Resolved(AddOutcome.Classic(folderUuid = savedUuid, folderName = savedName))
            }
        }
    }

    /**
     * Wipe the classic folder slot from both the project-scope and the
     * legacy application-scope `PropertiesComponent`. The app-scope clear
     * mirrors the dual-read in [readSavedClassicFolder] so a stale legacy
     * value can't re-surface on the next prompt.
     */
    private fun clearClassicFolder(project: Project) {
        val projectProps = PropertiesComponent.getInstance(project)
        projectProps.unsetValue(PREF_CLASSIC_FOLDER_UUID)
        projectProps.unsetValue(PREF_CLASSIC_FOLDER_NAME)

        val appProps = PropertiesComponent.getInstance()
        appProps.unsetValue(PREF_CLASSIC_FOLDER_UUID)
        appProps.unsetValue(PREF_CLASSIC_FOLDER_NAME)
    }

    /** Wipe the Nested Shared Folder slot (project-scope only — this slot was never app-scope). */
    private fun clearDriveFolder(project: Project) {
        val props = PropertiesComponent.getInstance(project)
        props.unsetValue(PREF_DRIVE_FOLDER_UUID)
        props.unsetValue(PREF_DRIVE_FOLDER_NAME)
    }

    /**
     * Read the persisted classic folder, project-scope first with an
     * application-scope fallback for installs that saved the UUID under
     * the older app-wide key. Returns `(uuid, name)` or `(null, null)`.
     */
    private fun readSavedClassicFolder(project: Project): Pair<String?, String?> {
        val projectProps = PropertiesComponent.getInstance(project)
        val appProps = PropertiesComponent.getInstance()

        val uuid = projectProps.getValue(PREF_CLASSIC_FOLDER_UUID)?.takeIf { it.isNotBlank() }
            ?: appProps.getValue(PREF_CLASSIC_FOLDER_UUID)?.takeIf { it.isNotBlank() }
            ?: return null to null

        val name = projectProps.getValue(PREF_CLASSIC_FOLDER_NAME)?.takeIf { it.isNotBlank() }
            ?: appProps.getValue(PREF_CLASSIC_FOLDER_NAME)?.takeIf { it.isNotBlank() }

        return uuid to name
    }

    /**
     * The single info dialog shown when Add / Generate can't dispatch
     * because the project has no saved folder (either never picked, or
     * the previous pick has been cleared by the validator). The
     * [stalePrefix] parameter, when non-null, explains *why* the saved
     * value was cleared so the user isn't left guessing.
     */
    private fun showFolderPickerHint(
        project: Project,
        dialogTitle: String,
        stalePrefix: String? = null
    ) {
        val message = (stalePrefix ?: "No Keeper folder is selected for this project. ") +
            "Run \"Get Keeper Folder\" from the Tools \u2192 Keeper Vault menu to pick a folder, " +
            "then try this action again."

        invokeAndWait {
            Messages.showInfoMessage(project, message, dialogTitle)
        }
    }

    private fun invokeAndWait(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            block()
        } else {
            app.invokeAndWait(block, ModalityState.any())
        }
    }
}
