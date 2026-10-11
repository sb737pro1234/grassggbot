package me.sbpro.grassggstaffbot;

import me.sbpro.grassggstaffbot.config.BotConfig;
import me.sbpro.grassggstaffbot.listeners.PunishmentListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;

public final class GrassGGStaffBot {

    private static JDA jda;

    public static void main(String[] args) {
        String token = System.getenv("GRASSGG_DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            System.err.println(
                    "[Grass.GG Utilities] Missing GRASSGG_DISCORD_TOKEN environment variable."
            );
            System.exit(1);
            return;
        }

        try {
            BotConfig config = BotConfig.load();

            System.out.println("[Grass.GG Utilities] Starting bot...");

            jda = JDABuilder.createDefault(token)
                    .addEventListeners(new PunishmentListener(config))
                    .build();

            jda.awaitReady();

            System.out.println(
                    "[Grass.GG Utilities] Successfully connected to Discord!"
            );

            System.out.println(
                    "[Grass.GG Utilities] Logged in as: "
                            + jda.getSelfUser().getName()
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            System.err.println(
                    "[Grass.GG Utilities] Startup was interrupted."
            );

            if (jda != null) {
                jda.shutdownNow();
            }

        } catch (Exception exception) {
            System.err.println(
                    "[Grass.GG Utilities] Failed to start."
            );

            exception.printStackTrace();

            if (jda != null) {
                jda.shutdownNow();
            }

            System.exit(1);
        }
    }
}