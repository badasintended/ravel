package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope

abstract class Remapper<F : PsiFile>(
    val extension: String,
    val caster: (PsiFile?) -> F?,
) {

    protected lateinit var project: Project
    protected lateinit var scope: GlobalSearchScope
    protected lateinit var mappings: Mappings
    protected lateinit var mClasses: ClassMappings
    protected lateinit var pFile: F
    protected lateinit var write: Writer

    fun init(project: Project, scope: GlobalSearchScope, mappings: Mappings, mClasses: ClassMappings, pFile: F, write: Writer) {
        this.project = project
        this.scope = scope
        this.mappings = mappings
        this.mClasses = mClasses
        this.pFile = pFile
        this.write = write
        init()
    }

    protected open fun init() {}

    abstract fun comment(pElt: PsiElement, comment: String)
    abstract fun remap()

}
