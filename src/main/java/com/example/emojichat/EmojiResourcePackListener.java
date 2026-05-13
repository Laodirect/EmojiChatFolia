package com.example.emojichat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class EmojiResourcePackListener implements Listener {

    private final JavaPlugin plugin;
    private final DynamicEmojiService dynamicEmojiService;

    public EmojiResourcePackListener(JavaPlugin plugin, DynamicEmojiService dynamicEmojiService) {
        this.plugin = plugin;
        this.dynamicEmojiService = dynamicEmojiService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("resource-pack.first-join-notice", true)) {
            return;
        }
        long delay = plugin.getConfig().getLong("resource-pack.join-delay-ticks", 40L);
        event.getPlayer().getScheduler().runDelayed(plugin, task -> dynamicEmojiService.sendFirstJoinSetupNoticeIfNeeded(event.getPlayer()), null, delay);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        String status = event.getStatus().name();
        switch (status) {
            case "ACCEPTED" -> event.getPlayer().sendMessage(Component.text("已接受全量表情资源包请求，开始下载。", NamedTextColor.GRAY));
            case "DOWNLOADED" -> event.getPlayer().sendMessage(Component.text("全量表情资源包已下载，正在应用。", NamedTextColor.GRAY));
            case "SUCCESSFULLY_LOADED" -> {
                dynamicEmojiService.markResourcePackLoaded(event.getPlayer());
                event.getPlayer().sendMessage(Component.text("全量表情资源包加载完成。", NamedTextColor.GREEN));
            }
            case "DECLINED" -> event.getPlayer().sendMessage(Component.text("你拒绝了全量表情资源包，部分表情可能只显示为方框或空白。", NamedTextColor.YELLOW));
            case "FAILED_DOWNLOAD" -> event.getPlayer().sendMessage(Component.text("全量表情资源包下载失败。请检查 upload-server.public-url 是否是玩家客户端可访问的公网地址。", NamedTextColor.RED));
            case "FAILED_RELOAD" -> event.getPlayer().sendMessage(Component.text("全量表情资源包应用失败。请检查资源包格式或客户端日志。", NamedTextColor.RED));
            case "DISCARDED" -> event.getPlayer().sendMessage(Component.text("全量表情资源包请求被客户端丢弃，可能被新的资源包请求覆盖。请稍后再执行 /emoji sync。", NamedTextColor.YELLOW));
            case "INVALID_URL" -> event.getPlayer().sendMessage(Component.text("全量表情资源包地址无效。请检查 upload-server.public-url。", NamedTextColor.RED));
            case "FAILED" -> event.getPlayer().sendMessage(Component.text("全量表情资源包加载失败。请检查地址、SHA-1 和客户端资源包设置。", NamedTextColor.RED));
            default -> {
                // Other future statuses are intentionally ignored.
            }
        }
    }
}
