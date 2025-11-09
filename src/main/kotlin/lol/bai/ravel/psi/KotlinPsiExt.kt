package lol.bai.ravel.psi

import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

val KtClassOrObject.jvmName get() = toLightClass()?.jvmName

val KtFile.jvmName: String?
    get() {
        for (pClass in classes) {
            if (pClass is KtLightClassForFacade) return pClass.jvmName
        }
        return null
    }
