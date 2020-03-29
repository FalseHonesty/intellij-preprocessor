package dev.falsehonesty

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.lang.Commenter
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.containers.TreeTraversal
import java.awt.Font
import java.util.*
import java.util.regex.Pattern

class PreprocessorHighlightVisitor(private val project: Project) : HighlightVisitor, DumbAware {
    private lateinit var holder: HighlightInfoHolder
    private lateinit var commenter: Commenter
    private lateinit var highlighter: SyntaxHighlighter

    private var preprocessorState = ArrayDeque<PreprocessorState>()

    override fun suitableForFile(file: PsiFile): Boolean {
        return file.fileType.name.toUpperCase() in ALLOWED_TYPES
    }

    override fun analyze(
        file: PsiFile,
        updateWholeFile: Boolean,
        holder: HighlightInfoHolder,
        action: Runnable
    ): Boolean {
        this.holder = holder
        this.commenter = LanguageCommenters.INSTANCE.forLanguage(file.language)
        this.highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)

        action.run()

        return true
    }

    override fun clone() = PreprocessorHighlightVisitor(project)

    override fun visit(element: PsiElement) {
        if (element !is PsiCommentImpl) return
        var text = element.text
        if (commenter.lineCommentPrefix?.let { text.startsWith(it) } != true) return
        val prefixLength = commenter.lineCommentPrefix!!.length

        text = text.substring(commenter.lineCommentPrefix?.length ?: return)
        if (text.isEmpty()) return

        EditorColorsManager.getInstance()

        if (text.startsWith("#")) {
            val split = text.substring(1).split(" ")

            when (val command = split[0]) {
                "if" -> {
                    preprocessorState.push(PreprocessorState.IF)

                    if (split.size < 2) {
                        fail(element, "Preprocessor directive \"if\" is missing a condition.")
                        return
                    }

                    val condition = split[1]

                    val segments = condition.split(SPLIT_PATTERN)

                    for (segment in segments) {
                        val pos = text.indexOf(segment)
                        val result = EXPR_PATTERN.find(segment)

                        if (result == null || result.groups.size < 4) {
                            val info = HighlightInfo
                                .newHighlightInfo(HighlightInfoType.ERROR)
                                .range(element, pos + prefixLength, pos + prefixLength + segment.length)
                                .descriptionAndTooltip("Invalid condition \"$segment\"")
                                .create()

                            holder.add(info)

                            continue
                        }

                        val lhs = result.groups[1]!!
                        numberOrId(element, lhs)

                        val rhs = result.groups[3]!!
                        numberOrId(element, rhs)
                    }

                    val info = HighlightInfo
                        .newHighlightInfo(DIRECTIVE_TYPE)
                        .range(element as PsiElement, element.startOffset + prefixLength, element.startOffset + prefixLength + 3)
                        .textAttributes(DIRECTIVE_ATTRIBUTES)
                        .create()

                    holder.add(info)
                }
                "ifdef" -> {
                    preprocessorState.push(PreprocessorState.IF)

                    if (split.size < 2) {
                        fail(element, "Preprocessor directive \"ifdef\" is missing an identifier.")
                        return
                    }

                    val directiveInfo = HighlightInfo
                        .newHighlightInfo(DIRECTIVE_TYPE)
                        .range(element as PsiElement, element.startOffset + prefixLength, element.startOffset + prefixLength + 6)
                        .textAttributes(DIRECTIVE_ATTRIBUTES)
                        .create()

                    holder.add(directiveInfo)

                    val idInfo = HighlightInfo
                        .newHighlightInfo(IDENTIFIER_TYPE)
                        .range(element as PsiElement, element.startOffset + prefixLength + 7, element.startOffset + prefixLength + 7 + split[1].length)
                        .textAttributes(IDENTIFIER_ATTRIBUTES)
                        .create()

                    holder.add(idInfo)
                }
                "else" -> {
                    val state = preprocessorState.pollFirst()
                    preprocessorState.push(PreprocessorState.ELSE)

                    if (state != PreprocessorState.IF) {
                        fail(element, "Preprocessor directive \"else\" must have an opening if.")
                        return
                    }

                    if (split.size > 1) {
                        fail(element, "Preprocessor directive \"else\" does not require any arguments.")
                        return
                    }

                    val directiveInfo = HighlightInfo
                        .newHighlightInfo(DIRECTIVE_TYPE)
                        .range(element as PsiElement, element.startOffset + prefixLength, element.startOffset + prefixLength + 5)
                        .textAttributes(DIRECTIVE_ATTRIBUTES)
                        .create()

                    holder.add(directiveInfo)
                }
                "endif" -> {
                    val state = preprocessorState.pollFirst()

                    if (state != PreprocessorState.IF && state != PreprocessorState.ELSE) {
                        fail(element, "Preprocessor directive \"endif\" must have an opening if.")
                        return
                    }

                    if (split.size > 1) {
                        fail(element, "Preprocessor directive \"endif\" does not require any arguments.")
                        return
                    }

                    val directiveInfo = HighlightInfo
                        .newHighlightInfo(DIRECTIVE_TYPE)
                        .range(element as PsiElement, element.startOffset + prefixLength, element.startOffset + prefixLength + 6)
                        .textAttributes(DIRECTIVE_ATTRIBUTES)
                        .create()

                    holder.add(directiveInfo)
                }
                else -> {
                    fail(element, "Unknown preprocessor directive \"$command\"")
                }
            }
        } else if (text.startsWith("$$")) {
            val directiveInfo = HighlightInfo
                .newHighlightInfo(DIRECTIVE_TYPE)
                .range(element as PsiElement, element.startOffset + prefixLength, element.startOffset + prefixLength + 2)
                .textAttributes(DIRECTIVE_ATTRIBUTES)
                .create()

            holder.add(directiveInfo)

            highlightCodeBlock(element, element.startOffset + prefixLength + 2, text.substring(2))
        }
    }

    private fun highlightCodeBlock(element: PsiCommentImpl, startOffset: Int, text: String) {
        val lexer = highlighter.highlightingLexer

        lexer.start(text)
        var token = lexer.tokenType

        while (token != null) {
            val attributes = highlighter.getTokenHighlights(token)
                .fold(TextAttributes(null, null, null, null, 0)) { first, second ->
                    TextAttributes.merge(first, SCHEME.getAttributes(second))
                }

            val directiveInfo = HighlightInfo
                .newHighlightInfo(HighlightInfoType.INJECTED_LANGUAGE_FRAGMENT)
                .range(element as PsiElement,
                    startOffset + lexer.tokenStart,
                    startOffset + lexer.tokenEnd
                )
                .textAttributes(attributes)
                .create()

            holder.add(directiveInfo)

            lexer.advance()
            token = lexer.tokenType
        }
    }

    private fun numberOrId(element: PsiCommentImpl, match: MatchGroup) {
        if (match.value.toIntOrNull() != null) {
            val info = HighlightInfo
                .newHighlightInfo(NUMBER_TYPE)
                .colorMatch(element, match)
                .textAttributes(NUMBER_ATTRIBUTES)
                .create()

            holder.add(info)
        } else {
            val info = HighlightInfo
                .newHighlightInfo(IDENTIFIER_TYPE)
                .colorMatch(element, match)
                .textAttributes(IDENTIFIER_ATTRIBUTES)
                .create()

            holder.add(info)
        }
    }

    private fun HighlightInfo.Builder.colorMatch(element: PsiCommentImpl, match: MatchGroup) = apply {
        this.range(
            element as PsiElement,
            element.startOffset + 6 + match.range.first,
            element.startOffset + 6 + match.range.last + 1
        )
    }

    private fun fail(element: PsiElement, text: String) {
        val info = HighlightInfo
            .newHighlightInfo(HighlightInfoType.ERROR)
            .range(element)
            .descriptionAndTooltip(text)
            .create()

        holder.add(info)
    }

    companion object {
        val BOLD_ATTRIBUTE = TextAttributes(null, null, null, null, Font.BOLD)
        val SCHEME = EditorColorsManager.getInstance().globalScheme

        val DIRECTIVE_COLOR = DefaultLanguageHighlighterColors.KEYWORD
        val DIRECTIVE_ATTRIBUTES = TextAttributes.merge(SCHEME.getAttributes(DIRECTIVE_COLOR), BOLD_ATTRIBUTE)
        val DIRECTIVE_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, DIRECTIVE_COLOR)

        val IDENTIFIER_COLOR = DefaultLanguageHighlighterColors.IDENTIFIER
        val IDENTIFIER_ATTRIBUTES = TextAttributes.merge(SCHEME.getAttributes(IDENTIFIER_COLOR), BOLD_ATTRIBUTE)
        val IDENTIFIER_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, IDENTIFIER_COLOR)

        val NUMBER_COLOR = DefaultLanguageHighlighterColors.NUMBER
        val NUMBER_ATTRIBUTES = TextAttributes.merge(SCHEME.getAttributes(NUMBER_COLOR), BOLD_ATTRIBUTE)
        val NUMBER_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, NUMBER_COLOR)

        private val EXPR_PATTERN = "(.+)(<=|>=|<|>)(.+)".toRegex()
        private val OR_PATTERN = Pattern.quote("||")
        private val AND_PATTERN = Pattern.quote("&&")
        private val SPLIT_PATTERN = Pattern.compile("$OR_PATTERN|$AND_PATTERN")
    }
}