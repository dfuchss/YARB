package org.fuchss.matrix.yarb

import net.folivo.trixnity.client.room.RoomService
import net.folivo.trixnity.core.model.EventId
import org.fuchss.matrix.bots.firstWithTimeout

suspend fun RoomService.getMessageId(transactionId: String): EventId? {
    val outboxWithTransaction = this.getOutbox().firstWithTimeout { it[transactionId] != null } ?: return null
    val transaction = outboxWithTransaction[transactionId] ?: return null
    return transaction.firstWithTimeout { it?.eventId != null }?.eventId
}
