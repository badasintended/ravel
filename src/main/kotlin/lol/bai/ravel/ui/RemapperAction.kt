package lol.bai.ravel.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.platform.util.progress.ProgressReporter
import com.intellij.platform.util.progress.reportProgress
import com.intellij.psi.PsiManager
import fleet.util.arrayListMultiMap
import kotlinx.coroutines.launch
import lol.bai.ravel.mapping.MappingTree
import lol.bai.ravel.mapping.MioClassMapping
import lol.bai.ravel.mapping.MioMappingConfig
import lol.bai.ravel.remapper.PsiRemapper
import lol.bai.ravel.util.B

data class RemapperModel(
    val mappings: MutableList<MioMappingConfig> = arrayListOf(),
    val modules: MutableList<Module> = arrayListOf(),
)

class RemapperAction : AnAction() {

    private val logger = thisLogger()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    @Suppress("UnstableApiUsage")
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = RemapperModel()
        val ok = RemapperDialog(project, model).showAndGet()
        if (ok) currentThreadCoroutineScope().launch {
            remap(project, model)
        }
    }

    suspend fun <T> run(project: Project, title: String, size: Int, block: suspend ProgressReporter.() -> T): T {
        return withModalProgress(ModalTaskOwner.project(project), title, TaskCancellation.nonCancellable()) {
            reportProgress(size) { reporter ->
                block(reporter)
            }
        }
    }

    /**
     * TODO: Currently tested with Fabric API
     *  - access widener
     *  - kotlin
     */
    suspend fun remap(project: Project, model: RemapperModel) {
        val time = System.currentTimeMillis()
        val psi = PsiManager.getInstance(project)

        val mTree = run(project, B("progress.readingMappings"), 1) {
            indeterminateStep {
                val mTree = MappingTree()
                model.mappings.first().tree.classes.forEach {
                    mTree.putClass(MioClassMapping(model.mappings, it))
                }
                mTree
            }
        }

        val files = run(project, B("progress.fileTraverse"), 1) {
            indeterminateStep {
                val files = arrayListOf<Pair<VirtualFile, Module>>()
                for (module in model.modules) {
                    for (root in module.rootManager.sourceRoots) {
                        VfsUtil.iterateChildrenRecursively(root, null) {
                            files.add(it to module)
                        }
                    }
                }
                files
            }
        }

        val fileWriters = run(project, B("progress.processing"), files.size) {
            val fileWriters = arrayListMultiMap<VirtualFile, () -> Unit>()
            for ((vf, module) in files) itemStep(vf.path) {
                readActionBlocking r@{
                    if (!vf.isFile) return@r true

                    for (remapper in PsiRemapper.instances) {
                        if (vf.extension != remapper.extension) continue
                        val pFile = remapper.caster(psi.findFile(vf)) ?: continue
                        val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
                        remapper.init(project, scope, mTree, pFile) { writer -> fileWriters.put(vf, writer) }
                        try {
                            remapper.remap()
                        } catch (e: Exception) {
                            fileWriters.put(vf) { remapper.comment(pFile, "TODO(Ravel): Failed to fully remap file: ${e.message}") }
                            logger.error("Failed to fully remap ${vf.path}", e)
                        }
                    }
                }
                true
            }
            fileWriters
        }

        logger.warn("Mapping resolved in ${System.currentTimeMillis() - time}ms")

        run(project, B("progress.remapping"), fileWriters.size()) {
            @Suppress("UnstableApiUsage")
            fileWriters.forEach { (vf, writers) ->
                itemStep(vf.path) {
                    writeCommandAction(project, "Ravel Writer") {
                        writers.forEach { writer ->
                            try {
                                writer.invoke()
                            } catch (e: Exception) {
                                logger.error("Failed to write ${vf.path}", e)
                            }
                        }
                    }
                }
            }
        }

        logger.warn("Remap finished in ${System.currentTimeMillis() - time}ms")
    }

}
