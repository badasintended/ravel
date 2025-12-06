package lol.bai.ravel.psi

import com.intellij.json.psi.JsonElementGenerator
import com.intellij.psi.PsiComment

fun JsonElementGenerator.createComment(comment: String): PsiComment {
    val file = createDummyFile(comment)
    return file.firstChild as PsiComment
}
