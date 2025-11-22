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
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.platform.util.progress.reportRawProgress
import kotlinx.coroutines.launch
import lol.bai.ravel.mapping.MappingTree
import lol.bai.ravel.mapping.MioClassMapping
import lol.bai.ravel.mapping.MioMappingConfig
import lol.bai.ravel.remapper.Remapper
import lol.bai.ravel.remapper.RemapperExtension
import lol.bai.ravel.util.listMultiMap

data class RemapperModel(
    val mappings: MutableList<MioMappingConfig> = arrayListOf(),
    val modules: MutableSet<Module> = linkedSetOf(),
)

@Suppress("UnstableApiUsage")
class RemapperAction : AnAction() {
    private val logger = thisLogger()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = RemapperModel()
        val ok = RemapperDialog(project, model).showAndGet()

        if (!ok) return
        if (model.mappings.isEmpty() || model.modules.isEmpty()) return

        currentThreadCoroutineScope().launch {
            withModalProgress(ModalTaskOwner.project(project), B("dialog.remapper.title"), TaskCancellation.nonCancellable()) {
                reportRawProgress { p ->
                    remap(project, model, p)
                }
            }
        }
    }

    suspend fun remap(project: Project, model: RemapperModel, progress: RawProgressReporter) {
        val time = System.currentTimeMillis()

        progress.fraction(null)
        progress.text(B("progress.readingMappings"))
        val mTree = MappingTree()
        model.mappings.first().tree.classes.forEach {
            mTree.putClass(MioClassMapping(model.mappings, it))
        }

        data class Target(
            val vf: VirtualFile,
            val module: Module,
            val remappers: List<Remapper>,
        )

        progress.fraction(null)
        progress.text(B("progress.fileTraverse"))
        val factories = RemapperExtension.createInstances()
        val targets = arrayListOf<Target>()
        for (module in model.modules) {
            for (root in module.rootManager.sourceRoots) {
                VfsUtil.iterateChildrenRecursively(root, null) v@{ vf ->
                    if (!vf.isFile) return@v true
                    val remappers = factories
                        .filter { it.matches(vf.extension ?: "") }
                        .map { it.create() }
                    if (remappers.isNotEmpty()) targets.add(Target(vf, module, remappers))
                    true
                }
            }
        }

        var fileCount = targets.size
        var fileIndex = 0
        val fileWriters = listMultiMap<VirtualFile, () -> Unit>()
        var writersCount = 0

        for ((vf, module, remappers) in targets) {
            progress.fraction(fileIndex.toDouble() / fileCount.toDouble())
            progress.text(B("progress.resolving", writersCount, fileIndex, fileCount))
            progress.details(vf.path)
            fileIndex++

            if (!vf.isFile) continue
            for (remapper in remappers) readActionBlocking r@{
                val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
                val write = Remapper.Write { writer ->
                    fileWriters.put(vf, writer)
                    writersCount++
                }

                val valid = remapper.init(project, scope, mTree, vf, write)
                if (!valid) return@r

                try {
                    remapper.stages().forEach { it.invoke() }
                } catch (e: Exception) {
                    write { remapper.fileComment("TODO(Ravel): Failed to fully resolve file: ${e.message}") }
                    logger.error("Failed to fully resolve ${vf.path}", e)
                }
            }
        }

        logger.warn("Mapping resolved in ${System.currentTimeMillis() - time}ms")
        progress.fraction(null)
        progress.text(null)
        progress.details(null)

        fileCount = fileWriters.size
        fileIndex = 0
        var writerIndex = 0

        fileWriters.forEach { (vf, writers) ->
            progress.details(vf.path)
            writeCommandAction(project, "Ravel Writer") {
                writers.forEach { writer ->
                    progress.fraction(writerIndex.toDouble() / writersCount.toDouble())
                    progress.text(B("progress.writing", writerIndex, writersCount, fileIndex, fileCount))
                    writerIndex++

                    try {
                        writer.invoke()
                    } catch (e: Exception) {
                        logger.error("Failed to write ${vf.path}", e)
                    }
                }
            }
            fileIndex++
        }

        logger.warn("Remap finished in ${System.currentTimeMillis() - time}ms")
    }
}
