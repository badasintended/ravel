package lol.bai.ravel.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import lol.bai.ravel.ui.RemapperDialog.ModuleList

abstract class ModuleAction : AnAction() {
    abstract fun act(model: RemapperModel, modules: ModuleList)

    override fun actionPerformed(e: AnActionEvent) {
        val model = e.getData(K.modelData) ?: return
        val modules = e.getData(K.modulesList) ?: return
        val modulesLabel = e.getData(K.modulesLabel) ?: return

        act(model, modules)
        modulesLabel.text = B("dialog.remapper.modules", model.modules.size, modules.model.size)
    }
}

class MarkAllModuleAction : ModuleAction() {
    override fun act(model: RemapperModel, modules: ModuleList) {
        modules.model.items.forEach {
            model.modules.add(it.module)
            it.selected = true
        }
        modules.repaint()
    }
}

class MarkModuleAction : ModuleAction() {
    override fun act(model: RemapperModel, modules: ModuleList) {
        modules.selectedValuesList.forEach {
            model.modules.add(it.module)
            it.selected = true
        }
    }
}

class UnmarkModuleAction : ModuleAction() {
    override fun act(model: RemapperModel, modules: ModuleList) {
        modules.selectedValuesList.forEach {
            model.modules.remove(it.module)
            it.selected = false
        }
    }
}
