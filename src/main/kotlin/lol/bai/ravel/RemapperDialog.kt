package lol.bai.ravel

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.util.ui.JBUI
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.jetbrains.annotations.NonNls
import com.intellij.ui.dsl.builder.panel as rootPanel

class RemapperDialog(
    val project: Project,
    val model: RemapperModel
) : DialogWrapper(project), DataProvider {

    companion object {
        val modelData = DataKey.create<RemapperModel>("RemapperDialogModel")
        val modulesData = DataKey.create<ModuleList>("RemapperDialogTree")
    }

    lateinit var modules: ModuleList

    init {
        title = B("dialog.remapper.title")
        init()
    }

    override fun getData(dataId: @NonNls String): Any? {
        return when (dataId) {
            modelData.name -> model
            modulesData.name -> modules
            else -> null
        }
    }

    override fun createCenterPanel() = rootPanel {
        // TODO: preset saving
        row(B("dialog.remapper.preset")) { textField() }
        separator()

        val mappingsModel = CollectionListModel(model.mappings, true)
        val mappingsList = JBList<Mapping>().apply {
            model = mappingsModel
            setEmptyText(B("dialog.remapper.empty"))
        }
        val steps = ToolbarDecorator
            .createDecorator(mappingsList)
            .setAddAction {
                val fileDesc = FileChooserDescriptorFactory.singleFileOrDir()
                val file = FileChooser.chooseFile(fileDesc, project, null) ?: return@setAddAction
                val path = file.toNioPath()
                val format = MappingReader.detectFormat(path)

                if (format == null) {
                    Messages.showErrorDialog(project, B("dialog.mapping.unknownFormat"), B.error)
                    return@setAddAction
                }

                MappingReader.read(path, format, MappingNsVisitor)
                val namespaces = arrayListOf(MappingNsVisitor.src)
                namespaces.addAll(MappingNsVisitor.dst)

                var srcNs = MappingNsVisitor.src
                var dstNs = MappingNsVisitor.dst.first()

                val ok = DialogBuilder(project)
                    .title(B("dialog.mapping.title"))
                    .centerPanel(rootPanel {
                        row(B("dialog.mapping.format")) { label(format.name) }
                        row(B("dialog.mapping.srcNs")) {
                            comboBox(namespaces).bindItem({ srcNs }, { srcNs = it ?: srcNs })
                        }
                        row(B("dialog.mapping.dstNs")) {
                            comboBox(namespaces).bindItem({ dstNs }, { dstNs = it ?: dstNs })
                        }
                    })
                    .showAndGet()

                if (ok) {
                    val mapping = MemoryMappingTree()
                    val visitor =
                        if (srcNs == MappingNsVisitor.src) mapping
                        else MappingSourceNsSwitch(mapping, srcNs)

                    MappingReader.read(path, format, visitor)
                    mappingsModel.add(Mapping(mapping, srcNs, dstNs, path))
                }
            }
            .setPreferredSize(JBUI.size(300, 500))
            .createPanel()

        // TODO: selected module indicator
        val moduleModel = CollectionListModel<ModuleEntry>()
        ModuleManager.getInstance(project).modules.sortedBy { it.name }.forEach { module ->
            if (module.rootManager.sourceRoots.isEmpty()) return@forEach
            moduleModel.add(ModuleEntry(module))
        }
        modules = ModuleList(moduleModel)
        val projectT = ToolbarDecorator
            .createDecorator(modules)
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()
            .addExtraAction(A<MarkAllAction>())
            .addExtraAction(A<MarkAction>())
            .addExtraAction(A<UnMarkAction>())
            .setPreferredSize(JBUI.size(400, 500))
            .createPanel()

        row {
            panel {
                row { label(B("dialog.remapper.mappings")) }
                row { cell(steps) }
            }

            panel {
                row { label(B("dialog.remapper.modules")) }
                row { cell(projectT) }
            }
        }
    }

    class ModuleList(
        val model: CollectionListModel<ModuleEntry>
    ) : JBList<ModuleEntry>() {
        init {
            super.model = model
        }
    }

    data class ModuleEntry(val module: Module) {
        override fun toString(): String = module.name
    }

    abstract class ModuleAction : AnAction() {
        abstract fun act(model: RemapperModel, modules: ModuleList)

        override fun actionPerformed(e: AnActionEvent) {
            val model = e.getData(modelData) ?: return
            val modules = e.getData(modulesData) ?: return
            act(model, modules)
        }
    }

    class MarkAllAction : ModuleAction() {
        override fun act(model: RemapperModel, modules: ModuleList) {
            model.modules.addAll(modules.model.items.map { it.module })
        }
    }

    class MarkAction : ModuleAction() {
        override fun act(model: RemapperModel, modules: ModuleList) {
            model.modules.addAll(modules.selectedValuesList.map { it.module })
        }
    }

    class UnMarkAction : ModuleAction() {
        override fun act(model: RemapperModel, modules: ModuleList) {
            model.modules.removeAll(modules.selectedValuesList.map { it.module })
        }
    }

}
