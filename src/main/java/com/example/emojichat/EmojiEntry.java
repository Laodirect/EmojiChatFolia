package com.example.emojichat;

import org.bukkit.Material;

public record EmojiEntry(
        String id,
        String token,
        String glyph,
        String fallback,
        Material material,
        String description
) {
    public String output(boolean useResourcePackFont) {
        return useResourcePackFont ? glyph : fallback;
    }
}
