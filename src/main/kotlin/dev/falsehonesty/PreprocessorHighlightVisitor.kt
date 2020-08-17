package dev.falsehonesty

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.lang.Commenter
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import org.jetbrains.kotlin.psi.psiUtil.endOffset
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
        val commentSource = element.text
        if (commenter.lineCommentPrefix?.let { commentSource.startsWith(it) } != true) return

        val prefixLength = commenter.lineCommentPrefix?.length ?: return

        val comment = commentSource.substring(prefixLength)
        if (comment.isEmpty()) return

        EditorColorsManager.getInstance()

        if (comment.startsWith("#")) {
            val commentSegments = comment.substring(1).split(WHITESPACES_PATTERN, limit = 2)

            when (val directive = commentSegments[0]) {
                "if" -> {
                    preprocessorState.push(PreprocessorState.IF)

                    if (commentSegments.size < 2) {
                        fail(element, "Preprocessor directive \"if\" is missing a condition.")
                        return
                    }

                    val conditionsSource = commentSegments[1]
                    val conditions = conditionsSource.split(SPLIT_PATTERN)

                    var nextStartPos = prefixLength + 3
                    for (condition in conditions) {
                        val position = commentSource.indexOf(condition, nextStartPos)
                        nextStartPos = position + condition.length

                        val conditionMatcher = EXPR_PATTERN.find(condition)

                        if (conditionMatcher == null || conditionMatcher.groups.size < 4) {
                            holder.add(condition.trim().toInvalidConditionErrorHighlight(element, position))
                            continue
                        }

                        holder.add(conditionMatcher.groups[1]?.toNumericOrVariableHighlight(element, position))
                        holder.add(conditionMatcher.groups[3]?.toNumericOrVariableHighlight(element, position))
                    }

                    holder.add(directive.toDirectiveHighlight(element, prefixLength))
                }
                "ifdef" -> {
                    preprocessorState.push(PreprocessorState.IF)

                    if (commentSegments.size < 2) {
                        fail(element, "Preprocessor directive \"ifdef\" is missing an identifier.")
                        return
                    }

                    holder.add(directive.toDirectiveHighlight(element, prefixLength))

                    val idInfo = HighlightInfo
                        .newHighlightInfo(IDENTIFIER_TYPE)
                        .range(element as PsiElement, element.startOffset + prefixLength + 7, element.startOffset + prefixLength + 7 + commentSegments[1].length)
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

                    if (commentSegments.size > 1) {
                        fail(element, "Preprocessor directive \"else\" does not require any arguments.")
                        return
                    }

                    holder.add(directive.toDirectiveHighlight(element, prefixLength))
                }
                "endif" -> {
                    val state = preprocessorState.pollFirst()

                    if (state != PreprocessorState.IF && state != PreprocessorState.ELSE) {
                        fail(element, "Preprocessor directive \"endif\" must have an opening if.")
                        return
                    }

                    if (commentSegments.size > 1) {
                        fail(element, "Preprocessor directive \"endif\" does not require any arguments.")
                        return
                    }

                    holder.add(directive.toDirectiveHighlight(element, prefixLength))
                }
                else -> {
                    fail(element, "Unknown preprocessor directive \"$directive\"")
                }
            }
        } else if (comment.startsWith("$$")) {
            holder.add("$$".toDirectiveHighlight(element, prefixLength))

            highlightCodeBlock(element, element.startOffset + prefixLength + 2, comment.substring(2))
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

    private fun fail(element: PsiElement, text: String) {
        val info = HighlightInfo
            .newHighlightInfo(HighlightInfoType.ERROR)
            .range(element)
            .descriptionAndTooltip(text)
            .create()

        holder.add(info)
    }

    private fun MatchGroup.toNumericOrVariableHighlight(element: PsiCommentImpl, offset: Int = 0): HighlightInfo? {
        val builder = if (value.trim().toIntOrNull() != null) {
            HighlightInfo
                .newHighlightInfo(NUMBER_TYPE)
                .textAttributes(NUMBER_ATTRIBUTES)
        } else {
            HighlightInfo
                .newHighlightInfo(IDENTIFIER_TYPE)
                .textAttributes(IDENTIFIER_ATTRIBUTES)
        }

        return builder
            .range(element, element.startOffset + offset + range.first, element.startOffset + offset + range.last + 1)
            .create()
    }

    private fun String.toDirectiveHighlight(element: PsiCommentImpl, offset: Int = 0): HighlightInfo? {
        return HighlightInfo
            .newHighlightInfo(DIRECTIVE_TYPE)
            .textAttributes(DIRECTIVE_ATTRIBUTES)
            .range(element, element.startOffset + offset, element.startOffset + offset + 1 + length)
            .create()
    }

    private fun String.toInvalidConditionErrorHighlight(element: PsiCommentImpl, offset: Int = 0): HighlightInfo? {
        return HighlightInfo
            .newHighlightInfo(HighlightInfoType.ERROR)
            .range(element, element.startOffset + offset, element.startOffset + offset + length)
            .descriptionAndTooltip("Invalid condition \"$this\"")
            .create()
    }

    companion object {
        val BOLD_ATTRIBUTE = TextAttributes(null, null, null, null, Font.BOLD)
        val SCHEME = EditorColorsManager.getInstance().globalScheme

        val DIRECTIVE_COLOR = DefaultLanguageHighlighterColors.KEYWORD
        val DIRECTIVE_ATTRIBUTES = TextAttributes.merge(SCHEME.getAttributes(DIRECTIVE_COLOR), BOLD_ATTRIBUTE)
        val DIRECTIVE_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, DIRECTIVE_COLOR)

        val OPERATOR_COLOR = DefaultLanguageHighlighterColors.OPERATION_SIGN
        val OPERATOR_ATTRIBUTES = SCHEME.getAttributes(OPERATOR_COLOR)
        val OPERATOR_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, OPERATOR_COLOR)

        val IDENTIFIER_COLOR = DefaultLanguageHighlighterColors.IDENTIFIER
        val IDENTIFIER_ATTRIBUTES = TextAttributes.merge(SCHEME.getAttributes(IDENTIFIER_COLOR), BOLD_ATTRIBUTE)
        val IDENTIFIER_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, IDENTIFIER_COLOR)

        val NUMBER_COLOR = DefaultLanguageHighlighterColors.NUMBER
        val NUMBER_ATTRIBUTES = TextAttributes.merge(SCHEME.getAttributes(NUMBER_COLOR), BOLD_ATTRIBUTE)
        val NUMBER_TYPE = HighlightInfoType.HighlightInfoTypeImpl(HighlightSeverity.INFORMATION, NUMBER_COLOR)

        private val LOGGER: Logger = Logger.getInstance(PreprocessorHighlightVisitor::class.java)

        private val WHITESPACES_PATTERN = "\\s+".toRegex()
        private val EXPR_PATTERN = "(.+)(<=|>=|<|>)(.+)".toRegex()
        private val OR_PATTERN = Pattern.quote("||")
        private val AND_PATTERN = Pattern.quote("&&")
        private val SPLIT_PATTERN = Pattern.compile("$OR_PATTERN|$AND_PATTERN")
    }
}
