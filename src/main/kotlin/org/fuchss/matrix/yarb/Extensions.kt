package org.fuchss.matrix.yarb

import com.vdurmont.emoji.Emoji
import com.vdurmont.emoji.EmojiManager
import net.folivo.trixnity.core.model.UserId

private const val MATRIX_TO_PREFIX = "https://matrix.to/#/"

/**
 * Convert a string emoji to an [Emoji].
 */
fun String.emoji(): String = EmojiManager.getForAlias(this).unicode

/**
 * Create a matrix.to link from a RoomId
 * @return the matrix.to link
 */
fun UserId.matrixTo(): String = "${MATRIX_TO_PREFIX}${this.full}"
