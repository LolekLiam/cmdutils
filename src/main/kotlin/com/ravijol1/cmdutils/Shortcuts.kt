package com.ravijol1.cmdutils

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.logging.Level

/**
 * Sudo execution context flag. When active, shortcut permission checks are bypassed.
 */
object SudoContext {
    private val flag: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    fun isActive(): Boolean = flag.get() == true
    fun <T> runAsSudo(block: () -> T): T {
        val prev = flag.get()
        flag.set(true)
        return try {
            block()
        } finally {
            flag.set(prev)
        }
    }
}

/**
 * Models a single execution step in a shortcut.
 * Only one of [run] or [display] should be present.
 */
data class ExecutionStep(
    val run: String? = null,
    val display: String? = null,
    val asWho: String? = null,
)

/**
 * Represents one shortcut command configuration.
 */
data class Shortcut(
    val label: String,
    val permission: String? = null,
    val playerOnly: Boolean = false,
    val usage: String? = null,
    val steps: List<ExecutionStep> = emptyList()
)

/**
 * Utility class to load shortcuts from config.yml
 */
object ShortcutConfigLoader {
    fun load(plugin: JavaPlugin): List<Shortcut> {
        val cfg = plugin.config
        val result = mutableListOf<Shortcut>()
        for (key in cfg.getKeys(false)) {
            val section = cfg.getConfigurationSection(key) ?: continue
            val permission = section.getString("permision") // intentionally spelled per spec
                ?: section.getString("permission") // also accept correctly spelled
            val playerOnly = section.getBoolean("playerOnly", false)
            val usage = section.getString("usage")

            val stepList = mutableListOf<ExecutionStep>()
            val execs = section.getList("executions")
            if (execs != null) {
                for (raw in execs) {
                    when (raw) {
                        is Map<*, *> -> {
                            val run = raw["run"]?.toString()
                            val display = raw["display"]?.toString()
                            val asWho = raw["as"]?.toString()
                            stepList.add(ExecutionStep(run = run, display = display, asWho = asWho))
                        }
                        else -> plugin.logger.warning("Unsupported execution entry in $key: $raw")
                    }
                }
            }
            result.add(Shortcut(label = key, permission = permission, playerOnly = playerOnly, usage = usage, steps = stepList))
        }
        return result
    }
}

/**
 * Replaces argument placeholders like %%0, %%1 with provided args.
 * Extended support:
 *  - %%*   -> all arguments joined by spaces
 *  - %%n+  -> arguments from index n to the end, joined by spaces (e.g., %%1+)
 */
private fun replaceArgPlaceholders(input: String, args: Array<out String>): String {
    var out = input

    // Replace join-all placeholder first
    if (out.contains("%%*")) {
        out = out.replace("%%*", args.joinToString(" "))
    }

    // Replace ranged placeholders like %%n+
    val rangeRegex = Regex("%%(\\d+)\\+")
    out = rangeRegex.replace(out) { m ->
        val start = m.groupValues[1].toInt()
        if (start in args.indices) args.drop(start).joinToString(" ") else ""
    }

    // Replace from highest index to lowest to avoid overlapping issues (e.g., %%10 containing %%1)
    for (i in args.indices.reversed()) {
        out = out.replace("%%$i", args[i])
    }
    return out
}

/**
 * Applies simple built-in placeholders even if PlaceholderAPI is not present.
 * Currently supports:
 *   %player_name%  -> player's exact name
 *   %player_uuid%  -> player's UUID
 */
private fun applyBuiltInPlaceholders(text: String, player: Player?): String {
    if (player == null) return text
    var out = text
    out = out.replace("%player_name%", player.name)
    out = out.replace("%player_uuid%", player.uniqueId.toString())
    return out
}

/**
 * Applies PlaceholderAPI placeholders if available and a Player context is provided.
 */
private fun applyPapiIfAvailable(text: String, player: Player?): String {
    return if (player != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
        try {
            PlaceholderAPI.setPlaceholders(player, text)
        } catch (ex: Throwable) {
            // Fail gracefully if PAPI errors
            text
        }
    } else text
}

/**
 * A dynamic command registered at runtime for a specific shortcut.
 */
class ShortcutCommand(
    private val plugin: JavaPlugin,
    private val shortcut: Shortcut
) : Command(shortcut.label) {

    init {
        // Set Bukkit-level permission for visibility and pre-dispatch gating.
        // We still bypass this under sudo by overriding testPermission methods below.
        if (!shortcut.permission.isNullOrBlank()) {
            this.permission = shortcut.permission
        }
        this.usageMessage = shortcut.usage ?: ""
        this.description = "Shortcut command"
        this.setPermissionMessage("You do not have permission to use this command.")
    }

    override fun testPermissionSilent(target: CommandSender): Boolean {
        // Allow when running under sudo context
        if (SudoContext.isActive()) return true
        val perm = this.permission
        return perm.isNullOrBlank() || target.hasPermission(perm)
    }

    override fun testPermission(target: CommandSender): Boolean {
        val ok = testPermissionSilent(target)
        if (!ok) {
            val msg = permissionMessage ?: "You do not have permission to use this command."
            target.sendMessage(msg)
        }
        return ok
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        // Respect playerOnly; sudo still requires a player executor if playerOnly is true
        if (shortcut.playerOnly && sender !is Player) {
            // silently ignore per spec: "returns instantly"
            return true
        }

        // Do not block execution when args are empty; 'usage' is informational only.
        // If you want to show usage as a hint when no args are provided, uncomment the next two lines.
        // if (!shortcut.usage.isNullOrBlank() && args.isEmpty()) sender.sendMessage("§eUsage: ${shortcut.usage}")

        performExecutions(sender, args)
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        // We won't implement complex tab-complete; return empty list.
        return mutableListOf()
    }

    private fun performExecutions(sender: CommandSender, args: Array<out String>) {
        for (step in shortcut.steps) {
            try {
                when {
                    step.display != null -> doDisplay(step.display, sender, args)
                    step.run != null -> doRun(step.run, step.asWho ?: "sender", sender, args)
                }
            } catch (ex: Throwable) {
                plugin.logger.log(Level.WARNING, "Error running shortcut '${shortcut.label}' step: ${ex.message}", ex)
            }
        }
    }

    private fun doDisplay(text: String, sender: CommandSender, args: Array<out String>) {
        var msg = replaceArgPlaceholders(text, args)
        val player = if (sender is Player) sender else null
        // Built-ins first, then PAPI if available
        msg = applyBuiltInPlaceholders(msg, player)
        msg = applyPapiIfAvailable(msg, player)
        sender.sendMessage(msg)
    }

    private fun doRun(rawCommand: String, asWho: String, sender: CommandSender, args: Array<out String>) {
        // First, apply argument placeholders (%%i)
        val base = replaceArgPlaceholders(rawCommand, args)
        val senderPlayer = sender as? Player

        val lower = asWho.lowercase(Locale.ROOT)
        when {
            lower == "sender" -> {
                var cmd = applyBuiltInPlaceholders(base, senderPlayer)
                cmd = applyPapiIfAvailable(cmd, senderPlayer)
                Bukkit.dispatchCommand(sender, cmd)
            }
            lower == "console" -> {
                // For console-run commands, still resolve placeholders using the executor if available
                var cmd = applyBuiltInPlaceholders(base, senderPlayer)
                cmd = applyPapiIfAvailable(cmd, senderPlayer)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            }
            lower.startsWith("player:") -> {
                val spec = asWho.substringAfter(":")
                var playerName = replaceArgPlaceholders(spec, args)
                // Resolve playerName using the sender as context first (if contains placeholders)
                playerName = applyBuiltInPlaceholders(playerName, senderPlayer)
                playerName = applyPapiIfAvailable(playerName, senderPlayer)
                val target = Bukkit.getPlayerExact(playerName)
                if (target != null) {
                    // Re-resolve placeholders for the command using the target player's context
                    var cmd = applyBuiltInPlaceholders(base, target)
                    cmd = applyPapiIfAvailable(cmd, target)
                    Bukkit.dispatchCommand(target, cmd)
                } else {
                    sender.sendMessage("§cTarget player '$playerName' is not online.")
                }
            }
            else -> {
                // default to sender if unknown directive
                var cmd = applyBuiltInPlaceholders(base, senderPlayer)
                cmd = applyPapiIfAvailable(cmd, senderPlayer)
                Bukkit.dispatchCommand(sender, cmd)
            }
        }
    }
}

class ShortcutRegistry(private val plugin: JavaPlugin) {
    private val registered = mutableListOf<ShortcutCommand>()

    fun registerAll(shortcuts: List<Shortcut>) {
        val map = getCommandMap() ?: return
        for (sc in shortcuts) {
            val cmd = ShortcutCommand(plugin, sc)
            // Use plugin name as fallback fallback prefix
            val fallback = plugin.name.lowercase(Locale.ROOT)
            map.register(fallback, cmd)
            registered.add(cmd)
        }
        plugin.logger.info("Registered ${registered.size} shortcut command(s).")
    }

    fun unregisterAll() {
        val map = getCommandMap() ?: return
        for (cmd in registered) {
            try {
                cmd.unregister(map)
            } catch (_: Throwable) {
                // ignore
            }
        }
        registered.clear()
    }

    private fun getCommandMap(): SimpleCommandMap? {
        return try {
            val server = Bukkit.getServer()
            val field = server.javaClass.getDeclaredField("commandMap")
            field.isAccessible = true
            field.get(server) as? SimpleCommandMap
        } catch (ex: Throwable) {
            plugin.logger.log(Level.SEVERE, "Failed to access CommandMap: ${ex.message}", ex)
            null
        }
    }
}
