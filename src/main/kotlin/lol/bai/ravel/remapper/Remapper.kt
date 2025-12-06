package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import lol.bai.ravel.mapping.MappingTree
import lol.bai.ravel.mapping.MutableMappingTree
import lol.bai.ravel.util.Extension

val RemapperExtension = Extension<RemapperFactory>("lol.bai.ravel.remapper")

abstract class RemapperFactory(
    val create: () -> Remapper,
    val matches: (VirtualFile) -> Boolean,
)

abstract class ExtensionRemapperFactory(
    create: () -> Remapper,
    extension: String,
) : RemapperFactory(create, { it.name.endsWith(".${extension}") })

abstract class Remapper {
    protected lateinit var project: Project
    protected lateinit var scope: GlobalSearchScope
    protected lateinit var mTree: MappingTree
    protected lateinit var file: VirtualFile
    protected lateinit var write: Write
    protected lateinit var rerun: Rerun

    protected abstract fun init(): Boolean
    fun init(project: Project, scope: GlobalSearchScope, mTree: MappingTree, file: VirtualFile, write: Write, rerun: Rerun): Boolean {
        this.project = project
        this.scope = scope
        this.mTree = mTree
        this.file = file
        this.write = write
        this.rerun = rerun
        return init()
    }

    abstract fun fileComment(comment: String)
    abstract fun stages(): List<Stage>

    fun interface Stage {
        operator fun invoke()
    }

    fun interface Write {
        operator fun invoke(writer: () -> Unit)
    }

    fun interface Rerun {
        class Context(
            val mTree: MutableMappingTree
        )

        operator fun invoke(ctx: Context.() -> Unit)
    }
}

