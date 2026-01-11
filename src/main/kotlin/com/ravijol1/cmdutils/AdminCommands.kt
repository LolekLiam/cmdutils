package com.ravijol1.cmdutils

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * /cmdutilsreload implemented as a runtime-registered Command
 */
class CmdutilsReloadCommand(private val plugin: Cmdutils) : Command("cmdutilsreload") {
    init {
        description = "Reload Cmdutils shortcuts from config.yml"
        usageMessage = "/cmdutilsreload"
        permission = "cmdutils.reload"
        permissionMessage = "You do not have permission to use this command."
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("cmdutils.reload")) {
            sender.sendMessage("§cYou do not have permission to use this command.")
            return true
        }
        plugin.reloadShortcuts()
        sender.sendMessage("§aCmdutils shortcuts reloaded.")
        return true
    }
}

/**
 * /sudo implemented as a runtime-registered Command with simple tab completion.
 * Usage:
 *  - /sudo <player> <command...>
 *  - /sudo <command...>   (self, if sender is a player)
 */
class SudoCommand(private val plugin: JavaPlugin) : Command("sudo") {
    init {
        description = "Execute a command as another player with temporary OP"
        usageMessage = "/sudo <player> <command...> or /sudo <command...>"
        permission = "cmdutils.sudo"
        permissionMessage = "You do not have permission to use this command."
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("cmdutils.sudo")) {
            sender.sendMessage("§cYou do not have permission to use this command.")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("§eUsage: /sudo <player> <command...> or /sudo <command...> (self)")
            return true
        }

        val target: Player
        var cmdToRun: String

        val firstArgAsPlayer = Bukkit.getPlayerExact(args[0])
        if (firstArgAsPlayer != null) {
            target = firstArgAsPlayer
            if (args.size < 2) {
                sender.sendMessage("§eUsage: /sudo <player> <command...>")
                return true
            }
            cmdToRun = args.drop(1).joinToString(" ")
        } else {
            if (sender !is Player) {
                sender.sendMessage("§cConsole must specify a target player: /sudo <player> <command...>")
                return true
            }
            target = sender
            cmdToRun = args.joinToString(" ")
        }

        // Strip optional leading slash from the sub-command
        if (cmdToRun.startsWith("/")) {
            cmdToRun = cmdToRun.removePrefix("/")
        }

        // Temporarily grant OP, run the command as the target, then restore
        val wasOp = target.isOp
        try {
            if (!wasOp) target.isOp = true
            // Run under sudo context so Cmdutils shortcuts bypass their internal permission checks
            SudoContext.runAsSudo {
                Bukkit.dispatchCommand(target, cmdToRun)
            }
            sender.sendMessage("§aExecuted as ${target.name}: /${cmdToRun}")
        } catch (ex: Throwable) {
            sender.sendMessage("§cFailed to execute command as ${target.name}: ${ex.message}")
            plugin.logger.warning("/sudo execution failed: ${ex.message}")
        } finally {
            if (!wasOp && target.isOnline) {
                try { target.isOp = false } catch (_: Throwable) {}
            }
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        return if (args.size == 1) {
            val prefix = args[0].lowercase()
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(prefix) }.toMutableList()
        } else mutableListOf()
    }
}
