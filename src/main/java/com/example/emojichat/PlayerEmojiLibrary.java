package com.example.emojichat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerEmojiLibrary {

    private final UUID playerUuid;
    private final LinkedHashMap<String, String> emojiGlyphs = new LinkedHashMap<>();
    private int nextCodePoint;
    private String lastSentPackHash = "";
    private boolean resourcePackLoadedEver = false;
    private boolean joinNoticeShown = false;
    private boolean guiDirectSendMode = false;

    public PlayerEmojiLibrary(UUID playerUuid, int firstCodePoint) {
        this.playerUuid = playerUuid;
        this.nextCodePoint = firstCodePoint;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public Map<String, String> emojiGlyphs() {
        return Collections.unmodifiableMap(emojiGlyphs);
    }

    public boolean has(String emojiId) {
        return emojiGlyphs.containsKey(emojiId);
    }

    public Optional<String> glyph(String emojiId) {
        return Optional.ofNullable(emojiGlyphs.get(emojiId));
    }

    public String addIfMissing(String emojiId) {
        return emojiGlyphs.computeIfAbsent(emojiId, ignored -> allocateGlyph());
    }

    public void putLoaded(String emojiId, String glyph) {
        emojiGlyphs.put(emojiId, glyph);
        int cp = glyph.codePointAt(0);
        if (cp >= nextCodePoint) {
            nextCodePoint = cp + 1;
        }
    }

    public void remove(String emojiId) {
        emojiGlyphs.remove(emojiId);
    }

    public int nextCodePoint() {
        return nextCodePoint;
    }

    public void nextCodePoint(int nextCodePoint) {
        this.nextCodePoint = nextCodePoint;
    }

    public String lastSentPackHash() {
        return lastSentPackHash;
    }

    public void lastSentPackHash(String lastSentPackHash) {
        this.lastSentPackHash = lastSentPackHash == null ? "" : lastSentPackHash;
    }

    public boolean resourcePackLoadedEver() {
        return resourcePackLoadedEver;
    }

    public void resourcePackLoadedEver(boolean resourcePackLoadedEver) {
        this.resourcePackLoadedEver = resourcePackLoadedEver;
    }

    public boolean joinNoticeShown() {
        return joinNoticeShown;
    }

    public void joinNoticeShown(boolean joinNoticeShown) {
        this.joinNoticeShown = joinNoticeShown;
    }

    public boolean guiDirectSendMode() {
        return guiDirectSendMode;
    }

    public void guiDirectSendMode(boolean guiDirectSendMode) {
        this.guiDirectSendMode = guiDirectSendMode;
    }

    private String allocateGlyph() {
        String glyph = new String(Character.toChars(nextCodePoint));
        nextCodePoint++;
        return glyph;
    }
}
