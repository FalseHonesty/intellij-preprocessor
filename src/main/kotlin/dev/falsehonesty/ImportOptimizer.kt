package dev.falsehonesty

import com.intellij.lang.ImportOptimizer
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.impl.source.tree.PsiCommentImpl

class ImportOptimizer : ImportOptimizer {
    override fun supports(file: PsiFile): Boolean {
        return file is PsiJavaFile
    }

    override fun processFile(file: PsiFile): Runnable {
        if (file !is PsiJavaFile) return EmptyRunnable.getInstance()

        val imports = file.importList ?: return EmptyRunnable.getInstance()

        // If we don't have preprocessor directives, we can use the more powerful import optimizer.
        if (!hasPreprocessorDirectives(imports)) {
            val javaImportOptimizer = LanguageImportStatements.INSTANCE
                .allForLanguage(file.language)
                .first { it !is dev.falsehonesty.ImportOptimizer  }

            return javaImportOptimizer.processFile(file)
        }

        val optimizedImportList = JavaCodeStyleManager.getInstance(file.project).prepareOptimizeImportsResult(file)

        return Runnable {
            val manager = PsiDocumentManager.getInstance(file.project)
            val document = manager.getDocument(file)
            if (document != null) {
                manager.commitDocument(document)
            }

            // Delete any imports that no longer exist in the optimized list.
            for (import in imports.importStatements) {
                if (optimizedImportList.findSingleClassImportStatement(import.qualifiedName) == null) {
                    import.delete()
                }
            }

            if (imports.firstChild is PsiWhiteSpace) {
                imports.firstChild.delete()
            }
        }
    }

    private fun hasPreprocessorDirectives(imports: PsiImportList): Boolean {
        var import = imports.firstChild

        while (import != null) {
            if (import is PsiCommentImpl && import.text.startsWith("//#")) return true

            import = import.nextSibling
        }

        return false
    }
}