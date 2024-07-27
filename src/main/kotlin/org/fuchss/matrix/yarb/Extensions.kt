package org.fuchss.matrix.yarb

import com.vdurmont.emoji.Emoji
import com.vdurmont.emoji.EmojiManager

private const val MATRIX_TO_PREFIX = "https://matrix.to/#/"

/**
 * Convert a string emoji to an [Emoji].
 */
fun String.emoji(): String = EmojiManager.getForAlias(this).unicode
