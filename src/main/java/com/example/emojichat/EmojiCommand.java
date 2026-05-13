package com.example.emojichat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EmojiCommand implements TabExecutor {

    private static final String ADMIN_PERMISSION = "ntc.admin";
    private static final String LEGACY_ADMIN_PERMISSION = "emojichat.admin";

    private static final List<String> PLAYER_SUBCOMMANDS = List.of(
            "upload", "save", "sync", "list", "help"
    );
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "set");

    private final EmojiChatPlugin plugin;
    private final EmojiGui emojiGui;
    private final DynamicEmojiService dynamicEmojiService;

    public EmojiCommand(EmojiChatPlugin plugin, EmojiGui emojiGui, DynamicEmojiService dynamicEmojiService) {
        this.plugin = plugin;
        this.emojiGui = emojiGui;
        this.dynamicEmojiService = dynamicEmojiService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            return handleList(sender);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "upload" -> {
                return handleUpload(sender, args);
            }
            case "save" -> {
                return handleSave(sender, args);
            }
            case "sync" -> {
                return handleSync(sender);
            }
            case "list" -> {
                return handleList(sender);
            }
            case "reload" -> {
                return handleReload(sender);
            }
            case "set" -> {
                return handleSet(sender);
            }
            case "help" -> {
                sendHelp(sender);
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>(PLAYER_SUBCOMMANDS);
            if (hasAdminPermission(sender)) {
                subcommands.addAll(ADMIN_SUBCOMMANDS);
            }
            return filterByPrefix(subcommands, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("save") && sender instanceof Player player) {
            return filterByPrefix(dynamicEmojiService.unsavedEmojiIds(player.getUniqueId()), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterByPrefix(List<String> values, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(value);
            }
        }
        Collections.sort(result);
        return result;
    }

    private boolean handleUpload(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can upload emojis.");
            return true;
        }
        if (!player.hasPermission("emojichat.upload")) {
            player.sendMessage(Component.text("你没有上传表情的权限。", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("用法：/emoji upload <name>", NamedTextColor.YELLOW));
            return true;
        }
        Optional<UploadToken> maybeToken = dynamicEmojiService.createUploadTokenWithCooldown(player, args[1]);
        if (maybeToken.isEmpty()) {
            return true;
        }
        UploadToken token = maybeToken.get();
        String url = dynamicEmojiService.uploadUrl(token);
        player.sendMessage(Component.text("请点击链接上传表情图片：", NamedTextColor.GREEN));
        player.sendMessage(Component.text(url, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(dynamicEmojiService.clickEventForExternalUrl(url))
                .hoverEvent(Component.text(dynamicEmojiService.hoverTextForExternalUrl(url, "点击打开上传页面"), NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("上传成功后，表情会加入你的表情栏。若需要加载最新材质，请执行 /emoji sync。", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/emoji upload 与 /emoji sync 共用冷却；管理员/OP 不受限制。", NamedTextColor.DARK_GRAY));
        return true;
    }


    private boolean handleSave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can save emojis.");
            return true;
        }
        if (!player.hasPermission("emojichat.save")) {
            player.sendMessage(Component.text("你没有保存表情的权限。", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("用法：/emoji save <emojiId>", NamedTextColor.YELLOW));
            return true;
        }
        player.getScheduler().run(plugin, task -> dynamicEmojiService.saveEmojiToPlayer(player, args[1]), null);
        return true;
    }

    private boolean handleSync(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can sync packs.");
            return true;
        }
        player.getScheduler().run(plugin, task -> dynamicEmojiService.syncPlayerPackWithCooldown(player), null);
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open /emoji.");
            return true;
        }
        player.getScheduler().run(plugin, task -> emojiGui.open(player, 0), null);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage("你没有重载插件的权限。");
            return true;
        }
        plugin.reloadAll();
        sender.sendMessage("NTCEmojiChatFolia / NeoTcc 表情插件已重载。");
        return true;
    }

    private boolean handleSet(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open the emoji admin GUI.");
            return true;
        }
        if (!hasAdminPermission(sender)) {
            player.sendMessage(Component.text("你没有打开表情管理界面的权限。", NamedTextColor.RED));
            return true;
        }
        player.getScheduler().run(plugin, task -> emojiGui.openAdmin(player, 0), null);
        return true;
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.isOp() || sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(LEGACY_ADMIN_PERMISSION);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("===完整用法请移步NeoTcc玩家手册===");
        sender.sendMessage("/emoji - 打开表情 GUI");
        sender.sendMessage("/emoji list - 打开表情 GUI");
        sender.sendMessage("/emoji upload <name> - 上传自己的表情");
        sender.sendMessage("/emoji save <emojiId> - 点击别人发送的未保存表情时使用，用于保存到自己的表情栏");
        sender.sendMessage("/emoji sync - 加载当前服务器表情资源包");
        if (hasAdminPermission(sender)) {
            sender.sendMessage("/emoji set - 打开表情管理 GUI");
            sender.sendMessage("/emoji reload - 重载插件配置和表情数据");
        }
    }
}
