package com.example.emojichat;

import java.time.Instant;
import java.util.UUID;

public record UploadToken(
        String token,
        UUID owner,
        String ownerName,
        String emojiName,
        Instant expiresAt
) {
    public boolean expired() {
        return Instant.now().isAfter(expiresAt);
    }
}
