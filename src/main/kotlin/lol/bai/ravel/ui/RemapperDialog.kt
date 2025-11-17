package lol.bai.ravel.ui

import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.*
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.util.ui.JBUI
import lol.bai.ravel.mapping.MioMappingConfig
import org.jetbrains.annotations.NonNls
import javax.swing.JLabel
import javax.swing.JList
import com.intellij.ui.dsl.builder.panel as rootPanel

class RemapperDialog(
    val project: Project,
    val model: RemapperModel
) : DialogWrapper(project), DataProvider {

    val fileColor = FileColorManager.getInstance(project)!!

    val modulesLabel = JLabel()
    lateinit var moduleList: ModuleList
    lateinit var mappingsModel: CollectionListModel<MioMappingConfig>

    init {
        title = B("dialog.remapper.title")
        init()
    }

    override fun getData(dataId: @NonNls String) = when (dataId) {
        K.modelData.name -> model
        K.modulesLabel.name -> modulesLabel
        K.modulesList.name -> moduleList
        K.mappingsModel.name -> mappingsModel
        else -> null
    }

    override fun createCenterPanel() = rootPanel {
        mappingsModel = CollectionListModel(model.mappings, true)
        val mappingsList = JBList<MioMappingConfig>().apply {
            model = mappingsModel
            setEmptyText(B("dialog.remapper.empty"))
        }
        val steps = ToolbarDecorator
            .createDecorator(mappingsList)
            .setPreferredSize(JBUI.size(300, 500))
            .addExtraAction(A<MappingActionGroup>())
            .setButtonComparator(
                B("group.lol.bai.ravel.ui.MappingActionGroup.text"),
                *CommonActionsPanel.Buttons.entries.map { it.text }.toTypedArray()
            )
            .createPanel()

        val moduleModel = CollectionListModel<ModuleEntry>()
        ModuleManager.getInstance(project).modules.sortedBy { it.name }.forEach { module ->
            if (module.rootManager.sourceRoots.isEmpty()) return@forEach
            moduleModel.add(ModuleEntry(module, false))
        }
        moduleList = ModuleList(moduleModel)
        modulesLabel.text = B("dialog.remapper.modules", model.modules.size, moduleModel.size)
        val modules = ToolbarDecorator
            .createDecorator(moduleList)
            .setPreferredSize(JBUI.size(400, 500))
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()
            .addExtraAction(A<MarkAllModuleAction>())
            .addExtraAction(A<MarkModuleAction>())
            .addExtraAction(A<UnmarkModuleAction>())
            .createPanel()

        row {
            cell(steps).label(B("dialog.remapper.mappings"), LabelPosition.TOP)
            cell(modules).label(modulesLabel, LabelPosition.TOP)
        }
    }

    inner class ModuleList(
        val model: CollectionListModel<ModuleEntry>
    ) : JBList<ModuleEntry>() {
        init {
            super.model = model
            cellRenderer = ModuleCellRenderer()
        }
    }

    data class ModuleEntry(
        val module: Module,
        var selected: Boolean
    )

    inner class ModuleCellRenderer : ColoredListCellRenderer<ModuleEntry>() {
        override fun customizeCellRenderer(list: JList<out ModuleEntry>, value: ModuleEntry, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(value.module.name)
            icon = ModuleType.get(value.module).icon
            if (value.selected) background = fileColor.getColor("Green")
            if (selected) background = JBUI.CurrentTheme.List.background(true, hasFocus)
        }
    }

}
