package com.example.emojichat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.ComponentRenderer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class EmojiChatListener implements Listener {

    private final JavaPlugin plugin;
    private final DynamicEmojiService dynamicEmojiService;

    public EmojiChatListener(JavaPlugin plugin, DynamicEmojiService dynamicEmojiService) {
        this.plugin = plugin;
        this.dynamicEmojiService = dynamicEmojiService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        if (!sender.hasPermission("emojichat.chat")) {
            return;
        }

        var originalRenderer = event.renderer();
        event.renderer((source, sourceDisplayName, message, viewer) -> {
            Component formatted = originalRenderer.render(source, sourceDisplayName, message, viewer);
            return dynamicEmojiService.renderComponentForViewer(
                    sender.getUniqueId(),
                    viewer instanceof Player player ? player.getUniqueId() : null,
                    formatted
            );
        });
    }
}
