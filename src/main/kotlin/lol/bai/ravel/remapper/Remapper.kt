package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import lol.bai.ravel.mapping.MappingTree
import lol.bai.ravel.util.Extension

val RemapperExtension = Extension<RemapperFactory>("lol.bai.ravel.remapper")

abstract class RemapperFactory(
    val create: () -> Remapper,
    val matches: (String) -> Boolean,
)

abstract class ConstRemapperFactory(
    create: () -> Remapper,
    extension: String,
) : RemapperFactory(create, { it == extension })

abstract class Remapper {
    protected lateinit var project: Project
    protected lateinit var scope: GlobalSearchScope
    protected lateinit var mTree: MappingTree
    protected lateinit var file: VirtualFile
    protected lateinit var write: Write

    protected abstract fun init(): Boolean
    fun init(project: Project, scope: GlobalSearchScope, mTree: MappingTree, file: VirtualFile, write: Write): Boolean {
        this.project = project
        this.scope = scope
        this.mTree = mTree
        this.file = file
        this.write = write
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
}

