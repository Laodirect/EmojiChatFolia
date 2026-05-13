package com.example.emojichat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EmojiGui implements Listener {

    private static final int PLAYER_INVENTORY_SIZE = 27;
    private static final int PLAYER_EMOJI_SLOTS_PER_PAGE = 18;
    private static final int PLAYER_PREV_SLOT = 18;
    private static final int PLAYER_SEND_MODE_SLOT = 21;
    private static final int PLAYER_GUIDE_SLOT = 22;
    private static final int PLAYER_UPLOAD_SLOT = 23;
    private static final int PLAYER_NEXT_SLOT = 26;

    private static final int ADMIN_INVENTORY_SIZE = 54;
    private static final int ADMIN_EMOJI_SLOTS_PER_PAGE = 45;

    private final JavaPlugin plugin;
    private final EmojiManager emojiManager;
    private final DynamicEmojiService dynamicEmojiService;
    private final NamespacedKey tokenKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey emojiIdKey;
    private final NamespacedKey savedKey;
    private final NamespacedKey adminKey;

    public EmojiGui(JavaPlugin plugin, EmojiManager emojiManager, DynamicEmojiService dynamicEmojiService) {
        this.plugin = plugin;
        this.emojiManager = emojiManager;
        this.dynamicEmojiService = dynamicEmojiService;
        this.tokenKey = new NamespacedKey(plugin, "emoji_token");
        this.actionKey = new NamespacedKey(plugin, "emoji_action");
        this.emojiIdKey = new NamespacedKey(plugin, "emoji_id");
        this.savedKey = new NamespacedKey(plugin, "emoji_saved");
        this.adminKey = new NamespacedKey(plugin, "emoji_admin");
    }

    public void open(Player player, int requestedPage) {
        List<GuiEmoji> entries = collectPlayerEntries(player);
        openInventory(player, requestedPage, false, entries);
    }

    public void openAdmin(Player player, int requestedPage) {
        List<GuiEmoji> entries = collectAdminEntries();
        openInventory(player, requestedPage, true, entries);
    }

    private void openInventory(Player player, int requestedPage, boolean adminMode, List<GuiEmoji> entries) {
        int emojiSlotsPerPage = adminMode ? ADMIN_EMOJI_SLOTS_PER_PAGE : PLAYER_EMOJI_SLOTS_PER_PAGE;
        int inventorySize = adminMode ? ADMIN_INVENTORY_SIZE : PLAYER_INVENTORY_SIZE;
        int pageCount = Math.max(1, (int) Math.ceil(entries.size() / (double) emojiSlotsPerPage));
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        boolean directSendMode = !adminMode && dynamicEmojiService.guiDirectSendMode(player.getUniqueId());

        EmojiHolder holder = new EmojiHolder(page, adminMode);
        String titleText = adminMode
                ? "表情管理 第 " + (page + 1) + "/" + pageCount + " 页"
                : emojiManager.guiTitle() + " 第 " + (page + 1) + "/" + pageCount + " 页";
        Inventory inventory = Bukkit.createInventory(holder, inventorySize,
                Component.text(titleText, adminMode ? NamedTextColor.DARK_RED : NamedTextColor.DARK_PURPLE));
        holder.setInventory(inventory);

        int start = page * emojiSlotsPerPage;
        int end = Math.min(entries.size(), start + emojiSlotsPerPage);
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, createEmojiItem(entries.get(i), adminMode, directSendMode));
        }

        if (adminMode) {
            inventory.setItem(49, createAdminGuideItem(entries.size()));
            if (page > 0) {
                inventory.setItem(45, createActionItem(Material.ARROW, "上一页", "prev"));
            }
            if (page + 1 < pageCount) {
                inventory.setItem(53, createActionItem(Material.ARROW, "下一页", "next"));
            }
        } else {
            inventory.setItem(PLAYER_SEND_MODE_SLOT, createSendModeItem(directSendMode));
            inventory.setItem(PLAYER_GUIDE_SLOT, createGuideItem(entries.size(), directSendMode));
            inventory.setItem(PLAYER_UPLOAD_SLOT, createUploadItem());
            if (page > 0) {
                inventory.setItem(PLAYER_PREV_SLOT, createActionItem(Material.ARROW, "上一页", "prev"));
            }
            if (page + 1 < pageCount) {
                inventory.setItem(PLAYER_NEXT_SLOT, createActionItem(Material.ARROW, "下一页", "next"));
            }
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EmojiHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action != null) {
            switch (action) {
                case "guide" -> {
                    if (holder.adminMode()) {
                        sendAdminGuide(player);
                    } else {
                        sendGuide(player);
                    }
                    return;
                }
                case "prev" -> {
                    reopen(player, holder.adminMode(), holder.page() - 1);
                    return;
                }
                case "next" -> {
                    reopen(player, holder.adminMode(), holder.page() + 1);
                    return;
                }
                case "toggle_send_mode" -> {
                    if (!holder.adminMode()) {
                        dynamicEmojiService.toggleGuiDirectSendMode(player);
                        reopen(player, false, holder.page());
                    }
                    return;
                }
                case "upload" -> {
                    if (!holder.adminMode()) {
                        player.closeInventory();
                        sendUploadCommandHint(player);
                    }
                    return;
                }
                default -> {
                    return;
                }
            }
        }

        String token = meta.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
        String emojiId = meta.getPersistentDataContainer().get(emojiIdKey, PersistentDataType.STRING);
        if ((token == null || token.isBlank()) && (emojiId == null || emojiId.isBlank())) {
            return;
        }

        if (holder.adminMode()) {
            handleAdminEmojiClick(player, holder.page(), event, token, emojiId);
            return;
        }

        handlePlayerEmojiClick(player, holder.page(), event, token, emojiId);
    }

    private void handlePlayerEmojiClick(Player player, int page, InventoryClickEvent event, String token, String emojiId) {
        if (event.isRightClick()) {
            if (emojiId == null || emojiId.isBlank()) {
                player.sendMessage(Component.text("基础表情不能从表情栏移除。", NamedTextColor.YELLOW));
                return;
            }
            player.closeInventory();
            player.getScheduler().run(plugin, task -> {
                boolean removed = dynamicEmojiService.removeEmojiFromPlayer(player, emojiId);
                if (removed && player.isOnline()) {
                    open(player, page);
                }
            }, null);
            return;
        }

        if (!event.isLeftClick()) {
            return;
        }

        player.closeInventory();
        if (dynamicEmojiService.guiDirectSendMode(player.getUniqueId())) {
            sendEmojiDirectly(player, token);
            return;
        }

        player.sendMessage(Component.text("发送方式：在聊天中输入 ", NamedTextColor.GRAY)
                .append(Component.text(token, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand(token))
                        .hoverEvent(Component.text("点击填入聊天框", NamedTextColor.YELLOW)))
                .append(Component.text(" 即可发送。", NamedTextColor.GRAY)));

        if (emojiId != null) {
            player.sendMessage(Component.text("右键 GUI 内该表情可从你的表情栏移除。", NamedTextColor.DARK_GRAY));
        }
    }

    private void sendEmojiDirectly(Player player, String token) {
        player.getScheduler().run(plugin, task -> {
            try {
                player.getClass().getMethod("chat", String.class).invoke(player, token);
            } catch (ReflectiveOperationException exception) {
                player.sendMessage(Component.text("当前服务端不支持从 GUI 直接发送聊天，请点击下方文本填入后发送：", NamedTextColor.YELLOW));
                player.sendMessage(Component.text(token, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.suggestCommand(token))
                        .hoverEvent(Component.text("点击填入聊天框", NamedTextColor.YELLOW)));
            }
        }, null);
    }

    private void handleAdminEmojiClick(Player player, int page, InventoryClickEvent event, String token, String emojiId) {
        if (emojiId == null || emojiId.isBlank()) {
            return;
        }
        if (event.isRightClick()) {
            player.closeInventory();
            player.getScheduler().run(plugin, task -> {
                boolean deleted = dynamicEmojiService.deleteEmojiByAdmin(player, emojiId);
                if (deleted && player.isOnline()) {
                    openAdmin(player, page);
                }
            }, null);
            return;
        }

        if (!event.isLeftClick()) {
            return;
        }

        dynamicEmojiService.getEmoji(emojiId).ifPresentOrElse(emoji -> {
            player.closeInventory();
            player.sendMessage(Component.text("表情信息：", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("ID: ", NamedTextColor.GRAY).append(Component.text(emoji.id(), NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Token: ", NamedTextColor.GRAY).append(Component.text(token, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand(token))
                    .hoverEvent(Component.text("点击填入聊天框", NamedTextColor.YELLOW))));
            player.sendMessage(Component.text("名称: ", NamedTextColor.GRAY).append(Component.text(emoji.name(), NamedTextColor.WHITE)));
            player.sendMessage(Component.text("上传者: ", NamedTextColor.GRAY).append(Component.text(emoji.ownerName(), NamedTextColor.WHITE)));
            player.sendMessage(Component.text("保存人数: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(dynamicEmojiService.savedPlayerCount(emoji.id())), NamedTextColor.WHITE)));
            player.sendMessage(Component.text("右键管理 GUI 内该表情可全局删除。", NamedTextColor.RED));
        }, () -> player.sendMessage(Component.text("这个表情不存在或已被删除。", NamedTextColor.RED)));
    }

    private void reopen(Player player, boolean adminMode, int page) {
        if (adminMode) {
            openAdmin(player, page);
        } else {
            open(player, page);
        }
    }

    private List<GuiEmoji> collectPlayerEntries(Player player) {
        List<GuiEmoji> result = new ArrayList<>();
        for (EmojiEntry entry : emojiManager.entries()) {
            Component preview = emojiManager.componentFor(entry);
            result.add(new GuiEmoji(entry.token(), preview, entry.description(), entry.material(), false, null, true, "基础表情", ""));
        }

        PlayerEmojiLibrary library = dynamicEmojiService.library(player.getUniqueId());
        List<DynamicEmoji> dynamic = new ArrayList<>(dynamicEmojiService.allEmojis());
        dynamic.sort(Comparator.comparing(DynamicEmoji::name, String.CASE_INSENSITIVE_ORDER));
        for (DynamicEmoji emoji : dynamic) {
            boolean saved = library.has(emoji.id());
            if (!saved) {
                continue;
            }
            Component preview = Component.text(emoji.glyph(), NamedTextColor.WHITE).font(dynamicEmojiService.dynamicFontKey());
            String label = "自定义表情 / 来自 " + emoji.ownerName();
            result.add(new GuiEmoji(emoji.token(), preview, emoji.description(), Material.PAPER, true, emoji.id(), true, label, ""));
        }
        return result;
    }

    private List<GuiEmoji> collectAdminEntries() {
        List<DynamicEmoji> dynamic = new ArrayList<>(dynamicEmojiService.allEmojis());
        dynamic.sort(Comparator.comparing(DynamicEmoji::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DynamicEmoji::id, String.CASE_INSENSITIVE_ORDER));
        List<GuiEmoji> result = new ArrayList<>();
        for (DynamicEmoji emoji : dynamic) {
            Component preview = Component.text(emoji.glyph(), NamedTextColor.WHITE).font(dynamicEmojiService.dynamicFontKey());
            int savedCount = dynamicEmojiService.savedPlayerCount(emoji.id());
            String label = "注册表情 / 保存人数 " + savedCount;
            String extra = "上传者：" + emoji.ownerName() + " | 名称：" + emoji.name() + " | SHA256：" + shortSha256(emoji.sha256());
            result.add(new GuiEmoji(emoji.token(), preview, emoji.description(), Material.NAME_TAG, true, emoji.id(), true, label, extra));
        }
        return result;
    }

    private String shortSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return "未知";
        }
        return sha256.substring(0, Math.min(sha256.length(), 12));
    }

    private ItemStack createEmojiItem(GuiEmoji entry, boolean adminMode, boolean directSendMode) {
        ItemStack item = new ItemStack(entry.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(entry.token(), NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.space())
                .append(resetEmojiPreview(entry.preview()))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(entry.typeLabel(), adminMode ? NamedTextColor.RED : (entry.saved() ? NamedTextColor.DARK_PURPLE : NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("说明：" + entry.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("聊天输入：", NamedTextColor.GRAY).append(Component.text(entry.token(), NamedTextColor.YELLOW)).decoration(TextDecoration.ITALIC, false));
        if (entry.dynamic() && entry.emojiId() != null) {
            lore.add(Component.text("ID：" + entry.emojiId(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (!entry.extraInfo().isBlank()) {
            lore.add(Component.text(entry.extraInfo(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (adminMode) {
            lore.add(Component.text("左键：查看信息 / 填入 token", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("右键：全局删除该表情", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else if (entry.dynamic()) {
            lore.add(Component.text(directSendMode ? "左键：直接发送该表情" : "左键：发送文本互动提示", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("右键：从你的表情栏移除", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text(directSendMode ? "左键：直接发送该表情" : "左键：发送文本互动提示", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("基础表情不可移除。", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, entry.token());
        meta.getPersistentDataContainer().set(savedKey, PersistentDataType.STRING, Boolean.toString(entry.saved()));
        meta.getPersistentDataContainer().set(adminKey, PersistentDataType.STRING, Boolean.toString(adminMode));
        if (entry.dynamic() && entry.emojiId() != null) {
            meta.getPersistentDataContainer().set(emojiIdKey, PersistentDataType.STRING, entry.emojiId());
        }

        item.setItemMeta(meta);
        return item;
    }

    private Component resetEmojiPreview(Component preview) {
        // Add a legacy reset marker plus explicit component style reset so GUI text color/bold does not tint bitmap emojis.
        return Component.text("§r", NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false)
                .append(preview.color(NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.ITALIC, false));
    }

    private ItemStack createSendModeItem(boolean directSendMode) {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("发送方式：" + (directSendMode ? "直接发送" : "文本互动提示"), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("当前模式：" + (directSendMode ? "点击表情后直接发送该表情。" : "点击表情后发送可点击的文本互动提示。"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("左键：切换发送方式。", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "toggle_send_mode");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createUploadItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("上传表情", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("点击后会提供 /emoji upload 命令补全。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("需要在补全的命令后输入此表情名称。", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("示例：/emoji upload smile", NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("上传命令和 /emoji sync 共用 10 分钟冷却。", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "upload");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGuideItem(int count, boolean directSendMode) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("如何使用服务器表情", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("当前列表共有 " + count + " 个表情。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("普通列表为 27 格：上方 18 格显示表情，底部为功能按钮。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("当前左键模式：" + (directSendMode ? "直接发送表情。" : "发送文本互动提示。"), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("右键自定义表情：从你的表情栏移除。", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.text("每名玩家最多保存 " + dynamicEmojiService.maxSavedEmojisPerPlayer() + " 个自定义表情。", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("/emoji sync 手动获取服务器表情资源包。", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "guide");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createAdminGuideItem(int count) {
        ItemStack item = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("表情管理界面", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("当前注册动态表情：" + count + " 个。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("左键表情：查看详细信息。", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("右键表情：从全局注册表和所有玩家表情栏删除。", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.text("命令：/emoji set", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "guide");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createActionItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private void sendUploadCommandHint(Player player) {
        Component command = Component.text("/emoji upload ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand("/emoji upload "))
                .hoverEvent(Component.text("点击补全上传命令，然后在后面输入表情名称", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("上传表情：请点击 ", NamedTextColor.GREEN)
                .append(command)
                .append(Component.text("，并在命令后输入此表情名称。", NamedTextColor.GREEN)));
        player.sendMessage(Component.text("示例：/emoji upload smile", NamedTextColor.GRAY));
    }

    private void sendGuide(Player player) {
        boolean directSendMode = dynamicEmojiService.guiDirectSendMode(player.getUniqueId());
        player.sendMessage(Component.text("服务器表情使用方法：", NamedTextColor.GOLD));
        player.sendMessage(Component.text("1. 普通 /emoji GUI 为 27 格，上方 18 格显示表情。", NamedTextColor.GRAY));
        player.sendMessage(Component.text("2. 第 22 格可切换发送方式，当前为：" + (directSendMode ? "直接发送表情。" : "文本互动提示。"), NamedTextColor.GRAY));
        player.sendMessage(Component.text("3. 第 24 格为上传表情按钮，点击后会提供 /emoji upload 命令补全。", NamedTextColor.GRAY));
        player.sendMessage(Component.text("4. 右键你的自定义表情，可从个人表情栏移除。", NamedTextColor.GRAY));
        player.sendMessage(Component.text("5. /emoji upload 与 /emoji sync 共用冷却；管理员/OP 不受限制。", NamedTextColor.YELLOW));
    }

    private void sendAdminGuide(Player player) {
        player.sendMessage(Component.text("表情管理界面：", NamedTextColor.RED, TextDecoration.BOLD));
        player.sendMessage(Component.text("左键查看表情信息；右键全局删除该表情。", NamedTextColor.GRAY));
        player.sendMessage(Component.text("删除会同步移除所有玩家保存记录，并重建资源包。", NamedTextColor.YELLOW));
    }

    private record GuiEmoji(String token, Component preview, String description, Material material, boolean dynamic, String emojiId, boolean saved, String typeLabel, String extraInfo) {
    }

    public static final class EmojiHolder implements InventoryHolder {
        private final int page;
        private final boolean adminMode;
        private Inventory inventory;

        private EmojiHolder(int page, boolean adminMode) {
            this.page = page;
            this.adminMode = adminMode;
        }

        public int page() {
            return page;
        }

        public boolean adminMode() {
            return adminMode;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
