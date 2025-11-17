@file:Suppress("FunctionName")

package lol.bai.ravel.ui

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.ui.CollectionListModel
import com.intellij.util.application
import lol.bai.ravel.mapping.MioMappingConfig
import lol.bai.ravel.ui.RemapperDialog.ModuleList
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import javax.swing.JLabel

internal fun A(clazz: Class<out AnAction>): AnAction =
    ActionManager.getInstance().getAction(clazz.name)

internal inline fun <reified T : AnAction> A() = A(T::class.java)

@NonNls
private const val BUNDLE = "messages.RavelBundle"

internal object B : DynamicBundle(BUNDLE) {
    val error by lazy { B("dialog.error.title") }
}

internal fun B(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
    B.getMessage(key, *params)

internal object K {
    val modelData = DataKey.create<RemapperModel>("RemapperDialogModel")
    val modulesLabel = DataKey.create<JLabel>("RemapperDialogModulesLabel")
    val modulesList = DataKey.create<ModuleList>("RemapperDialogModulesList")
    val mappingsModel = DataKey.create<CollectionListModel<MioMappingConfig>>("RemapperDialogMappingsModel")
}

internal inline fun <reified T> S() = application.getService<T>(T::class.java)
