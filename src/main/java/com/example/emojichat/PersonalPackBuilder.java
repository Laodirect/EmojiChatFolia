package com.example.emojichat;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PersonalPackBuilder {

    public record PackResult(Path zipPath, byte[] sha1, String sha1Hex) {
    }

    private final JavaPlugin plugin;
    private final DynamicEmojiService service;

    public PersonalPackBuilder(JavaPlugin plugin, DynamicEmojiService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public PackResult buildAll(Collection<DynamicEmoji> emojis) throws IOException {
        Path packsDir = plugin.getDataFolder().toPath().resolve("generated-packs");
        Files.createDirectories(packsDir);
        Path zip = packsDir.resolve("server.zip");

        try (OutputStream fileOut = Files.newOutputStream(zip);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            writeText(zipOut, "pack.mcmeta", packMetaJson());
            writeText(zipOut, "assets/emojichat/font/personal.json", fontJson(emojis));

            for (DynamicEmoji emoji : emojis.stream().sorted(Comparator.comparing(DynamicEmoji::id, String.CASE_INSENSITIVE_ORDER)).toList()) {
                Path image = emoji.imagePath();
                if (!Files.isRegularFile(image)) {
                    continue;
                }
                writeFile(zipOut, "assets/emojichat/textures/emoji/" + emoji.id() + ".png", image);
            }
        }

        byte[] sha1 = sha1(zip);
        return new PackResult(zip, sha1, HexFormat.of().formatHex(sha1));
    }

    private String packMetaJson() {
        int packFormat = plugin.getConfig().getInt("resource-pack.pack-format", 34);
        String serverName = plugin.getConfig().getString("settings.server-name", "NeoTcc");
        if (serverName == null || serverName.isBlank()) {
            serverName = "NeoTcc";
        }
        return "{\n" +
                "  \"pack\": {\n" +
                "    \"pack_format\": " + packFormat + ",\n" +
                "    \"supported_formats\": [" + packFormat + ", 999],\n" +
                "    \"description\": \"" + jsonEscape(serverName) + " full server emoji pack by Lao_direct\"\n" +
                "  }\n" +
                "}\n";
    }

    private String fontJson(Collection<DynamicEmoji> emojis) {
        int height = plugin.getConfig().getInt("personal-emojis.font-height", 24);
        int ascent = plugin.getConfig().getInt("personal-emojis.font-ascent", 20);

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"providers\": [\n");
        boolean first = true;
        for (DynamicEmoji emoji : emojis.stream().sorted(Comparator.comparing(DynamicEmoji::id, String.CASE_INSENSITIVE_ORDER)).toList()) {
            if (emoji.glyph() == null || emoji.glyph().isBlank() || !Files.isRegularFile(emoji.imagePath())) {
                continue;
            }
            if (!first) {
                json.append(",\n");
            }
            first = false;
            json.append("    {\n")
                    .append("      \"type\": \"bitmap\",\n")
                    .append("      \"file\": \"emojichat:emoji/").append(jsonEscape(emoji.id())).append(".png\",\n")
                    .append("      \"ascent\": ").append(ascent).append(",\n")
                    .append("      \"height\": ").append(height).append(",\n")
                    .append("      \"chars\": [\"").append(unicodeEscape(emoji.glyph())).append("\"]\n")
                    .append("    }");
        }
        json.append("\n  ]\n}\n");
        return json.toString();
    }

    private void writeText(ZipOutputStream zipOut, String path, String text) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zipOut.putNextEntry(entry);
        zipOut.write(text.getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
    }

    private void writeFile(ZipOutputStream zipOut, String path, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zipOut.putNextEntry(entry);
        Files.copy(file, zipOut);
        zipOut.closeEntry();
    }

    private byte[] sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-1 digest is not available", exception);
        }
    }

    private String unicodeEscape(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint <= 0xFFFF) {
                builder.append(String.format("\\u%04X", codePoint));
            } else {
                char[] chars = Character.toChars(codePoint);
                builder.append(String.format("\\u%04X\\u%04X", (int) chars[0], (int) chars[1]));
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
