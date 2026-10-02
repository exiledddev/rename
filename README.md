# Rename

A nickname plugin for Paper 1.21.11 (it also runs on Purpur). Give players random nicknames, and optionally random skins, so the same community members can play different characters in different recordings.

A nickname replaces the player's name everywhere it shows up:
- the nametag above their head
- the tab list and chat
- death messages, including when they kill someone
- join, leave and advancement messages
- `/msg`
- vanilla selectors, such as `@a[name=GamerBoy9718]`

Nicknames stay on when players log off and on, and across server restarts, until you remove them.

Every command that targets players uses the vanilla selector, so names, `@a`, `@r[limit=3]`, `@a[team=red]`, `@p[distance=..10]` and the rest all work. It works alongside [TeamSplit](https://github.com/exiledddev/teamsplit) and [IRKit](https://github.com/exiledddev/irkit):

```
/rename @a[team=red] generic
/rename @a[team=blue] binary --skin
/kit give knight @a[team=red]
/unrename team red
```

## Install

1. Download the jar: open this repo's **Actions** tab, click **Build**, open the latest run, and download **Rename** under **Artifacts**. It's a zip; the jar is inside.
2. Put it in your server's `plugins/` folder and restart.

Rename needs no setup: it stores everything in `plugins/Rename/rename.db`.

## Nickname styles

| Style | Example | What it is |
|---|---|---|
| `generic` | `GamerBoy9718`, `GreatLucas`, `MaceGodYT`, `FuzzyBee` | 1-3 parts: a first word, a middle word, and a number or tag (`YT`, `TV`, `Pro`...). Millions of combinations, no swear words. |
| `binary` | `011110000001` | Only 1s and 0s, 3-16 characters. |
| `unsettling` | `rKcBZLaWgcMDk` | Random letters, numbers and `_`, 3-16 characters, like a generated password. |
| `obscured` | (scrambled text) | Shows as scrambled, constantly changing text in chat, the tab list, and death/join/leave/advancement messages. |

Every nickname is unique: never the same as another active nickname, an online player's name, or the real name of anyone who has joined.

Minecraft limits nicknames to valid usernames: 3-16 letters, numbers or `_`. That has two effects:
- **unsettling** names can't use other symbols.
- **obscured** names can't be scrambled on the nametag itself, which shows a random 16-character jumble instead. Everywhere else they're scrambled.

## Random skins

Add `--skin` to give each player a random skin along with the nickname:
- Skins come from random real Minecraft accounts, so they're unpredictable.
- If Mojang is unreachable, Rename uses the skin of a random player who has joined your server instead.
- `/unrename` puts the player's real skin back.
- To make random skins the default, set `skins.random-by-default: true` in the config. `--noskin` then skips them.

When a player is renamed, or their skin changes, their game refreshes for a split second. Everyone else sees the new name and skin straight away.

## Commands

| Command | What it does |
|---|---|
| `/rename <targets> [style] [--skin\|--noskin]` | Random nicknames. Leave out the style to use the config default (generic). |
| `/rename set <player> <nickname> [--skin]` | Give one player a specific nickname, e.g. a named character. |
| `/rename reroll <targets>` | New nicknames in the style they already have, and new random skins if they had one. |
| `/unrename <targets>` | Real names and skins back for online players. |
| `/unrename team <team>` | Real names back for everyone on a team, including offline members. |
| `/unrename all` | Real names back for everyone, including offline players. Run it when you're done recording. |
| `/rename list` | Who is nicked as what. |
| `/rename whois <nickname>` | The real player behind a nickname, even an old one. |
| `/rename history <player>` | A player's past nicknames. |
| `/rename auto <style> [--skin]` / `/rename auto off` | Auto-nick session: while it's on, anyone who joins without a nickname gets one. |
| `/rename reload` | Reload `config.yml`. |

Permissions:
- `rename.use` (default: op): all the commands.
- `rename.exempt` (default: nobody): never auto-nicked. Give it to your director so they can join during a session.

## Working with TeamSplit and IRKit

- **Teams:** Minecraft's scoreboard tracks players by name. When someone is renamed, Rename moves their team membership and scores to the new name, and back again when they're unnicked. TeamSplit teams, team colors, `@a[team=...]` and friendly fire all keep working.
- **TeamSplit version:** use TeamSplit from after its "nickname support" update, which finds team members by their current name.
- **`/teamrun` with `{player}`** keeps working for nicked players.
- **IRKit** needs no changes, because all of its commands use selectors.
- **Targeting a nicked player by plain name:** this isn't reliable. Minecraft's own lookup may still know them by their real name until they rejoin. Use selectors instead, like `@a[name=GamerBoy9718]` or `@a[team=red]`.

## Storage

Rename stores nicknames in SQLite by default, in `plugins/Rename/rename.db`. It keeps:
- each player's real name and skin
- every nickname ever given, with who gave it and when, which `/rename history` and `/rename whois` read
- the auto-nick session

Active nicknames survive players leaving and server restarts until you run `/unrename`.

To share nicknames between several servers, switch `storage.type` to `mysql` and fill in the connection details. Paper already includes the drivers, so there's nothing else to install.

```yaml
default-style: generic
skins:
  random-by-default: false
  random-account-attempts: 6
  fallback-to-server-skins: true
storage:
  type: sqlite
```

## Building

You need JDK 21. Alternatively, let GitHub build it: every push runs the **Build** workflow, which uploads the jar as an artifact.

```
./gradlew build          # jar ends up in build/libs/
./gradlew runServer      # starts a local Paper 1.21.11 test server with the plugin installed
```
