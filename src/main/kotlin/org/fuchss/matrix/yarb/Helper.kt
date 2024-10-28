package org.fuchss.matrix.yarb

import net.folivo.trixnity.client.room.RoomService
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import org.fuchss.matrix.bots.firstWithTimeout

suspend fun RoomService.getMessageId(
    roomId: RoomId,
    transactionId: String
): EventId? {
    val transaction = this.getOutbox(roomId, transactionId)
    return transaction.firstWithTimeout { it?.eventId != null }?.eventId
}
