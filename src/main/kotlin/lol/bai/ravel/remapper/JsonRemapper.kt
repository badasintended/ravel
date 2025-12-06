package lol.bai.ravel.remapper

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.psi.PsiElement
import lol.bai.ravel.psi.createComment

abstract class JsonRemapper : PsiRemapper<JsonFile>({ it as? JsonFile }) {
    protected lateinit var gen: JsonElementGenerator

    override fun init(): Boolean {
        if (!super.init()) return false
        gen = JsonElementGenerator(project)
        return true
    }

    override fun comment(pElt: PsiElement, comment: String) {
        var pAnchor: PsiElement? = null
        comment.split('\n').forEach { line ->
            val pComment = gen.createComment("// $line")
            pAnchor =
                if (pAnchor == null) pElt.addBefore(pComment, pElt.firstChild)
                else pElt.addAfter(pComment, pAnchor)
        }
    }
}
