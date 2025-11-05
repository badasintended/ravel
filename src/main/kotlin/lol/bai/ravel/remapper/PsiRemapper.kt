package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import lol.bai.ravel.mapping.MappingTree

typealias Writer = (() -> Unit) -> Unit

abstract class PsiRemapper<F : PsiFile>(
    val extension: String,
    val caster: (PsiFile?) -> F?,
) {
    companion object {
        val instances = listOf(
            MixinRemapper,
            JavaRemapper
        )
    }

    protected lateinit var project: Project
    protected lateinit var scope: GlobalSearchScope
    protected lateinit var mTree: MappingTree
    protected lateinit var pFile: F
    protected lateinit var write: Writer

    protected open fun init() {}
    fun init(project: Project, scope: GlobalSearchScope, mTree: MappingTree, pFile: F, write: Writer) {
        this.project = project
        this.scope = scope
        this.mTree = mTree
        this.pFile = pFile
        this.write = write
        init()
    }

    abstract fun comment(pElt: PsiElement, comment: String)
    abstract fun remap()

    protected inline fun <reified E : PsiElement> PsiElement.process(crossinline action: (E) -> Unit) {
        PsiTreeUtil.processElements(this, E::class.java) {
            action(it)
            true
        }
    }

    protected inline fun <reified E : PsiElement> PsiElement.parent(): E? {
        return PsiTreeUtil.getParentOfType(this, E::class.java)
    }

}
