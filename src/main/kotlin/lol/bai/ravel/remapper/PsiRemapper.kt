package lol.bai.ravel.remapper

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

typealias Writer = (() -> Unit) -> Unit

abstract class PsiRemapper<F : PsiFile>(
    regex: Regex,
    val caster: (PsiFile?) -> F?,
): Remapper(regex) {

    protected lateinit var pFile: F

    override fun init(): Boolean {
        val psi = PsiManager.getInstance(project)
        pFile = caster(psi.findFile(file)) ?: return false
        return true
    }

    abstract fun comment(pElt: PsiElement, comment: String)
    override fun fileComment(comment: String) = comment(pFile, comment)

    protected inline fun <reified E : PsiElement> PsiElement.process(crossinline action: (E) -> Unit) {
        PsiTreeUtil.processElements(this, E::class.java) {
            action(it)
            true
        }
    }

    protected inline fun <reified E : PsiElement> PsiElement.parent(): E? {
        return PsiTreeUtil.getParentOfType(this, E::class.java)
    }

    protected val PsiElement.depth get() = PsiTreeUtil.getDepth(this, null)

}
