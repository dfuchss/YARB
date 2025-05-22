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
import net.folivo.trixnity.core.model.events.senderOrNull
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.yarb.Config
import org.fuchss.matrix.yarb.TimerManager
import org.fuchss.matrix.yarb.getMessageId
import java.time.LocalTime

class ReminderCommand(
    private val config: Config,
    private val timerManager: TimerManager
) : Command() {
    companion object {
        const val COMMAND_NAME = "new"
        private val TIME_REGEX = Regex("^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")
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
        // Handle New Messages
        execute(matrixBot, roomId, parameters, textEventId, textEventId)
    }

    private suspend fun execute(
        matrixBot: MatrixBot,
        roomId: RoomId,
        parameters: String,
        currentMessageEventId: EventId,
        initialMessageEventId: EventId
    ) {
        val timeXmessage = parameters.split(" ", "\n", limit = 2)
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
            matrixBot
                .room()
                .sendMessage(roomId) {
                    text(
                        "Time $time is in the past. I can only remind you at the same day :) Also remember that I'll inform you ${config.offsetInMinutes} min before :)"
                    )
                }
            return
        }

        logger.debug("Reminder for {} with '{}'", time, timeXmessage[1])

        val emojiToMessage = parseEmojiToMessage(timeXmessage[1])

        val botMessageTransactionId =
            matrixBot.room().sendMessage(roomId) {
                reply(initialMessageEventId, null)

                val header = "I'll remind all people at $time.${if (emojiToMessage.size > 1) "\n\n" else " "}"
                val options = emojiToMessage.map { (emoji, message) -> "Use '$emoji' for $message" }.joinToString("\n")
                text(header + options)
            }
        logger.debug("Bot Message TransactionId: {}", botMessageTransactionId)
        val botMessageId = matrixBot.room().getMessageId(roomId, botMessageTransactionId)
        if (botMessageId == null) {
            logger.error("Could not send bot message :( -- TransactionId: {}", botMessageTransactionId)
            return
        }
        logger.debug("Bot Message Id: {}", botMessageId)

        val botReactionMessageTransactionIds =
            emojiToMessage.map {
                matrixBot.room().sendMessage(roomId) {
                    react(botMessageId, it.key)
                }
            }
        logger.debug("Bot Reaction Message TransactionIds: {}", botReactionMessageTransactionIds)
        val botReactionMessageIds = botReactionMessageTransactionIds.map { matrixBot.room().getMessageId(roomId, it) }
        if (botReactionMessageIds.any { it == null }) {
            logger.error("Could not send bot reaction message :( -- TransactionIds: {}", botReactionMessageTransactionIds)
            matrixBot.roomApi().redactEvent(roomId, botMessageId).getOrNull()
            matrixBot.room().sendMessage(roomId) {
                text("Run into server rate limits. Please use less emojis :/")
            }
            return
        }
        logger.debug("Bot Reaction Message Ids: {}", botReactionMessageIds)

        val timer =
            TimerManager.TimerData(
                roomId,
                initialMessageEventId,
                currentMessageEventId,
                time,
                botMessageId,
                botReactionMessageIds.map { it!! },
                emojiToMessage
            )
        timerManager.addTimer(timer)
    }

    private fun parseEmojiToMessage(content: String): Map<String, String> {
        val lines = content.lines().map { it.trim() }.filter { !it.isBlank() }
        if (lines.size <= 1) {
            return mapOf(TimerManager.DEFAULT_REACTION to content)
        }

        // Check that lines are structured like "Emoji:Option"
        val options = lines.map { it.split(":", limit = 2) }
        if (options.any { it.size != 2 }) {
            return mapOf(TimerManager.DEFAULT_REACTION to content)
        }

        return options.associate { it[0].trim() to it[1].trim() }
    }

    suspend fun handleUserDeleteMessage(
        matrixBot: MatrixBot,
        event: ClientEvent<RedactionEventContent>
    ) {
        if (event.senderOrNull == matrixBot.self()) {
            return
        }
        val timer = timerManager.removeByOriginalRequestMessage(event.content.redacts) ?: return
        timer.redactAll(matrixBot)
    }

    suspend fun handleUserEditMessage(
        matrixBot: MatrixBot,
        eventId: EventId,
        senderId: UserId,
        roomId: RoomId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        logger.debug("Edit Message: {}", textEvent)
        val relatesTo = textEvent.relatesTo ?: return

        if (relatesTo.relationType != RelationType.Replace) {
            return
        }

        val timer = this.timerManager.removeByOriginalRequestMessage(relatesTo.eventId) ?: return
        timer.redactAll(matrixBot)

        val replace = (textEvent.relatesTo as? RelatesTo.Replace) ?: return
        val newBody = (replace.newContent as? RoomMessageEventContent.TextBased.Text)?.body ?: return

        var parameters = newBody.substring("!${config.prefix}".length).trim()
        if (parameters.startsWith(COMMAND_NAME)) {
            parameters = parameters.substring(COMMAND_NAME.length).trim()
        }
        execute(matrixBot, roomId, parameters, eventId, relatesTo.eventId)
    }
}
