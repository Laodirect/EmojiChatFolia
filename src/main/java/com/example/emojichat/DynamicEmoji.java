package com.example.emojichat;

import java.nio.file.Path;
import java.util.UUID;

public record DynamicEmoji(
        String id,
        UUID owner,
        String ownerName,
        String name,
        String token,
        String glyph,
        String fallback,
        String description,
        Path imagePath,
        String sha256
) {
}
