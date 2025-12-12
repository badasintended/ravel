package lol.bai.ravel.remapper

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

abstract class PsiRemapper<F : PsiFile>(
    val caster: (PsiFile?) -> F?,
) : Remapper() {
    protected lateinit var pFile: F
    protected lateinit var pFileFactory: PsiFileFactory

    override fun init(): Boolean {
        val psi = PsiManager.getInstance(project)
        pFile = caster(psi.findFile(file)) ?: return false
        pFileFactory = PsiFileFactory.getInstance(project)
        return true
    }

    abstract fun comment(pElt: PsiElement, comment: String)
    override fun fileComment(comment: String) = comment(pFile, comment)

    protected inline fun <reified E : PsiElement> psiStage(crossinline action: (E) -> Unit): Stage = Stage { pFile.processChildren(action) }

    protected inline fun <reified E : PsiElement> PsiElement.processChildren(crossinline action: (E) -> Unit) {
        PsiTreeUtil.processElements(this, E::class.java) {
            if (it != this) action(it)
            true
        }
    }

    protected inline fun <reified E : PsiElement> PsiElement.parent(): E? {
        return PsiTreeUtil.getParentOfType(this, E::class.java)
    }

    protected val PsiElement.depth get() = PsiTreeUtil.getDepth(this, null)
}
