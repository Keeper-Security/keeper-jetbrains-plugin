package keepersecurity.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/** Vault-model badge shown beside each folder or record in the searchable picker. */
enum class KeeperVaultBadge(val label: String) {
    CLASSIC("Classic"),
    NESTED("Nested"),
}

/**
 * One row in [KeeperListPickerDialog]. [label] is the folder or record name;
 * [badge] identifies Classic vs Nested Shared Folder when both vault models
 * appear in the same list.
 */
data class KeeperListPickerItem(
    val label: String,
    val badge: KeeperVaultBadge? = null,
) {
    fun matchesSearch(query: String): Boolean {
        if (query.isEmpty()) return true
        if (label.contains(query, ignoreCase = true)) return true
        return badge?.label?.contains(query, ignoreCase = true) == true
    }
}

/**
 * Wide, resizable, searchable single-select list picker used by Keeper
 * actions in place of `Messages.showEditableChooseDialog`. The platform
 * helper is fine for short labels but truncates long record or folder titles
 * because it sizes to the *narrower* of its column metric and the screen width.
 *
 * Folder and record pickers pass [KeeperListPickerItem] rows with a Classic /
 * Nested badge on the right so users can tell which vault model each entry
 * belongs to without cluttering the primary label.
 *
 * Must be invoked on the EDT — same calling-convention as
 * `Messages.showEditableChooseDialog`.
 */
object KeeperListPickerDialog {

    /**
     * Show the picker and return the chosen entry, or `null` if the user
     * cancelled / dismissed the dialog.
     */
    fun pick(
        project: Project,
        title: String,
        message: String,
        options: List<String>,
        initialSelection: String? = null,
        preferredWidth: Int = 640,
        preferredHeight: Int = 420,
    ): String? = pickItem(
        project = project,
        title = title,
        message = message,
        options = options.map { KeeperListPickerItem(it) },
        initialSelection = initialSelection?.let { KeeperListPickerItem(it) },
        preferredWidth = preferredWidth,
        preferredHeight = preferredHeight,
    )?.label

    /**
     * Show the picker with optional Classic / Nested badges and return the
     * chosen [KeeperListPickerItem], or `null` if cancelled.
     */
    fun pickItem(
        project: Project,
        title: String,
        message: String,
        options: List<KeeperListPickerItem>,
        initialSelection: KeeperListPickerItem? = null,
        preferredWidth: Int = 640,
        preferredHeight: Int = 420,
    ): KeeperListPickerItem? {
        if (options.isEmpty()) return null
        val dialog = ListPickerDialog(
            project = project,
            dialogTitle = title,
            message = message,
            options = options,
            initial = initialSelection,
            prefW = preferredWidth,
            prefH = preferredHeight,
        )
        return if (dialog.showAndGet()) dialog.chosenValue else null
    }

    private class ListPickerDialog(
        project: Project,
        dialogTitle: String,
        private val message: String,
        private val options: List<KeeperListPickerItem>,
        private val initial: KeeperListPickerItem?,
        private val prefW: Int,
        private val prefH: Int,
    ) : DialogWrapper(project, true) {

        private val listModel = CollectionListModel(options)
        private val list = JBList(listModel).apply {
            cellRenderer = VaultBadgeListCellRenderer()
        }
        private val search = SearchTextField()

        init {
            title = dialogTitle
            list.selectionMode = ListSelectionModel.SINGLE_SELECTION

            val initialIndex = initial?.let { options.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            list.selectedIndex = initialIndex

            list.addListSelectionListener {
                isOKActionEnabled = list.selectedValue != null
            }

            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && list.selectedValue != null) {
                        doOKAction()
                    }
                }
            })

            search.textEditor.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    val query = search.text.trim()
                    val filtered = options.filter { it.matchesSearch(query) }
                    listModel.replaceAll(filtered)
                    list.selectedIndex = if (filtered.isNotEmpty()) 0 else -1
                    isOKActionEnabled = list.selectedValue != null
                }
            })

            init()
        }

        override fun getPreferredFocusedComponent(): JComponent = search

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
            panel.preferredSize = Dimension(JBUI.scale(prefW), JBUI.scale(prefH))

            if (message.isNotEmpty()) {
                val north = JPanel(BorderLayout(0, JBUI.scale(6)))
                north.add(JLabel(message), BorderLayout.NORTH)
                north.add(search, BorderLayout.SOUTH)
                panel.add(north, BorderLayout.NORTH)
            } else {
                panel.add(search, BorderLayout.NORTH)
            }
            panel.add(JBScrollPane(list), BorderLayout.CENTER)
            return panel
        }

        val chosenValue: KeeperListPickerItem?
            get() = list.selectedValue
    }

    private class VaultBadgeListCellRenderer : JPanel(BorderLayout(JBUI.scale(8), 0)),
        ListCellRenderer<KeeperListPickerItem> {

        private val nameLabel = JBLabel()
        private val badgeLabel = JBLabel("", SwingConstants.CENTER).apply {
            border = JBUI.Borders.empty(1, JBUI.scale(6))
            isOpaque = true
            font = font.deriveFont(font.size2D - 1f)
        }

        init {
            border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8))
            add(nameLabel, BorderLayout.CENTER)
            add(badgeLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out KeeperListPickerItem>,
            value: KeeperListPickerItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            nameLabel.text = value?.label.orEmpty()

            val badge = value?.badge
            if (badge != null) {
                badgeLabel.isVisible = true
                badgeLabel.text = badge.label
                applyBadgeColors(badgeLabel, badge, isSelected, list)
            } else {
                badgeLabel.isVisible = false
            }

            background = if (isSelected) list.selectionBackground else list.background
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            isOpaque = true
            return this
        }

        private fun applyBadgeColors(
            label: JBLabel,
            badge: KeeperVaultBadge,
            isSelected: Boolean,
            list: JList<out KeeperListPickerItem>,
        ) {
            if (isSelected) {
                label.background = list.selectionForeground
                label.foreground = list.selectionBackground
                return
            }
            val (background, foreground) = when (badge) {
                KeeperVaultBadge.CLASSIC -> Pair(
                    UIUtil.getPanelBackground().brighter(),
                    UIUtil.getLabelDisabledForeground(),
                )
                KeeperVaultBadge.NESTED -> Pair(
                    nestedBadgeBackground(),
                    nestedBadgeForeground(),
                )
            }
            label.background = background
            label.foreground = foreground
        }

        private fun nestedBadgeBackground(): Color =
            JBUI.CurrentTheme.Link.linkColor().let { link ->
                Color(
                    (link.red + 255 * 4) / 5,
                    (link.green + 255 * 4) / 5,
                    (link.blue + 255 * 4) / 5,
                    255,
                )
            }

        private fun nestedBadgeForeground(): Color = JBUI.CurrentTheme.Link.linkColor()
    }
}
