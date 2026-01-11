# Cmdutils - Dynamic Shortcut Commands for Paper (Minecraft)

Cmdutils is a lightweight Paper plugin that lets you define new commands as "shortcuts" in `config.yml`. Each shortcut can:
- Run one or more commands (as the sender, console, or a specific player)
- Send formatted messages to the executor
- Use arguments and placeholders (works even without PlaceholderAPI; integrates with PAPI when installed)
- Enforce optional permissions and player-only restrictions

It also provides two admin commands:
- `/cmdutilsreload` reload shortcuts from `config.yml`
- `/sudo` execute commands as another player with temporary OP and a sudo bypass for shortcut permissions


## Requirements
- Paper 1.21.x (tested against `1.21.11` API)
- Java 21 runtime (Paper 1.21 requires Java 21)
- PlaceholderAPI (optional) only if you want PAPI placeholders; built-ins work without it

The built artifact is a shaded (fat) JAR that includes Kotlin runtime, so no extra libraries are needed on the server.


## Installation
1. Download or build the plugin JAR (see Build below).
2. Place the JAR into your server `plugins/` folder.
3. (Optional) Install PlaceholderAPI if you want advanced placeholders.
4. Start or restart the server. A default `config.yml` will be generated on first run.

To apply config-only changes, use `/cmdutilsreload` (no server restart required).


## Build (Gradle)
This project uses the Gradle wrapper and produces a shaded JAR by default.

- Windows (PowerShell):
  ```powershell
  .\gradlew.bat clean build
  ```
- macOS/Linux:
  ```bash
  ./gradlew clean build
  ```

Output: `build/libs/cmdutils-<version>.jar` (shaded, ready to drop into `plugins/`).


## Defining Shortcuts (config.yml)
Each top-level key is the command label that players will type in-game, e.g. `/greet`.

Fields per shortcut:
- `permision` or `permission` (string, optional)
  - Note: both spellings are supported for compatibility. If set, the command will be hidden from players lacking the node (permission gating applies), unless executed via `/sudo`.
- `playerOnly` (boolean, optional, default false)
  - If `true`, the shortcut only runs when executed by a player. If run by console, it returns immediately without doing anything.
- `usage` (string, optional)
  - A hint message shown by Bukkit when needed. It does not block execution when args are empty; purely informational.
- `executions` (list of steps)
  - Each step is either a `run` command or a `display` message. Steps execute in order.

Supported step fields:
- `run: <command>` Runs a command string (no leading slash needed). You can use argument placeholders and PAPI/built-in placeholders here.
- `display: <message>` Sends a message to the executor (formatting/placeholder support).
- `as: <context>` Who runs the `run` command. One of:
  - `sender` the shortcut executor
  - `console` the server console
  - `player:<name|placeholder|%%i>` a specific online player, resolved after argument and placeholder replacement (must be online)

### Arguments placeholders
- `%%0`, `%%1`, `%%2`, ... Individual argument at zero-based index
- `%%*` All arguments joined by spaces
- `%%n+` Arguments from index `n` to the end, joined by spaces (e.g., `%%1+`)

Examples:
- `/echo hello world` with `run: say %%*` → runs `say hello world`
- `/msg Steve hello there` with `run: msg %%0 %%1+` → runs `msg Steve hello there`

### Built-in placeholders (work without PAPI)
- `%player_name%` executor’s exact name (or the target player for `as: player:...`)
- `%player_uuid%` executor’s UUID (or the target player for `as: player:...`)

Built-ins are applied first. If PlaceholderAPI is installed, PAPI placeholders are then applied on top using the same player context.

### PlaceholderAPI integration (optional)
If PlaceholderAPI is present, any `%placeholder%` supported by PAPI will be resolved in both `run` and `display` steps using the most relevant player context:
- `as: sender` → the executing player
- `as: console` → the executing player (if the sender is a player) for placeholder resolution only
- `as: player:<...>` → the resolved player target

If PAPI is not installed, non-built-in placeholders remain unchanged.


## Examples

### Simple greet with message and two runs
```yaml
greet:
  permision: example.permission  # spelling per original spec also supported
  playerOnly: false
  usage: "/greet <name>"
  executions:
    - { display: "Hello, %%0!" }
    - { run: "say Greetings, %%0", as: sender }
    - { run: "say Console also greets %%0", as: console }
```

### Kill yourself (no args required)
```yaml
kms:
  playerOnly: true
  usage: /kms
  executions:
    - { run: "kill %player_name%", as: console }
    - { display: "§0[§a✓§0]§r You have been killed." }
```

### "Yapp" broadcast chat-like message with name prefix
Vanilla `tellraw` requires a valid JSON payload. Use `%%*` to capture the full message.
```yaml
yapp:
  permission: trust.trusted
  playerOnly: false
  usage: /yapp <message>
  executions:
    - { run: 'tellraw @a {"text":"<%player_name%> %%*"}', as: sender }
```

### Run as a specific player (first arg target, rest as message)
```yaml
yapp_other:
  permission: trust.trusted
  executions:
    - { run: 'tellraw @a {"text":"<%player_name%> %%1+"}', as: 'player:%%0' }
```


## Admin commands

### /cmdutilsreload
- Permission: `cmdutils.reload` (default: op)
- Action: Reloads `config.yml`, unregisters old shortcuts, registers new ones.

### /sudo
- Permission: `cmdutils.sudo` (default: op)
- Usage:
  - `/sudo <player> <command...>` → run as the specified online player
  - `/sudo <command...>` → if you are a player, run as yourself
- Behavior:
  - Temporarily grants OP to the target for the duration of the command, then restores the original OP status (try/finally safety).
  - Runs inside a sudo context so Cmdutils shortcuts bypass their internal permission checks. This ensures you can sudo a non-op into a restricted shortcut.
  - Accepts commands with or without a leading slash (both work).
  - Tab completion suggests online player names for the first argument.


## Permissions and visibility
- If a shortcut has `permission`/`permision` set, Bukkit-level permission gating is applied:
  - Players lacking the permission do not see the command in help/tab-complete and cannot execute it.
  - `/sudo` bypasses this gating for shortcuts only while the command is being executed.
- Global permissions declared by the plugin:
  - `cmdutils.reload` use `/cmdutilsreload` (default: op)
  - `cmdutils.sudo` use `/sudo` (default: op)


## Notes and best practices
- Paper does not support YAML command declarations for plugins. Admin commands and dynamic shortcuts are registered programmatically at runtime by Cmdutils.
- `tellraw` must receive valid JSON. If you see syntax errors, ensure your JSON is properly escaped inside YAML.
- `playerOnly: true` shortcuts do nothing if executed by non-players (including console).
- `as: player:<...>` requires the resolved player to be online.
- Prefer unique shortcut labels to avoid clashes with existing commands. If a label conflicts, the existing command may take precedence.


## Troubleshooting
- Error: `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`
  - Cause: Unshaded build. Solution: Use the provided shaded JAR from `build/libs` (Gradle task `build` or `shadowJar`).

- Error on startup about `JavaPlugin#getCommand` or YAML commands in Paper
  - Cause: Paper doesn’t support YAML command declarations for plugins. Cmdutils already registers commands programmatically; ensure you’re using the provided jar and do not add `commands:` to `paper-plugin.yml`.

- `%player_name%` not replaced
  - Built-ins only resolve when a Player context exists for that step. For `as: console`, built-ins use the executor if the sender is a player; otherwise no replacement occurs.
  - PlaceholderAPI placeholders require PAPI to be installed.

- `tellraw` errors like "Expected a valid unquoted string"
  - Use proper JSON and escape quotes in YAML: `'tellraw @a {"text":"message"}'`.

- Command visible to players without permission
  - Ensure the shortcut has a `permission:` key. Bukkit will hide it from tab-complete/help for players lacking the node.

- Sudo of shortcuts still says no permission
  - Ensure you’re using the latest Cmdutils version where sudo uses a bypass context. Replace the JAR and restart the server.


## Development notes
- Kotlin, shaded via Shadow plugin
- Runtime command registration per Paper guidelines
- Enhanced argument placeholders: `%%*` and `%%n+` in addition to `%%i`
- Built-in placeholders + optional PlaceholderAPI integration

Useful Gradle tasks:
- `build` produces the shaded JAR (also wired to `assemble`)
- `runServer` starts a local Paper test server (via `xyz.jpenilla.run-paper`), configured to 1.21

