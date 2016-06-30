package org.rust.lang.core.psi

import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.psi.RustTokenElementTypes.INNER_EOL_DOC_COMMENT
import org.rust.lang.core.psi.RustTokenElementTypes.OUTER_EOL_DOC_COMMENT

interface RustDocAndAttributeOwner : RustCompositeElement, NavigatablePsiElement

/**
 * Get list of all item's inner and outer attributes.
 */
val RustDocAndAttributeOwner.allAttributes: List<RustAttrElement>
    get() = (this as? RustOuterAttributeOwner)?.outerAttrList.orEmpty() +
        (this as? RustInnerAttributeOwner)?.innerAttrList.orEmpty()

/**
 * Returns [QueryAttributes] for given PSI element.
 */
val RustDocAndAttributeOwner.queryAttributes: QueryAttributes
    get() = QueryAttributes(allAttributes)

/**
 * Allows for easy querying [RustDocAndAttributeOwner] for specific attributes.
 *
 * **Do not instantiate directly**, use [RustDocAndAttributeOwner.queryAttributes] instead.
 */
class QueryAttributes(private val attributes: List<RustAttrElement>) {
    fun hasAtomAttribute(name: String): Boolean =
        metaItems
            .filter { it.eq == null && it.lparen == null }
            .any { it.identifier.text == name }

    fun lookupStringValueForKey(key: String): String? =
        metaItems
            .filter { it.identifier.text == key }
            .mapNotNull { it.litExpr?.stringLiteralValue }
            .singleOrNull()

    private val metaItems: List<RustMetaItemElement>
        get() = attributes.mapNotNull { it.metaItem }
}

/**
 * Get documentation text of given [RustDocAndAttributeOwner].
 */
val RustDocAndAttributeOwner.documentation: String? get() {
    val lines = mutableListOf<String>()
    if (this is RustOuterAttributeOwner) {
        lines += outerDocs
    }
    if (this is RustInnerAttributeOwner) {
        lines += innerDocs
    }
    return lines.joinToString("\n")
}

private val RustOuterAttributeOwner.outerDocs: List<String> get() {
    // rustdoc appends the contents of each doc comment and doc attribute in order
    // so we have to resolve these attributes that are edge-bound at the top of the
    // children list.
    val childOuterIterator = PsiTreeUtil.childIterator(this, PsiElement::class.java)
    return childOuterIterator.asSequence()
        // All these outer elements have been edge bound; if we reach something that isn't one
        // of these, we have reached the actual parse children of this item.
        .takeWhile { it is RustOuterAttrElement || it is PsiComment || it is PsiWhiteSpace }
        .mapNotNull {
            when {
                it is RustOuterAttrElement -> it.metaItem.docAttr
                it is PsiComment && it.tokenType == OUTER_EOL_DOC_COMMENT -> it.text.substringAfter("///").trim()
                // FIXME Missing block comments
                else -> null
            }
        }.toList()
}

private val RustInnerAttributeOwner.innerDocs: List<String> get() {
    // Next, we have to consider inner comments and meta. These, like the outer case, are appended in
    // lexical order, after the outer elements. This only applies to functions and modules.
    val childBlock = PsiTreeUtil.findChildOfType(this, RustBlockElement::class.java)
        ?: return emptyList()

    val childInnerIterator = PsiTreeUtil.childIterator(childBlock, PsiElement::class.java)
    childInnerIterator.next() // skip the first open bracket ...
    return childInnerIterator.asSequence()
        // We only consider comments and attributes at the beginning.
        // Technically, anything else is a syntax error.
        .takeWhile { it is RustInnerAttrElement || it is PsiComment || it is PsiWhiteSpace }
        .mapNotNull {
            when {
                it is RustInnerAttrElement -> it.metaItem.docAttr
                it is PsiComment && it.tokenType == INNER_EOL_DOC_COMMENT -> it.text.substringAfter("//!").trim()
                // FIXME Missing block comments
                else -> null
            }
        }.toList()
}

private val RustMetaItemElement.docAttr: String?
    get() = if (identifier.text == "doc") litExpr?.stringLiteralValue else null

private val RustLitExprElement.stringLiteralValue: String?
    get() = ((stringLiteral ?: rawStringLiteral) as? RustLiteral.Text)?.value

