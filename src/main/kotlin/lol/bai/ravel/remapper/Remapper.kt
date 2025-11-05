package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import lol.bai.ravel.mapping.MappingTree
import org.intellij.lang.annotations.Language

abstract class Remapper(
    @Language("regexp") regex: String
) {
    companion object {
        val instances = listOf<Remapper>(
            MixinRemapper,
            JavaRemapper
        )
    }

    val regex = regex.toRegex()

    protected lateinit var project: Project
    protected lateinit var scope: GlobalSearchScope
    protected lateinit var mTree: MappingTree
    protected lateinit var file: VirtualFile
    protected lateinit var write: Writer

    protected abstract fun init(): Boolean
    fun init(project: Project, scope: GlobalSearchScope, mTree: MappingTree, file: VirtualFile, write: Writer): Boolean {
        this.project = project
        this.scope = scope
        this.mTree = mTree
        this.file = file
        this.write = write
        return init()
    }

    abstract fun fileComment(comment: String)
    abstract fun remap()

}
