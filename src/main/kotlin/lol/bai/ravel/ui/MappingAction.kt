package lol.bai.ravel.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.bai.ravel.mapping.MappingNsVisitor
import lol.bai.ravel.mapping.MioMappingConfig
import lol.bai.ravel.mapping.downloader.MappingDownloader
import lol.bai.ravel.mapping.downloader.MappingDownloaderExtension
import lol.bai.ravel.util.getUserDownloadsDir
import lol.bai.ravel.util.resolveUnique
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.io.path.Path

class MappingActionGroup : DefaultActionGroup()

open class AddMappingAction : AnAction() {

    fun readMapping(e: AnActionEvent, path: Path) {
        val mappingsModel = e.getData(K.mappingsModel) ?: return

        val format = MappingReader.detectFormat(path)
        if (format == null) {
            Messages.showErrorDialog(e.project, B("dialog.mapping.unknownFormat"), B.error)
            return
        }

        MappingReader.read(path, format, MappingNsVisitor)
        val namespaces = arrayListOf(MappingNsVisitor.src)
        namespaces.addAll(MappingNsVisitor.dst)

        var srcNs = MappingNsVisitor.src
        var dstNs = MappingNsVisitor.dst.first()

        val ok = DialogBuilder(e.project)
            .title(B("dialog.mapping.title"))
            .centerPanel(panel {
                row(B("dialog.mapping.format")) { label(format.name) }
                row(B("dialog.mapping.srcNs")) {
                    comboBox(namespaces).bindItem({ srcNs }, { srcNs = it ?: srcNs })
                }
                row(B("dialog.mapping.dstNs")) {
                    comboBox(namespaces).bindItem({ dstNs }, { dstNs = it ?: dstNs })
                }
            })
            .showAndGet()

        if (!ok) return

        val mapping = MemoryMappingTree()
        val visitor =
            if (srcNs == MappingNsVisitor.src) mapping
            else MappingSourceNsSwitch(mapping, srcNs)

        MappingReader.read(path, format, visitor)
        mappingsModel.add(MioMappingConfig(mapping, srcNs, dstNs, path))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val fileDesc = FileChooserDescriptorFactory.singleFileOrDir()
        val path = FileChooser.chooseFile(fileDesc, e.project, null)?.toNioPath() ?: return
        readMapping(e, path)
    }

}

class DownloadMappingAction : AddMappingAction() {

    @Suppress("UnstableApiUsage")
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val downloaders = MappingDownloaderExtension.createInstances()

        var downloadDir = getUserDownloadsDir().toCanonicalPath()
        var selectedDownloader: MappingDownloader? = null
        val versionModel = CollectionComboBoxModel<String>()

        val ok = DialogBuilder(project)
            .title(B("dialog.mapping.download.title"))
            .centerPanel(panel {
                val downloaderModel = CollectionComboBoxModel(downloaders, null)
                val downloaderCb = ComboBox(downloaderModel)
                val versionCb = ComboBox(versionModel)

                downloaderCb.addActionListener {
                    val newDownloader = downloaderModel.selected
                    if (selectedDownloader != newDownloader && newDownloader != null) {
                        val service = S<GetVersionService>()
                        versionModel.selectedItem = B("dialog.mapping.download.version.pending")
                        versionModel.removeAll()
                        versionCb.isEnabled = false
                        service.getVersion(newDownloader) {
                            versionModel.add(it)
                            versionCb.isEnabled = true
                            versionModel.selectedItem = it.firstOrNull()
                        }
                    }
                    selectedDownloader = newDownloader ?: selectedDownloader
                }

                row(B("dialog.mapping.download.directory")) {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.singleDir(), project) { it.toNioPath().toCanonicalPath() }
                        .bindText({ downloadDir }, { downloadDir = it })
                }
                row(B("dialog.mapping.download.type")) { cell(downloaderCb) }
                row(B("dialog.mapping.download.version")) { cell(versionCb) }
            })
            .showAndGet()

        if (!ok) return
        val downloader = selectedDownloader ?: return
        val version = versionModel.selected ?: return

        currentThreadCoroutineScope().launch {
            val (name, extension) = downloader.resolveDest(version)
            val downloadPath = Path(downloadDir).resolveUnique(name, extension)

            val downloaded = withModalProgress(ModalTaskOwner.project(project), B("dialog.mapping.download.mapping.pending"), TaskCancellation.cancellable()) {
                downloader.download(version, downloadPath)
            }

            if (downloaded) withContext(Dispatchers.EDT) {
                readMapping(e, downloadPath)
            }
        }
    }

    @Service
    class GetVersionService(private val cs: CoroutineScope) {
        fun getVersion(downloader: MappingDownloader, consumer: (List<String>) -> Unit) = cs.launch {
            val versions = downloader.versions()
            SwingUtilities.invokeLater { consumer(versions) }
        }
    }

}


