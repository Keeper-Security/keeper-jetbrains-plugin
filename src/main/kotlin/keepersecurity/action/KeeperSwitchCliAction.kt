package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import keepersecurity.model.KeeperCliInstall
import keepersecurity.service.KeeperCliSelection
import keepersecurity.service.KeeperShellService
import keepersecurity.ui.KeeperListPickerDialog
import keepersecurity.ui.KeeperListPickerItem

/**
 * Tools > Keeper Vault > Switch Keeper CLI.
 *
 * Lists every detected Keeper Commander (`keeper`) and Keeper SMK
 * (`~/.keeper-smk/<host>/bin/keeper-smk`) install, saves the user's pick, and
 * stops the running shell so the next Keeper action starts with the new CLI.
 */
class KeeperSwitchCliAction : AnAction("Switch Keeper CLI") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        object : Task.Backgroundable(project, "Detecting Keeper CLIs...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Looking for Keeper Commander and Keeper SMK..."
                val installs = try {
                    KeeperShellService.detectInstalls()
                } catch (ex: Exception) {
                    logger.error("Keeper CLI detection failed", ex)
                    showError(project, "Couldn't detect Keeper CLIs: ${ex.message}")
                    return
                }
                ApplicationManager.getApplication().invokeLater { pickAndSwitch(project, installs) }
            }
        }.queue()
    }

    private fun pickAndSwitch(project: Project, installs: List<KeeperCliInstall>) {
        if (installs.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "No Keeper Commander or Keeper SMK CLI was found.\n\n" +
                    "Install Keeper Commander (pip install keepercommander), or run your SMK " +
                    "deployment's Commander setup.sh, then try again.",
                "Switch Keeper CLI"
            )
            return
        }

        val currentId = KeeperShellService.getActiveCli()?.id ?: KeeperCliSelection.loadSaved()?.id
        val items = installs.map { install ->
            val current = if (install.id == currentId) "  [current]" else ""
            KeeperListPickerItem(label = "${install.displayLabel()}$current  -  ${install.path}", id = install.id)
        }
        val chosenItem = KeeperListPickerDialog.pickItem(
            project = project,
            title = "Switch Keeper CLI",
            message = "Choose which Keeper CLI the plugin uses. Each one has its own login and vault.",
            options = items,
            initialSelection = items.firstOrNull { it.id == currentId },
        ) ?: return
        val chosen = installs.first { it.id == chosenItem.id }

        object : Task.Backgroundable(project, "Switching Keeper CLI...", false) {
            override fun run(indicator: ProgressIndicator) {
                val changed = chosen.id != currentId
                KeeperShellService.switchCli(chosen)
                if (changed) {
                    ProjectManager.getInstance().openProjects.forEach { KeeperRecordTargetPrompt.clearSavedFolders(it) }
                }
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        project,
                        "Now using ${chosen.displayLabel()}\n${chosen.path}\n\n" +
                            "The Keeper shell will start with this CLI on your next Keeper action." +
                            (if (changed) " Saved folders were cleared because they belong to the previous vault." else "") +
                            "\n\nLogins are separate per CLI. If you aren't logged in yet, run this in a terminal:\n" +
                            "    ${chosen.terminalCommand()} login\n" +
                            "and enable persistent login (this-device persistent-login on).",
                        "Keeper CLI Switched"
                    )
                }
            }
        }.queue()
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Switch Keeper CLI")
        }
    }
}
