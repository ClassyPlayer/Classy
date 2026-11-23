package com.themoon.y1.books

/**
 * The text of one book section with its styles, without Android types (so the converters run in JVM tests).
 * [SpannedFactory] turns it into spans for [android.text.StaticLayout].
 *
 * Paragraphs are separated by '\n'. An image is one [IMAGE_CHAR] with an [Run.IMAGE] run over it.
 * [anchors] maps the ids of the section (`id` attributes, for the TOC fragments) to char offsets.
 */
class StyledText(
    val text: String,
    val runs: List<Run>,
    val anchors: Map<String, Int> = emptyMap(),
    /** Headings that the TXT formatter found (title, char offset); empty for EPUB sections. */
    val headings: List<Heading> = emptyList()
) {
    val length: Int get() = text.length

    class Heading(val title: String, val offset: Int)

    /** One style over [start, end). [value]: the heading level (1–6). [href]: the image path in the book. */
    class Run(val kind: Int, val start: Int, val end: Int, val value: Int = 0, val href: String? = null) {
        override fun toString(): String = "Run($kind, $start..$end${if (value != 0) ", $value" else ""}${href?.let { ", $it" } ?: ""})"

        companion object {
            const val BOLD = 1
            const val ITALIC = 2
            /** A heading ([value] = level): bold, larger, centred. */
            const val HEADING = 3
            const val CENTER = 4
            const val RIGHT = 5
            /** Blockquote: indented on both sides. */
            const val QUOTE = 6
            const val SUP = 7
            const val SUB = 8
            const val MONO = 9
            /** A body paragraph: the first line is indented. */
            const val PARAGRAPH = 10
            const val IMAGE = 11
            /** A list item: indented, with a bullet at its start. */
            const val LIST_ITEM = 12
            const val SMALL = 13
            const val UNDERLINE = 14
            const val STRIKE = 15
        }
    }

    companion object {
        /** OBJECT REPLACEMENT CHARACTER: the place of an image. */
        const val IMAGE_CHAR = '￼'

        val EMPTY = StyledText("", emptyList())
    }
}
