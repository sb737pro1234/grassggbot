package me.sbpro.grassggbot.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class BotConfig {

    private final String guildId;
    private final List<String> staffRoleIds;
    private final String punishmentCategoryName;

    private BotConfig(
            String guildId,
            List<String> staffRoleIds,
            String punishmentCategoryName
    ) {
        this.guildId = guildId;
        this.staffRoleIds = staffRoleIds;
        this.punishmentCategoryName = punishmentCategoryName;
    }

    public static BotConfig load() throws IOException {
        Path path = Path.of("config.properties");

        if (!Files.exists(path)) {
            Properties defaults = new Properties();

            defaults.setProperty("guild.id", "YOUR_SERVER_ID");
            defaults.setProperty("staff.role.ids", "YOUR_STAFF_ROLE_ID");
            defaults.setProperty(
                    "punishment.category.name",
                    "Punishment Logs"
            );

            try (var output = Files.newOutputStream(path)) {
                defaults.store(output, "Grass.GG Utilities Configuration");
            }

            throw new IllegalStateException(
                    "Created config.properties. Enter your Discord server "
                            + "ID and staff role IDs, then restart the bot."
            );
        }

        Properties properties = new Properties();

        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }

        String guildId = properties.getProperty("guild.id", "").trim();

        String rawRoleIds = properties.getProperty(
                "staff.role.ids",
                ""
        ).trim();

        String categoryName = properties.getProperty(
                "punishment.category.name",
                "Punishment Logs"
        ).trim();

        if (!guildId.matches("\\d{17,20}")) {
            throw new IllegalStateException(
                    "config.properties: guild.id is missing or invalid."
            );
        }

        List<String> roleIds = Arrays.stream(rawRoleIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();

        if (roleIds.isEmpty()
                || roleIds.stream().anyMatch(id -> !id.matches("\\d{17,20}"))) {
            throw new IllegalStateException(
                    "config.properties: enter one or more valid staff role IDs."
            );
        }

        if (categoryName.isBlank()) {
            throw new IllegalStateException(
                    "config.properties: punishment.category.name cannot be blank."
            );
        }

        return new BotConfig(guildId, roleIds, categoryName);
    }

    public String getGuildId() {
        return guildId;
    }

    public List<String> getStaffRoleIds() {
        return staffRoleIds;
    }

    public String getPunishmentCategoryName() {
        return punishmentCategoryName;
    }
}