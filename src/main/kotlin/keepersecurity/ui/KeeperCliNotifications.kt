package keepersecurity.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import keepersecurity.service.KeeperShellService

/**
 * Balloon shown when several Keeper CLIs are installed and the plugin picked one
 * automatically. It links to the *Switch Keeper CLI* action.
 *
 * [KeeperShellService] only exposes a callback ([KeeperShellService.autoPickListener]),
 * so the service itself stays free of UI code. [install] wires the two together
 * from the project startup activity.
 */
object KeeperCliNotifications {
    /** Must match the `<notificationGroup>` id registered in plugin.xml. */
    private const val NOTIFICATION_GROUP_ID = "Keeper Security"
    private const val SWITCH_CLI_ACTION_ID = "keepersecurity.action.KeeperSwitchCliAction"

    fun install(project: Project) {
        KeeperShellService.autoPickListener = { picked, all ->
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    "Keeper CLI selected automatically",
                    "Found ${all.size} Keeper CLIs. Using ${picked.displayLabel()}.<br/>" +
                        "Use <b>Tools | Keeper Vault | Switch Keeper CLI</b> to choose another.",
                    NotificationType.INFORMATION
                )
                .addAction(NotificationAction.createSimpleExpiring("Switch Keeper CLI...") {
                    val action = ActionManager.getInstance().getAction(SWITCH_CLI_ACTION_ID)
                        ?: return@createSimpleExpiring
                    val event = AnActionEvent.createEvent(
                        action,
                        SimpleDataContext.getProjectContext(project),
                        action.templatePresentation.clone(),
                        ActionPlaces.NOTIFICATION,
                        ActionUiKind.NONE,
                        null,
                    )
                    ActionUtil.invokeAction(action, event, null)
                })
                .notify(null)
        }
    }
}
