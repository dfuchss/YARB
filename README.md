# YARB - Yet Another Reminder Bot

This bot can be used to create reminders for a specific time at a day.

## Features

* Create reminders for a specific time at the current day.
* Change display name per room
* Configure an offset for the reminder time
* Simple rights management (same as for my other bots)

![Functions](.docs/images/functions.png)

## Setup

1. Get a matrix account for the bot (e.g., on your own homeserver or on `matrix.org`)
2. Prepare configuration:
    * Copy `config-sample.json` to `config.json`
    * Enter `baseUrl` to the matrix server and `username` / `password` for the bot user
    * Add yourself to the `admins` (and delete my account from the list :))
    * You can limit the users that can interact with the bot by defining the `users` list
3. Either run the bot via jar or run it via the provided docker.
    * If you run it locally, you can use the environment variable `CONFIG_PATH` to point at your `config.json` (defaults to `./config.json`)
    * If you run it in docker, you can use a command similar to this `docker run -itd -v $LOCAL_PATH_TO_CONFIG:/usr/src/bot/data/config.json:ro ghcr.io/dfuchss/yarb`

## Usage

* An admin can invite the bot to an *unencrypted* room. If the room has enabled encryption or if the invite was not sent by an admin, the bot ignores it (without logging it)
* After the bot has joined use `!yarb help` to get an overview about the features of the bot (remember: the bot only respond to users)
* In order to create a new reminder use `!yarb <time> <message>`. The time has to be in the format `HH:mm` (e.g., `!yarb 12:00 Lunch time!`).
* You can configure the bot name in the `config.json` 

## Development

I'm typically online in the [Trixnity channel](https://matrix.to/#/#trixnity:imbitbu.de). So feel free to tag me there if you have any questions.

* The bot is build using the [Trixnity](https://trixnity.gitlab.io/trixnity/) framework.
* The basic functionality is located in [Main.kt](src/main/kotlin/org/fuchss/matrix/yarb/Main.kt). There you can also find the main method of the program.
