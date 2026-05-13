package com.example.emojichat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class EmojiManager {

    private final JavaPlugin plugin;
    private final Map<String, EmojiEntry> entriesById = new LinkedHashMap<>();
    private final List<EmojiEntry> entries = new ArrayList<>();
    private final List<EmojiEntry> entriesByTokenLength = new ArrayList<>();

    private boolean useResourcePackFont;
    private Key fontKey;
    private String guiTitle;

    public EmojiManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();

        this.useResourcePackFont = plugin.getConfig().getBoolean("settings.use-resource-pack-font", true);
        this.fontKey = parseKey(plugin.getConfig().getString("settings.font-key", "emojichat:emoji"));
        this.guiTitle = plugin.getConfig().getString("settings.gui-title", "表情列表");

        entriesById.clear();
        entries.clear();
        entriesByTokenLength.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("emojis");
        if (section == null) {
            plugin.getLogger().warning("No emojis section found in config.yml");
            return;
        }

        for (String id : section.getKeys(false)) {
            String base = "emojis." + id + ".";
            String token = plugin.getConfig().getString(base + "token", ":" + id + ":");
            String glyph = plugin.getConfig().getString(base + "glyph", "");
            String fallback = plugin.getConfig().getString(base + "fallback", token);
            String description = plugin.getConfig().getString(base + "description", id);
            String materialName = plugin.getConfig().getString(base + "material", "PAPER");

            if (token == null || token.isBlank()) {
                plugin.getLogger().warning("Emoji " + id + " has an empty token and was skipped.");
                continue;
            }
            if (glyph == null || glyph.isEmpty()) {
                plugin.getLogger().warning("Emoji " + id + " has an empty glyph and was skipped.");
                continue;
            }

            Material material = Material.matchMaterial(materialName == null ? "PAPER" : materialName.toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                material = Material.PAPER;
            }

            EmojiEntry entry = new EmojiEntry(id, token, glyph, fallback == null ? token : fallback, material, description == null ? id : description);
            entriesById.put(id, entry);
            entries.add(entry);
            entriesByTokenLength.add(entry);
        }

        entriesByTokenLength.sort(Comparator.comparingInt((EmojiEntry entry) -> entry.token().length()).reversed());
    }

    public List<EmojiEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public Optional<EmojiEntry> byToken(String token) {
        return entries.stream().filter(entry -> entry.token().equals(token)).findFirst();
    }

    public boolean useResourcePackFont() {
        return useResourcePackFont;
    }

    public Key fontKey() {
        return fontKey;
    }

    public String guiTitle() {
        return guiTitle;
    }

    public Component replaceEmojis(Component original) {
        String plain = PlainTextComponentSerializer.plainText().serialize(original);
        return replaceEmojis(plain);
    }


    public Component componentFor(EmojiEntry entry) {
        Component emojiComponent = Component.text(entry.output(useResourcePackFont), NamedTextColor.WHITE);
        if (useResourcePackFont) {
            emojiComponent = emojiComponent.font(fontKey);
        }
        return emojiComponent;
    }

    public Component replaceEmojis(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }

        Component result = Component.empty();
        StringBuilder normalText = new StringBuilder();
        int index = 0;

        while (index < message.length()) {
            EmojiEntry match = findMatchAt(message, index);
            if (match != null) {
                if (!normalText.isEmpty()) {
                    result = result.append(Component.text(normalText.toString()));
                    normalText.setLength(0);
                }

                result = result.append(componentFor(match));
                index += match.token().length();
                continue;
            }

            int codePoint = message.codePointAt(index);
            normalText.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }

        if (!normalText.isEmpty()) {
            result = result.append(Component.text(normalText.toString()));
        }

        return result;
    }

    public EmojiEntry findMatchAt(String message, int index) {
        for (EmojiEntry entry : entriesByTokenLength) {
            if (message.startsWith(entry.token(), index)) {
                return entry;
            }
        }
        return null;
    }

    private Key parseKey(String raw) {
        try {
            return Key.key(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid settings.font-key: " + raw + ", using emojichat:emoji");
            return Key.key("emojichat", "emoji");
        }
    }
}
