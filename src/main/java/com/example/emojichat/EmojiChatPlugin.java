package com.example.emojichat;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class EmojiChatPlugin extends JavaPlugin {

    private EmojiManager emojiManager;
    private DynamicEmojiService dynamicEmojiService;
    private EmojiGui emojiGui;
    private UploadServer uploadServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.emojiManager = new EmojiManager(this);
        this.emojiManager.reload();

        this.dynamicEmojiService = new DynamicEmojiService(this, emojiManager);
        this.dynamicEmojiService.reload();

        this.emojiGui = new EmojiGui(this, emojiManager, dynamicEmojiService);
        this.uploadServer = new UploadServer(this, dynamicEmojiService);
        this.uploadServer.start();

        getServer().getPluginManager().registerEvents(new EmojiChatListener(this, dynamicEmojiService), this);
        getServer().getPluginManager().registerEvents(new EmojiResourcePackListener(this, dynamicEmojiService), this);
        getServer().getPluginManager().registerEvents(emojiGui, this);

        PluginCommand emojiCommand = getCommand("emoji");
        if (emojiCommand != null) {
            EmojiCommand emojiCommandHandler = new EmojiCommand(this, emojiGui, dynamicEmojiService);
            emojiCommand.setExecutor(emojiCommandHandler);
            emojiCommand.setTabCompleter(emojiCommandHandler);
        }

        logStartupLogo();
    }

    private void logStartupLogo() {
        String serverName = getConfig().getString("settings.server-name", "NeoTcc");
        String[] logoLines = {
                "",
                "#   _____                 _ _  ____ _           _   ",
                "#  | ____|_ __ ___   ___ (_|_) / ___| |__   __ _| |_ ",
                "#  |  _| | '_ ` _ \\ / _ \\| | || |   | '_ \\ / _` | __|",
                "#  | |___| | | | | | (_) | | || |___| | | | (_| | |_ ",
                "#  |_____|_| |_| |_|\\___// |_| \\____|_| |_|\\__,_|\\__|",
                "#                      |__/             by.Lao_direct",
                ""
        };
        for (String line : logoLines) {
            getLogger().info(line);
        }
        getLogger().info(getDescription().getName() + " loaded successfully for " + serverName
                + ". Static emojis: " + emojiManager.entries().size()
                + ", dynamic emojis: " + dynamicEmojiService.allEmojis().size() + ".");
    }

    @Override
    public void onDisable() {
        if (uploadServer != null) {
            uploadServer.stop();
        }
    }

    public EmojiManager emojiManager() {
        return emojiManager;
    }

    public DynamicEmojiService dynamicEmojiService() {
        return dynamicEmojiService;
    }

    public void reloadAll() {
        reloadConfig();
        emojiManager.reload();
        dynamicEmojiService.reload();
        if (uploadServer != null) {
            uploadServer.stop();
            uploadServer.start();
        }
    }
}
