package org.fuchss.matrix.yarb.commands

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.client.room.getTimelineEventReactionAggregation
import net.folivo.trixnity.client.room.message.react
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.RelationType
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.firstWithTimeout
import org.fuchss.matrix.yarb.Config
import org.fuchss.matrix.yarb.emoji
import org.fuchss.matrix.yarb.matrixTo
import java.time.LocalTime
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.minutes

class ReminderCommand(private val config: Config, private val timer: Timer) : Command() {
    companion object {
        const val COMMAND_NAME = "new"
        private val TIME_REGEX = Regex("^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")
        private val EMOJI = ":+1:".emoji()
    }

    override val help: String = "Set a reminder for a specific time."
    override val params: String = "<time|11:30> <message|Time for Lunch!>"
    override val name: String = COMMAND_NAME

    private val timers = mutableListOf<TimerData>()

    init {
        val millisecondsToNextMinute = (60 - LocalTime.now().second) * 1000L
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    runBlocking {
                        logger.debug("Reminders: {}", timers)
                        val timerCopy = timers.toList()
                        val now = LocalTime.now()
                        for (timer in timerCopy) {
                            if (timer.timeToRemind.isAfter(now)) {
                                continue
                            }
                            timers.remove(timer)
                            remind(timer)
                        }
                    }
                }
            },
            millisecondsToNextMinute,
            1.minutes.inWholeMilliseconds
        )
    }

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

        val time = LocalTime.parse(timeXmessage[0])
        val now = LocalTime.now()
        if (now.isAfter(time)) {
            matrixBot.room().sendMessage(roomId) { text("Time ($time) is in the past. I can only remind you at the same day :)") }
            return
        }

        val timelineEvent = getTimelineEvent(matrixBot, roomId, textEventId) ?: return

        val timerData = TimerData(matrixBot, roomId, textEventId, time, null)
        timers.add(timerData)

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

        val timerData = this.timers.find { it.roomId == roomId && it.requestMessage == relatesTo.eventId } ?: return

        timerData.botMessageId = eventId
        matrixBot.room().sendMessage(roomId) {
            react(eventId, EMOJI)
        }
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

        val timerData = this.timers.find { it.roomId == roomId && it.requestMessage == relatesTo.eventId } ?: return
        timers.remove(timerData)

        if (timerData.botMessageId != null) {
            matrixBot.roomApi().redactEvent(roomId, timerData.botMessageId!!).getOrThrow()
        }

        val replace = (textEvent.relatesTo as? RelatesTo.Replace) ?: return
        val newBody = (replace.newContent as? RoomMessageEventContent.TextBased.Text)?.body ?: return

        var parameters = newBody.substring("!${config.prefix}".length).trim()
        if (parameters.startsWith(COMMAND_NAME)) {
            parameters = parameters.substring(COMMAND_NAME.length).trim()
        }
        execute(matrixBot, senderId, roomId, parameters, replace.eventId, textEvent)
    }

    private suspend fun remind(timer: TimerData) {
        try {
            val matrixBot = timer.matrixBot
            val roomId = timer.roomId
            val messageId = timer.botMessageId ?: return

            val reactions = matrixBot.room().getTimelineEventReactionAggregation(roomId, messageId).first().reactions
            val peopleToRemind = reactions[EMOJI]?.filter { it != matrixBot.self() }?.map { it.matrixTo() }
            if (peopleToRemind.isNullOrEmpty()) {
                return
            }

            val timelineEvent = getTimelineEvent(matrixBot, roomId, messageId) ?: return
            matrixBot.room().sendMessage(roomId) {
                reply(timelineEvent)
                text("Reminder for ${peopleToRemind.joinToString(", ")}")
            }
        } catch (e: Exception) {
            logger.error("Error during remind: ${e.message}", e)
        }
    }

    private suspend fun getTimelineEvent(
        matrixBot: MatrixBot,
        roomId: RoomId,
        eventId: EventId
    ): TimelineEvent? {
        val timelineEvent = matrixBot.room().getTimelineEvent(roomId, eventId).firstWithTimeout { it?.content != null }
        if (timelineEvent == null) {
            logger.error("Cannot get timeline event for $eventId within the given time ..")
            return null
        }
        return timelineEvent
    }

    private data class TimerData(
        val matrixBot: MatrixBot,
        val roomId: RoomId,
        val requestMessage: EventId,
        val timeToRemind: LocalTime,
        var botMessageId: EventId?
    )
}
