package org.fuchss.matrix.yarb.commands

import net.folivo.trixnity.client.room.message.react
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.RelationType
import net.folivo.trixnity.core.model.events.m.room.RedactionEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.model.events.roomIdOrNull
import net.folivo.trixnity.core.model.events.senderOrNull
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.yarb.Config
import org.fuchss.matrix.yarb.TimerManager
import java.time.LocalTime

class ReminderCommand(private val config: Config, private val timerManager: TimerManager) : Command() {
    companion object {
        const val COMMAND_NAME = "new"
        private val TIME_REGEX = Regex("^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")
        private val EMOJI = TimerManager.EMOJI
    }

    override val help: String = "Set a reminder for a specific time."
    override val params: String = "<time|11:30> <message|Time for Lunch!>"
    override val name: String = COMMAND_NAME
    override val autoAcknowledge: Boolean = false

    override suspend fun execute(
        matrixBot: MatrixBot,
        sender: UserId,
        roomId: RoomId,
        parameters: String,
        textEventId: EventId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        val timeXmessage = parameters.split(" ", limit = 2)
        if (timeXmessage.size != 2) {
            matrixBot.room().sendMessage(roomId) { text("Time not found. Please use commands like '!${config.prefix} 09:00 Time to Work!'") }
            return
        }
        if (!TIME_REGEX.matches(timeXmessage[0])) {
            matrixBot.room().sendMessage(roomId) { text("Invalid time format. Please use commands like '!${config.prefix} 09:00 Time to Work!'") }
            return
        }

        val time = LocalTime.parse(timeXmessage[0]).withSecond(0).minusMinutes(config.offsetInMinutes)
        val now = LocalTime.now()
        if (now.isAfter(time)) {
            matrixBot.room()
                .sendMessage(roomId) {
                    text(
                        "Time $time is in the past. I can only remind you at the same day :) Also remember that I'll inform you ${config.offsetInMinutes} min before :)"
                    )
                }
            return
        }

        val timelineEvent = matrixBot.getTimelineEvent(roomId, textEventId) ?: return
        timerManager.addTimer(roomId, textEventId, time, timeXmessage[1])

        matrixBot.room().sendMessage(roomId) {
            reply(timelineEvent)
            text("I'll remind all people at $time with '${timeXmessage[1]}'. If you want to receive a message please click on $EMOJI")
        }
    }

    suspend fun handleBotMessageForReminder(
        matrixBot: MatrixBot,
        eventId: EventId,
        sender: UserId,
        roomId: RoomId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        if (sender != matrixBot.self()) {
            return
        }

        val relatesTo = textEvent.relatesTo ?: return

        if (relatesTo.relationType != RelationType.Reply) {
            return
        }

        val isRelated = timerManager.addBotMessageToTimer(relatesTo.eventId, eventId)
        if (!isRelated) {
            return
        }

        matrixBot.room().sendMessage(roomId) {
            react(eventId, EMOJI)
        }
    }

    suspend fun handleUserDeleteMessage(
        matrixBot: MatrixBot,
        event: ClientEvent<RedactionEventContent>
    ) {
        if (event.senderOrNull == matrixBot.self()) {
            return
        }

        val botMessage = timerManager.removeByRequestMessage(event.content.redacts) ?: return
        val roomId = event.roomIdOrNull ?: return
        matrixBot.roomApi().redactEvent(roomId, botMessage).getOrThrow()
    }

    suspend fun handleUserEditMessage(
        matrixBot: MatrixBot,
        eventId: EventId,
        senderId: UserId,
        roomId: RoomId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        val relatesTo = textEvent.relatesTo ?: return

        if (relatesTo.relationType != RelationType.Replace) {
            return
        }

        val relatedBotMessage = this.timerManager.removeByRequestMessage(relatesTo.eventId) ?: return

        matrixBot.roomApi().redactEvent(roomId, relatedBotMessage).getOrThrow()

        val replace = (textEvent.relatesTo as? RelatesTo.Replace) ?: return
        val newBody = (replace.newContent as? RoomMessageEventContent.TextBased.Text)?.body ?: return

        var parameters = newBody.substring("!${config.prefix}".length).trim()
        if (parameters.startsWith(COMMAND_NAME)) {
            parameters = parameters.substring(COMMAND_NAME.length).trim()
        }
        execute(matrixBot, senderId, roomId, parameters, replace.eventId, textEvent)
    }
}
