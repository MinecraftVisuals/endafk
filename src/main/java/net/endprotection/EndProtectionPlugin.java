package net.endprotection;

import org.bukkit.plugin.java.JavaPlugin;

public final class EndProtectionPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new EndProtection(this), this);
        getLogger().info("EndProtection enabled.");
    }
}
