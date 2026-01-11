package com.ravijol1.cmdutils

import org.bukkit.plugin.java.JavaPlugin

class Cmdutils : JavaPlugin() {

    private lateinit var registry: ShortcutRegistry

    override fun onEnable() {
        // Save default config if not present
        saveDefaultConfig()
        // Load shortcuts and register commands
        registry = ShortcutRegistry(this)
        reloadShortcuts()

        // Register fixed admin commands programmatically (Paper does not support YAML commands)
        try {
            val server = server
            val field = server.javaClass.getDeclaredField("commandMap")
            field.isAccessible = true
            val map = field.get(server) as? org.bukkit.command.SimpleCommandMap
            val fallback = name.lowercase()
            if (map != null) {
                map.register(fallback, CmdutilsReloadCommand(this))
                map.register(fallback, SudoCommand(this))
            } else {
                logger.severe("Failed to access CommandMap to register admin commands.")
            }
        } catch (t: Throwable) {
            logger.severe("Error registering admin commands: ${t.message}")
        }

        logger.info("Cmdutils enabled. Loaded dynamic command shortcuts from config.yml")
    }

    override fun onDisable() {
        if (this::registry.isInitialized) {
            registry.unregisterAll()
        }
        logger.info("Cmdutils disabled.")
    }

    fun reloadShortcuts() {
        if (this::registry.isInitialized) {
            registry.unregisterAll()
        }
        reloadConfig()
        val shortcuts = ShortcutConfigLoader.load(this)
        registry.registerAll(shortcuts)
    }
}
