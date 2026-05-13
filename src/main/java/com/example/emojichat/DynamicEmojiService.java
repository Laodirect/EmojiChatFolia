package com.example.emojichat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class DynamicEmojiService {

    private final JavaPlugin plugin;
    private final EmojiManager staticEmojiManager;
    private final Map<String, DynamicEmoji> emojisById = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerEmojiLibrary> librariesByPlayer = new ConcurrentHashMap<>();
    private final Map<String, UploadToken> uploadTokens = new ConcurrentHashMap<>();
    private final Map<UUID, Long> manualSyncCooldownUntilMillis = new ConcurrentHashMap<>();
    private final Map<String, Integer> savedCounts = new ConcurrentHashMap<>();
    private PersonalPackBuilder packBuilder;
    private volatile PersonalPackBuilder.PackResult cachedServerPackResult;
    private volatile boolean serverPackDirty = true;

    private int firstCodePoint;
    private int lastCodePoint;
    private Key personalFontKey;
    private String packPublicBaseUrl;

    public DynamicEmojiService(JavaPlugin plugin, EmojiManager staticEmojiManager) {
        this.plugin = plugin;
        this.staticEmojiManager = staticEmojiManager;
    }

    public void reload() {
        this.firstCodePoint = Integer.parseInt(plugin.getConfig().getString("personal-emojis.codepoint-start", "E200"), 16);
        this.lastCodePoint = Integer.parseInt(plugin.getConfig().getString("personal-emojis.codepoint-end", "F8FF"), 16);
        this.personalFontKey = parseKey(plugin.getConfig().getString("personal-emojis.font-key", "emojichat:personal"));
        this.packPublicBaseUrl = normalizePublicUrl(plugin.getConfig().getString("upload-server.public-url", "127.0.0.1:8123"));
        this.packBuilder = new PersonalPackBuilder(plugin, this);

        emojisById.clear();
        librariesByPlayer.clear();
        uploadTokens.clear();
        manualSyncCooldownUntilMillis.clear();
        savedCounts.clear();
        cachedServerPackResult = null;
        serverPackDirty = true;

        try {
            loadAllEmojis();
            loadAllPlayerLibraries();
            rebuildServerPackSilently();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load dynamic emojis", exception);
        }
    }

    public Collection<DynamicEmoji> allEmojis() {
        return List.copyOf(emojisById.values());
    }

    public Optional<DynamicEmoji> getEmoji(String id) {
        return Optional.ofNullable(emojisById.get(id));
    }

    public List<String> allEmojiIds() {
        return emojisById.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public int maxSavedEmojisPerPlayer() {
        return Math.max(1, plugin.getConfig().getInt("personal-emojis.max-saved-per-player", 18));
    }

    public int savedPlayerCount(String emojiId) {
        return savedCounts.getOrDefault(emojiId, 0);
    }

    public List<String> savedEmojiIds(UUID playerUuid) {
        return library(playerUuid).emojiGlyphs().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> unsavedEmojiIds(UUID playerUuid) {
        PlayerEmojiLibrary library = library(playerUuid);
        return emojisById.keySet().stream()
                .filter(id -> !library.has(id))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<DynamicEmoji> usableEmojis(UUID playerUuid) {
        PlayerEmojiLibrary library = library(playerUuid);
        List<DynamicEmoji> result = new ArrayList<>();
        for (String emojiId : library.emojiGlyphs().keySet()) {
            DynamicEmoji emoji = emojisById.get(emojiId);
            if (emoji != null) {
                result.add(emoji);
            }
        }
        result.sort(Comparator.comparing(DynamicEmoji::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public PlayerEmojiLibrary library(UUID playerUuid) {
        return librariesByPlayer.computeIfAbsent(playerUuid, uuid -> new PlayerEmojiLibrary(uuid, firstCodePoint));
    }

    public Key dynamicFontKey() {
        return personalFontKey;
    }

    public synchronized boolean guiDirectSendMode(UUID playerUuid) {
        return library(playerUuid).guiDirectSendMode();
    }

    public synchronized boolean toggleGuiDirectSendMode(Player player) {
        PlayerEmojiLibrary library = library(player.getUniqueId());
        boolean newMode = !library.guiDirectSendMode();
        library.guiDirectSendMode(newMode);
        try {
            savePlayerLibrary(library);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to save GUI send mode", exception);
            player.sendMessage(Component.text("发送方式已切换，但保存设置失败，重启后可能恢复默认。", NamedTextColor.YELLOW));
        }
        player.sendMessage(Component.text("表情点击发送方式已切换为：" + (newMode ? "直接发送表情" : "文本互动提示"), NamedTextColor.GREEN));
        return newMode;
    }

    public synchronized Optional<UploadToken> createUploadTokenWithCooldown(Player player, String rawName) {
        if (!tryUseManualActionCooldown(player, "/emoji upload")) {
            return Optional.empty();
        }
        return Optional.of(createUploadToken(player, rawName));
    }

    public UploadToken createUploadToken(Player player, String rawName) {
        String name = sanitizeName(rawName);
        Duration ttl = Duration.ofSeconds(plugin.getConfig().getLong("upload-server.upload-token-expire-seconds", 300L));
        String token = UUID.randomUUID().toString().replace("-", "");
        UploadToken uploadToken = new UploadToken(token, player.getUniqueId(), player.getName(), name, Instant.now().plus(ttl));
        uploadTokens.put(token, uploadToken);
        return uploadToken;
    }

    public Optional<UploadToken> getUploadToken(String token) {
        UploadToken uploadToken = uploadTokens.get(token);
        if (uploadToken == null || uploadToken.expired()) {
            uploadTokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(uploadToken);
    }

    public String uploadUrl(UploadToken token) {
        return packPublicBaseUrl + "/upload/" + token.token();
    }

    public synchronized DynamicEmoji completeUpload(String token, byte[] imageBytes, String originalFilename) throws IOException {
        UploadToken uploadToken = getUploadToken(token).orElseThrow(() -> new IOException("Upload token is invalid or expired."));
        uploadTokens.remove(token);
        return completeExternalUpload(
                uploadToken.owner(),
                uploadToken.ownerName(),
                uploadToken.emojiName(),
                imageBytes,
                originalFilename,
                "由 " + uploadToken.ownerName() + " 上传至 " + serverName(),
                true
        );
    }

    public synchronized DynamicEmoji completeExternalUpload(UUID owner, String ownerName, String rawName, byte[] imageBytes, String originalFilename, String description, boolean addToOwnerLibrary) throws IOException {
        int maxKb = plugin.getConfig().getInt("upload-server.max-file-size-kb", 1024);
        if (imageBytes.length > maxKb * 1024L) {
            throw new IOException("Image is too large. Max " + maxKb + " KB.");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("Unsupported image. Please upload PNG, JPG, JPEG or GIF.");
        }
        int maxWidth = Math.max(0, plugin.getConfig().getInt("upload-server.max-image-width", 2048));
        int maxHeight = Math.max(0, plugin.getConfig().getInt("upload-server.max-image-height", 2048));
        if ((maxWidth > 0 && image.getWidth() > maxWidth) || (maxHeight > 0 && image.getHeight() > maxHeight)) {
            throw new IOException("Image dimensions are too large. Max " + maxWidth + "x" + maxHeight + " px.");
        }

        int imageSize = plugin.getConfig().getInt("personal-emojis.image-size", 64);
        BufferedImage normalized = normalizeImage(image, imageSize);

        UUID safeOwner = owner == null ? new UUID(0L, 0L) : owner;
        String safeOwnerName = ownerName == null || ownerName.isBlank() ? "KiKi-FusionBot" : ownerName.trim();
        String safeName = sanitizeName(rawName);
        if (addToOwnerLibrary && library(safeOwner).emojiGlyphs().size() >= maxSavedEmojisPerPlayer()) {
            throw new IOException("Your emoji library is full. Max " + maxSavedEmojisPerPlayer() + " saved emojis per player.");
        }
        String id = uniqueEmojiId(safeOwner, safeName);
        Path emojiDir = globalEmojiDirectory(id);
        Files.createDirectories(emojiDir);
        Path imagePath = emojiDir.resolve("image.png");
        ImageIO.write(normalized, "png", imagePath.toFile());

        String sha256 = sha256(Files.readAllBytes(imagePath));
        DynamicEmoji emoji = new DynamicEmoji(
                id,
                safeOwner,
                safeOwnerName,
                safeName,
                ":" + id + ":",
                nextAvailableGlobalGlyph(),
                plugin.getConfig().getString("personal-emojis.unsynced-placeholder", "?"),
                description == null || description.isBlank() ? "由 " + safeOwnerName + " 上传至 " + serverName() : description,
                imagePath,
                sha256
        );
        emojisById.put(id, emoji);
        saveEmojiMeta(emoji);

        if (addToOwnerLibrary) {
            PlayerEmojiLibrary ownerLibrary = library(safeOwner);
            ownerLibrary.addIfMissing(id);
            savePlayerLibrary(ownerLibrary);
            incrementSavedCount(id);
        }
        rebuildServerPack();
        return emoji;
    }

    public synchronized boolean saveEmojiToPlayer(Player player, String emojiId) {
        DynamicEmoji emoji = emojisById.get(emojiId);
        if (emoji == null) {
            player.sendMessage(Component.text("这个表情不存在或已被删除。", NamedTextColor.RED));
            return false;
        }

        PlayerEmojiLibrary library = library(player.getUniqueId());
        boolean alreadyHad = library.has(emojiId);
        if (!alreadyHad && library.emojiGlyphs().size() >= maxSavedEmojisPerPlayer()) {
            player.sendMessage(Component.text("你的表情栏已满，最多只能保存 " + maxSavedEmojisPerPlayer() + " 个自定义表情。请先右键移除不需要的表情。", NamedTextColor.RED));
            return false;
        }
        library.addIfMissing(emojiId);
        try {
            savePlayerLibrary(library);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save emoji to player", exception);
            player.sendMessage(Component.text("保存失败：无法保存你的表情栏。", NamedTextColor.RED));
            return false;
        }
        if (!alreadyHad) {
            incrementSavedCount(emojiId);
        }

        if (alreadyHad) {
            player.sendMessage(Component.text("你的表情栏中已经有 ", NamedTextColor.GRAY)
                    .append(Component.text(emoji.token(), NamedTextColor.AQUA))
                    .append(Component.text("。", NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text("已保存到你的表情栏：", NamedTextColor.GREEN)
                    .append(Component.text(emoji.token(), NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text("。若表情无法正常显示，请执行 /emoji sync 手动加载资源包。", NamedTextColor.GREEN)));
        }
        return true;
    }

    public synchronized boolean removeEmojiFromPlayer(Player player, String emojiId) {
        PlayerEmojiLibrary library = library(player.getUniqueId());
        if (!library.has(emojiId)) {
            player.sendMessage(Component.text("你的表情栏里没有这个表情。", NamedTextColor.RED));
            return false;
        }
        library.remove(emojiId);
        try {
            savePlayerLibrary(library);
            decrementSavedCount(emojiId);
            boolean cleaned = cleanupEmojiIfUnused(emojiId);
            if (cleaned) {
                rebuildServerPack();
                player.sendMessage(Component.text("已从你的表情栏移除并清理无人保存的表情数据：" + emojiId, NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("已从你的表情栏移除：" + emojiId, NamedTextColor.GREEN));
            }
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player library after remove", exception);
            player.sendMessage(Component.text("移除失败：无法保存你的表情栏。", NamedTextColor.RED));
            return false;
        }
    }

    public synchronized boolean deleteEmojiByAdmin(CommandSender sender, String emojiId) {
        DynamicEmoji emoji = emojisById.get(emojiId);
        if (emoji == null) {
            sender.sendMessage(Component.text("这个表情不存在或已被删除。", NamedTextColor.RED));
            return false;
        }
        try {
            int removedFromLibraries = removeEmojiFromAllLibraries(emojiId);
            deleteEmojiData(emojiId);
            rebuildServerPack();
            sender.sendMessage(Component.text("已全局删除表情 ", NamedTextColor.GREEN)
                    .append(Component.text(emoji.token(), NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text("，并从 " + removedFromLibraries + " 个玩家表情栏中移除。", NamedTextColor.GREEN)));
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete emoji by admin", exception);
            sender.sendMessage(Component.text("删除失败：无法清理表情数据。", NamedTextColor.RED));
            return false;
        }
    }

    private boolean cleanupEmojiIfUnused(String emojiId) throws IOException {
        if (!emojisById.containsKey(emojiId)) {
            return false;
        }
        if (savedPlayerCount(emojiId) > 0) {
            return false;
        }
        deleteEmojiData(emojiId);
        return true;
    }

    private int removeEmojiFromAllLibraries(String emojiId) throws IOException {
        int removed = 0;
        for (PlayerEmojiLibrary library : librariesByPlayer.values()) {
            if (!library.has(emojiId)) {
                continue;
            }
            library.remove(emojiId);
            savePlayerLibrary(library);
            decrementSavedCount(emojiId);
            removed++;
        }
        return removed;
    }

    private void deleteEmojiData(String emojiId) throws IOException {
        emojisById.remove(emojiId);
        savedCounts.remove(emojiId);
        markServerPackDirty();
        Path directory = globalEmojiDirectory(emojiId);
        if (Files.exists(directory)) {
            try (var stream = Files.walk(directory)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public synchronized void syncPlayerPackWithCooldown(Player player) {
        if (!tryUseManualActionCooldown(player, "/emoji sync")) {
            return;
        }
        syncPlayerPack(player, true);
    }

    private boolean tryUseManualActionCooldown(Player player, String actionName) {
        long cooldownSeconds = Math.max(0L, plugin.getConfig().getLong("resource-pack.sync-cooldown-seconds", 600L));
        if (cooldownSeconds <= 0L || hasAdminPermission(player)) {
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldownUntil = manualSyncCooldownUntilMillis.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownUntil > now) {
            player.sendMessage(Component.text(actionName + " 冷却中，请等待 " + formatCooldown(cooldownUntil - now) + " 后再尝试。", NamedTextColor.YELLOW));
            return false;
        }

        manualSyncCooldownUntilMillis.put(player.getUniqueId(), now + cooldownSeconds * 1000L);
        return true;
    }

    private boolean hasAdminPermission(Player player) {
        return player.isOp() || player.hasPermission("ntc.admin") || player.hasPermission("emojichat.admin");
    }

    public synchronized void syncPlayerPack(Player player, boolean force) {
        try {
            PersonalPackBuilder.PackResult result = ensureServerPackReady();
            sendServerPackIfNeeded(player, result, force);
        } catch (IOException exception) {
            manualSyncCooldownUntilMillis.remove(player.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Failed to sync full server emoji pack", exception);
            player.sendMessage(Component.text("同步失败：无法生成全量表情资源包。", NamedTextColor.RED));
        }
    }

    private String formatCooldown(long millis) {
        long seconds = Math.max(1L, (millis + 999L) / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes <= 0L) {
            return remainingSeconds + " 秒";
        }
        if (remainingSeconds == 0L) {
            return minutes + " 分钟";
        }
        return minutes + " 分 " + remainingSeconds + " 秒";
    }

    public Component renderComponentForViewer(UUID senderUuid, UUID viewerUuid, Component original) {
        Component base;
        if (original instanceof TextComponent textComponent) {
            Component replacedText = renderForViewer(senderUuid, viewerUuid, textComponent.content());
            base = Component.empty()
                    .style(original.style())
                    .append(replacedText);
        } else {
            base = original.children(List.of());
        }

        for (Component child : original.children()) {
            base = base.append(renderComponentForViewer(senderUuid, viewerUuid, child));
        }
        return base;
    }

    public Component renderForViewer(UUID senderUuid, UUID viewerUuid, String rawMessage) {
        List<DynamicEmoji> senderEmojis = usableEmojis(senderUuid);
        senderEmojis.sort(Comparator.comparingInt((DynamicEmoji emoji) -> emoji.token().length()).reversed());

        Component result = Component.empty();
        StringBuilder normal = new StringBuilder();
        int index = 0;
        while (index < rawMessage.length()) {
            EmojiEntry staticMatch = staticEmojiManager.findMatchAt(rawMessage, index);
            DynamicEmoji dynamicMatch = findDynamicMatchAt(senderEmojis, rawMessage, index);

            boolean useDynamic = false;
            if (dynamicMatch != null) {
                if (staticMatch == null || dynamicMatch.token().length() >= staticMatch.token().length()) {
                    useDynamic = true;
                }
            }

            if (useDynamic) {
                if (!normal.isEmpty()) {
                    result = result.append(Component.text(normal.toString()));
                    normal.setLength(0);
                }
                result = result.append(dynamicEmojiComponentForViewer(dynamicMatch, viewerUuid));
                index += dynamicMatch.token().length();
                continue;
            }

            if (staticMatch != null) {
                if (!normal.isEmpty()) {
                    result = result.append(Component.text(normal.toString()));
                    normal.setLength(0);
                }
                result = result.append(staticEmojiManager.componentFor(staticMatch));
                index += staticMatch.token().length();
                continue;
            }

            int codePoint = rawMessage.codePointAt(index);
            normal.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }

        if (!normal.isEmpty()) {
            result = result.append(Component.text(normal.toString()));
        }
        return result;
    }

    public synchronized PersonalPackBuilder.PackResult rebuildServerPack() throws IOException {
        PersonalPackBuilder.PackResult result = packBuilder.buildAll(allEmojis());
        cachedServerPackResult = result;
        serverPackDirty = false;
        return result;
    }

    public synchronized PersonalPackBuilder.PackResult ensureServerPackReady() throws IOException {
        PersonalPackBuilder.PackResult cached = cachedServerPackResult;
        if (!serverPackDirty && cached != null && Files.isRegularFile(cached.zipPath())) {
            return cached;
        }
        return rebuildServerPack();
    }

    public void rebuildServerPackSilently() {
        try {
            ensureServerPackReady();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to rebuild full server emoji pack", exception);
        }
    }

    private void markServerPackDirty() {
        serverPackDirty = true;
    }

    public void sendServerPackIfNeeded(Player player, PersonalPackBuilder.PackResult result, boolean force) throws IOException {
        PlayerEmojiLibrary library = library(player.getUniqueId());
        if (!force && result.sha1Hex().equalsIgnoreCase(library.lastSentPackHash())) {
            player.sendMessage(Component.text("全量表情资源包已经是最新版本。", NamedTextColor.GRAY));
            return;
        }

        String url = serverPackUrl() + "?v=" + result.sha1Hex();
        UUID packId = serverPackId();
        String prompt = plugin.getConfig().getString("resource-pack.prompt", "加载全量表情资源包以显示服务器所有玩家表情。");
        boolean forcePack = plugin.getConfig().getBoolean("resource-pack.force", false);

        warnIfLocalPublicUrl(player);
        if (plugin.getConfig().getBoolean("resource-pack.remove-before-send", false)) {
            removeResourcePackIfSupported(player, packId);
        }

        player.sendMessage(Component.text("正在发送全量表情资源包。", NamedTextColor.GREEN));
        player.sendMessage(Component.text("资源包地址：", NamedTextColor.DARK_GRAY)
                .append(Component.text(url, NamedTextColor.GRAY)
                        .clickEvent(clickEventForExternalUrl(url))
                        .hoverEvent(Component.text(hoverTextForExternalUrl(url, "点击测试这个地址是否能打开。"), NamedTextColor.YELLOW))));

        player.getScheduler().runDelayed(plugin, task -> {
            try {
                sendResourcePack(player, packId, url, result.sha1(), prompt, forcePack);
                library.lastSentPackHash(result.sha1Hex());
                savePlayerLibrary(library);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to send full server emoji resource pack", exception);
                player.sendMessage(Component.text("发送全量表情材质包失败，请查看后台报错。", NamedTextColor.RED));
            }
        }, null, 2L);
    }


    public synchronized void markResourcePackLoaded(Player player) {
        PlayerEmojiLibrary library = library(player.getUniqueId());
        if (library.resourcePackLoadedEver()) {
            return;
        }
        library.resourcePackLoadedEver(true);
        try {
            savePlayerLibrary(library);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to save resource pack loaded state", exception);
        }
    }

    public synchronized void sendFirstJoinSetupNoticeIfNeeded(Player player) {
        PlayerEmojiLibrary library = library(player.getUniqueId());
        if (library.resourcePackLoadedEver() || library.joinNoticeShown()) {
            return;
        }

        library.joinNoticeShown(true);
        try {
            savePlayerLibrary(library);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to save first join emoji notice state", exception);
        }

        Component syncCommand = Component.text("/emoji sync", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/emoji sync"))
                .hoverEvent(Component.text("点击立即请求加载表情资源包", NamedTextColor.YELLOW));
        Component modLink = Component.text("安装 ResourcePackCached", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl("https://modrinth.com/mod/resourcepackcached"))
                .hoverEvent(Component.text("点击打开 Modrinth 下载页", NamedTextColor.YELLOW));

        player.sendMessage(Component.text(" ", NamedTextColor.DARK_GRAY));
        String serverName = serverName();
        player.sendMessage(Component.text("[EmojiChat表情] 自定义表情提示", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        player.sendMessage(Component.text("本服使用 " + serverName + " 自定义表情资源包；若表情显示为方框/空白，请执行 ", NamedTextColor.GRAY).append(syncCommand).append(Component.text(" 加载材质。", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("推荐 ", NamedTextColor.GRAY).append(modLink).append(Component.text("，可缓存服务器资源包，减少重进服后重复加载。", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("此公告只会对你显示一次；之后也可以随时手动执行 /emoji sync。", NamedTextColor.DARK_GRAY));
    }

    public UUID serverPackId() {
        return UUID.nameUUIDFromBytes((serverName().toLowerCase(Locale.ROOT) + "-lao-direct-full-server-emoji-pack").getBytes(StandardCharsets.UTF_8));
    }

    public UUID personalPackId(UUID playerUuid) {
        return serverPackId();
    }

    private void removeResourcePackIfSupported(Player player, UUID packId) {
        try {
            player.getClass().getMethod("removeResourcePack", UUID.class).invoke(player, packId);
        } catch (NoSuchMethodException ignored) {
            // Older API versions do not expose removeResourcePack(UUID). Sending the pack again still works on modern clients.
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Failed to remove old resource pack before resend", exception);
        }
    }

    private void sendResourcePack(Player player, UUID packId, String url, byte[] sha1, String prompt, boolean force) throws Exception {
        try {
            player.getClass()
                    .getMethod("addResourcePack", UUID.class, String.class, byte[].class, String.class, boolean.class)
                    .invoke(player, packId, url, sha1, prompt, force);
            return;
        } catch (NoSuchMethodException ignored) {
            // Try the Adventure Component prompt overload used by newer APIs.
        }

        try {
            player.getClass()
                    .getMethod("addResourcePack", UUID.class, String.class, byte[].class, Component.class, boolean.class)
                    .invoke(player, packId, url, sha1, Component.text(prompt), force);
            return;
        } catch (NoSuchMethodException ignored) {
            // Fall back to the older single-pack API below.
        }

        try {
            player.getClass()
                    .getMethod("setResourcePack", String.class, byte[].class, String.class, boolean.class)
                    .invoke(player, url, sha1, prompt, force);
            return;
        } catch (NoSuchMethodException ignored) {
            // Try the Adventure Component prompt overload.
        }

        player.getClass()
                .getMethod("setResourcePack", String.class, byte[].class, Component.class, boolean.class)
                .invoke(player, url, sha1, Component.text(prompt), force);
    }

    private void warnIfLocalPublicUrl(Player player) {
        String lower = packPublicBaseUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("127.0.0.1") || lower.contains("localhost")) {
            player.sendMessage(Component.text("警告：upload-server.public-url 当前是本机地址：" + packPublicBaseUrl, NamedTextColor.RED));
            player.sendMessage(Component.text("客户端会用玩家自己的电脑访问这个地址，公网玩家通常无法下载。请改成服务器公网 IP 或域名。", NamedTextColor.YELLOW));
        }
    }

    public Path generatedServerPack() {
        return plugin.getDataFolder().toPath().resolve("generated-packs").resolve("server.zip");
    }

    public Path generatedPackFor(UUID playerUuid) {
        return generatedServerPack();
    }

    public String serverPackUrl() {
        return packPublicBaseUrl + "/packs/server.zip";
    }

    public String personalPackUrl(UUID playerUuid) {
        return serverPackUrl();
    }

    public void notifyOwnerUploadComplete(UUID ownerUuid, DynamicEmoji emoji) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            Player player = Bukkit.getPlayer(ownerUuid);
            if (player == null) {
                return;
            }
            player.getScheduler().run(plugin, entityTask -> {
                player.sendMessage(Component.text(serverName() + " 表情上传完成：", NamedTextColor.GREEN)
                        .append(Component.text(emoji.token(), NamedTextColor.AQUA, TextDecoration.BOLD))
                        .append(Component.text("。已加入你的表情栏，并已更新全量服务器表情包。若需要加载最新材质，请执行 /emoji sync。", NamedTextColor.GREEN)));
            }, null);
        });
    }


    private String serverName() {
        String name = plugin.getConfig().getString("settings.server-name", "NeoTcc");
        return name == null || name.isBlank() ? "NeoTcc" : name;
    }

    private DynamicEmoji findDynamicMatchAt(List<DynamicEmoji> senderEmojis, String message, int index) {
        for (DynamicEmoji emoji : senderEmojis) {
            if (message.startsWith(emoji.token(), index)) {
                return emoji;
            }
        }
        return null;
    }

    private Component dynamicEmojiComponentForViewer(DynamicEmoji emoji, UUID viewerUuid) {
        Component glyph = Component.text(emoji.glyph(), NamedTextColor.WHITE).font(personalFontKey);
        String displayHelp = "这是一个自定义表情，如果未能正常显示请尝试使用 /emoji sync 同步资源。";
        if (viewerUuid != null && library(viewerUuid).has(emoji.id())) {
            return glyph.hoverEvent(Component.text(emoji.token() + " - " + emoji.description() + "\n"
                            + displayHelp + "\n已保存到你的表情栏，点击填入聊天框。", NamedTextColor.YELLOW))
                    .clickEvent(ClickEvent.suggestCommand(emoji.token()));
        }
        return glyph.hoverEvent(Component.text("表情：" + emoji.token() + "\n上传者：" + emoji.ownerName() + "\n"
                        + displayHelp + "\n未保存到你的表情栏，点击保存。", NamedTextColor.YELLOW))
                .clickEvent(ClickEvent.runCommand("/emoji save " + emoji.id()));
    }

    private void loadAllEmojis() throws IOException {
        Path globalDir = plugin.getDataFolder().toPath().resolve("emojis").resolve("global");
        Files.createDirectories(globalDir);
        try (var stream = Files.list(globalDir)) {
            for (Path emojiDir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path metaFile = emojiDir.resolve("meta.yml");
                Path imageFile = emojiDir.resolve("image.png");
                if (!Files.isRegularFile(metaFile) || !Files.isRegularFile(imageFile)) {
                    continue;
                }
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(metaFile.toFile());
                String id = yaml.getString("id", emojiDir.getFileName().toString());
                UUID owner = UUID.fromString(yaml.getString("owner", new UUID(0, 0).toString()));
                String glyph = yaml.getString("glyph", "");
                boolean needsMetaSave = false;
                if (glyph == null || glyph.isBlank()) {
                    glyph = nextAvailableGlobalGlyph();
                    needsMetaSave = true;
                }
                DynamicEmoji emoji = new DynamicEmoji(
                        id,
                        owner,
                        yaml.getString("owner-name", "Unknown"),
                        yaml.getString("name", id),
                        yaml.getString("token", ":" + id + ":"),
                        glyph,
                        yaml.getString("fallback", plugin.getConfig().getString("personal-emojis.unsynced-placeholder", "?")),
                        yaml.getString("description", id),
                        imageFile,
                        yaml.getString("sha256", "")
                );
                emojisById.put(id, emoji);
                if (needsMetaSave) {
                    saveEmojiMeta(emoji);
                }
            }
        }
    }

    private void loadAllPlayerLibraries() throws IOException {
        Path playersDir = plugin.getDataFolder().toPath().resolve("players");
        Files.createDirectories(playersDir);
        try (var stream = Files.list(playersDir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                String baseName = file.getFileName().toString().replace(".yml", "");
                UUID uuid;
                try {
                    uuid = UUID.fromString(baseName);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                PlayerEmojiLibrary library = new PlayerEmojiLibrary(uuid, firstCodePoint);
                library.nextCodePoint(yaml.getInt("next-codepoint", firstCodePoint));
                library.lastSentPackHash(yaml.getString("last-sent-pack-hash", ""));
                library.resourcePackLoadedEver(yaml.getBoolean("resource-pack-loaded-ever", false));
                library.joinNoticeShown(yaml.getBoolean("join-notice-shown", false));
                library.guiDirectSendMode(yaml.getBoolean("gui-direct-send-mode", false));
                boolean removedMissingEmoji = false;
                ConfigurationSection saved = yaml.getConfigurationSection("saved-emojis");
                if (saved != null) {
                    for (String emojiId : saved.getKeys(false)) {
                        DynamicEmoji emoji = emojisById.get(emojiId);
                        if (emoji == null) {
                            removedMissingEmoji = true;
                            continue;
                        }
                        String glyph = saved.getString(emojiId + ".glyph", "");
                        if (glyph == null || glyph.isBlank()) {
                            glyph = emoji.glyph();
                            removedMissingEmoji = true;
                        }
                        library.putLoaded(emojiId, glyph);
                        incrementSavedCount(emojiId);
                    }
                }
                librariesByPlayer.put(uuid, library);
                if (removedMissingEmoji) {
                    savePlayerLibrary(library);
                }
            }
        }
    }

    private void saveEmojiMeta(DynamicEmoji emoji) throws IOException {
        Path metaFile = globalEmojiDirectory(emoji.id()).resolve("meta.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", emoji.id());
        yaml.set("owner", emoji.owner().toString());
        yaml.set("owner-name", emoji.ownerName());
        yaml.set("name", emoji.name());
        yaml.set("token", emoji.token());
        yaml.set("glyph", emoji.glyph());
        yaml.set("fallback", emoji.fallback());
        yaml.set("description", emoji.description());
        yaml.set("sha256", emoji.sha256());
        yaml.save(metaFile.toFile());
    }

    private void savePlayerLibrary(PlayerEmojiLibrary library) throws IOException {
        Path file = playerLibraryFile(library.playerUuid());
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-codepoint", library.nextCodePoint());
        yaml.set("last-sent-pack-hash", library.lastSentPackHash());
        yaml.set("resource-pack-loaded-ever", library.resourcePackLoadedEver());
        yaml.set("join-notice-shown", library.joinNoticeShown());
        yaml.set("gui-direct-send-mode", library.guiDirectSendMode());
        for (Map.Entry<String, String> entry : library.emojiGlyphs().entrySet()) {
            DynamicEmoji emoji = emojisById.get(entry.getKey());
            yaml.set("saved-emojis." + entry.getKey() + ".glyph", emoji == null ? entry.getValue() : emoji.glyph());
        }
        yaml.save(file.toFile());
    }

    private Path globalEmojiDirectory(String emojiId) {
        return plugin.getDataFolder().toPath().resolve("emojis").resolve("global").resolve(emojiId);
    }

    private Path playerLibraryFile(UUID playerUuid) {
        return plugin.getDataFolder().toPath().resolve("players").resolve(playerUuid + ".yml");
    }

    private BufferedImage normalizeImage(BufferedImage image, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double scale = Math.min(size / (double) image.getWidth(), size / (double) image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int x = (size - width) / 2;
        int y = (size - height) / 2;
        graphics.drawImage(image, x, y, width, height, null);
        graphics.dispose();
        return target;
    }

    private String sanitizeName(String raw) {
        String lower = raw == null ? "emoji" : raw.toLowerCase(Locale.ROOT);
        String sanitized = lower.replaceAll("[^a-z0-9_\\-]", "_").replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) {
            sanitized = "emoji";
        }
        return sanitized.substring(0, Math.min(sanitized.length(), 32));
    }

    private String uniqueEmojiId(UUID owner, String name) {
        String prefix = sanitizeName(name) + "_" + playerIdSuffix(owner);
        String id = prefix;
        int duplicateIndex = 2;
        while (emojisById.containsKey(id)) {
            id = prefix + "_" + duplicateIndex;
            duplicateIndex++;
        }
        return id;
    }

    private String playerIdSuffix(UUID owner) {
        return owner.toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    }

    private String nextAvailableGlobalGlyph() throws IOException {
        Set<Integer> used = emojisById.values().stream()
                .filter(emoji -> emoji.glyph() != null && !emoji.glyph().isBlank())
                .map(emoji -> emoji.glyph().codePointAt(0))
                .collect(Collectors.toSet());
        for (int codePoint = firstCodePoint; codePoint <= lastCodePoint; codePoint++) {
            if (!used.contains(codePoint)) {
                return new String(Character.toChars(codePoint));
            }
        }
        throw new IOException("No free private-use glyph left for dynamic emojis.");
    }

    private String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 digest is not available", exception);
        }
    }

    private Key parseKey(String raw) {
        try {
            return Key.key(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid personal-emojis.font-key: " + raw + ", using emojichat:personal");
            return Key.key("emojichat", "personal");
        }
    }

    private void incrementSavedCount(String emojiId) {
        savedCounts.merge(emojiId, 1, Integer::sum);
    }

    private void decrementSavedCount(String emojiId) {
        savedCounts.computeIfPresent(emojiId, (id, count) -> count <= 1 ? null : count - 1);
    }

    public ClickEvent clickEventForExternalUrl(String url) {
        if (hasUrlScheme(url)) {
            return ClickEvent.openUrl(url);
        }
        return ClickEvent.suggestCommand(url);
    }

    public String hoverTextForExternalUrl(String url, String openText) {
        if (hasUrlScheme(url)) {
            return openText;
        }
        return "当前链接未包含 http:// 或 https://，点击只会填入链接，避免客户端自动按 http:// 打开。";
    }

    private String normalizePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            value = "127.0.0.1:8123";
        }
        value = trimTrailingSlash(value.trim());

        if (hasUrlScheme(value)) {
            return value;
        }

        boolean autoAddScheme = plugin.getConfig().getBoolean("upload-server.auto-add-scheme.enabled", false);
        if (!autoAddScheme) {
            plugin.getLogger().warning("upload-server.public-url 未包含 http:// 或 https://，且 auto-add-scheme 已关闭；插件会按原样使用该地址，聊天点击不会直接打开链接。当前值：" + value);
            return value;
        }

        String scheme = plugin.getConfig().getString("upload-server.auto-add-scheme.scheme", "http");
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }
        scheme = scheme.trim().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            plugin.getLogger().warning("upload-server.auto-add-scheme.scheme 只能是 http 或 https，当前值无效：" + scheme + "，已回退为 http。");
            scheme = "http";
        }
        return scheme + "://" + value;
    }

    private boolean hasUrlScheme(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "127.0.0.1:8123";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
