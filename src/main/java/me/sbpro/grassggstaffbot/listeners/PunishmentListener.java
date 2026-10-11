package me.sbpro.grassggstaffbot.listeners;

import me.sbpro.grassggstaffbot.config.BotConfig;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;

import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PunishmentListener extends ListenerAdapter {

    private static final String BUTTON_ID = "punishment:start";
    private static final String MODAL_ID = "punishment:player";
    private static final String DURATION_NONE = "none";
    private static final Path CLEANUP_FILE =
            Path.of("punishment-cleanup.properties");

    private final BotConfig config;

    private final Properties scheduledDeletions = new Properties();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "GrassGG-Punishment-Cleanup"
                );
                thread.setDaemon(true);
                return thread;
            });

    public PunishmentListener(BotConfig config) {
        this.config = config;
        loadScheduledDeletions();
    }

    @Override
    public void onReady(ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(config.getGuildId());

        if (guild == null) {
            System.err.println(
                    "[Grass.GG Utilities] Configured Discord server not found."
            );
            return;
        }

        guild.upsertCommand(
                "punishmentbutton",
                "Post the button used to create punishment evidence channels."
        ).setDefaultPermissions(
                DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)
        ).queue(
                command -> System.out.println(
                        "[Grass.GG Utilities] Registered /punishmentbutton."
                ),
                error -> System.err.println(
                        "[Grass.GG Utilities] Could not register command: "
                                + error.getMessage()
                )
        );

        rescheduleDeletions(guild);
    }

    @Override
    public void onSlashCommandInteraction(
            SlashCommandInteractionEvent event
    ) {
        if (!event.getName().equals("punishmentbutton")) {
            return;
        }

        if (event.getGuild() == null
                || !event.getGuild().getId().equals(config.getGuildId())) {
            event.reply("This command is not configured for this server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (event.getMember() == null
                || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("You must be an administrator to use this command.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply(true).queue(hook ->
                event.getChannel().sendMessage(
                        "**Punishment Logs**\n"
                                + "Use the button below to create a private "
                                + "punishment evidence channel."
                ).setComponents(
                        ActionRow.of(
                                Button.primary(
                                        BUTTON_ID,
                                        "Create a punishment log"
                                )
                        )
                ).queue(
                        sent -> hook.editOriginal(
                                "Punishment button added."
                        ).queue(),
                        error -> hook.editOriginal(
                                "Could not post the punishment button. "
                                        + "Check that I can send messages "
                                        + "in this channel."
                        ).queue()
                )
        );
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().equals(BUTTON_ID)) {
            return;
        }

        TextInput playerInput = TextInput.create(
                        "player",
                        TextInputStyle.SHORT
                ).setPlaceholder("Enter the player's Minecraft username")
                .setRequiredRange(3, 16)
                .setRequired(true)
                .build();

        TextInput reasonInput = TextInput.create(
                        "reason",
                        TextInputStyle.PARAGRAPH
                ).setPlaceholder("Enter the reason for this punishment")
                .setRequiredRange(1, 300)
                .setRequired(true)
                .build();

        Modal modal = Modal.create(
                MODAL_ID,
                "Create a punishment log"
        ).addComponents(
                Label.of("Minecraft username", playerInput),
                Label.of("Reason", reasonInput),
                Label.of("Punishment duration", createDurationMenu())
        ).build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals(MODAL_ID)) {
            return;
        }

        ModalMapping playerMapping = event.getValue("player");
        ModalMapping reasonMapping = event.getValue("reason");
        ModalMapping durationMapping = event.getValue("duration");

        if (playerMapping == null) {
            event.reply("The player name was missing. Please try again.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String playerName = playerMapping.getAsString().trim();

        if (!playerName.matches("[A-Za-z0-9_]{3,16}")) {
            event.reply(
                    "Enter a valid Minecraft username using 3–16 letters, "
                            + "numbers or underscores."
            ).setEphemeral(true).queue();
            return;
        }

        String reason = reasonMapping == null
                ? ""
                : reasonMapping.getAsString().trim();

        if (reason.isEmpty()) {
            event.reply("Please enter a reason and try again.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        final String selectedReason = reason;

        String durationKey = DURATION_NONE;

        if (durationMapping != null
                && !durationMapping.getAsStringList().isEmpty()) {
            durationKey = durationMapping.getAsStringList().getFirst();
        }

        final String selectedDuration = durationKey;
        final String creatorId = event.getUser().getId();

        event.deferReply(true).queue(hook ->
                createPunishmentChannel(
                        event.getGuild(),
                        creatorId,
                        playerName,
                        selectedReason,
                        selectedDuration,
                        message -> hook.editOriginal(message).queue()
                )
        );
    }

    private StringSelectMenu createDurationMenu() {
        return StringSelectMenu.create("duration")
                .setPlaceholder("Select punishment duration")
                .addOption("None", DURATION_NONE)
                .addOption("1 day", "1d")
                .addOption("3 days", "3d")
                .addOption("7 days", "7d")
                .addOption("14 days", "14d")
                .addOption("30 days", "30d")
                .addOption("60 days", "60d")
                .addOption("90 days", "90d")
                .addOption("6 months", "6m")
                .addOption("Permanent", "permanent")
                .setDefaultValues(DURATION_NONE)
                .build();
    }

    private void createPunishmentChannel(
            Guild guild,
            String creatorId,
            String playerName,
            String reason,
            String durationKey,
            MessageResponder responder
    ) {
        if (guild == null || !guild.getId().equals(config.getGuildId())) {
            responder.reply("This server is not configured for the bot.");
            return;
        }

        Category category = guild.getCategoriesByName(
                config.getPunishmentCategoryName(),
                true
        ).stream().findFirst().orElse(null);

        if (category == null) {
            responder.reply(
                    "The category **" + config.getPunishmentCategoryName()
                            + "** does not exist. Please create it first."
            );
            return;
        }

        List<Role> staffRoles = new ArrayList<>();

        for (String roleId : config.getStaffRoleIds()) {
            Role role = guild.getRoleById(roleId);

            if (role == null) {
                responder.reply(
                        "A configured staff role could not be found. "
                                + "Please check config.properties."
                );
                return;
            }

            staffRoles.add(role);
        }

        if (staffRoles.isEmpty()) {
            responder.reply(
                    "No staff roles are configured. Update config.properties."
            );
            return;
        }

        if (!guild.getSelfMember().hasPermission(
                Permission.MANAGE_CHANNEL,
                Permission.VIEW_CHANNEL
        )) {
            responder.reply(
                    "The bot needs Manage Channels, Manage Permissions "
                            + "and View Channels permissions."
            );
            return;
        }

        String channelName = "punishment-" + playerName.toLowerCase();

        List<Permission> allowed = List.of(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_ATTACH_FILES,
                Permission.MESSAGE_HISTORY
        );

        List<Permission> botAllowed = new ArrayList<>(allowed);
        botAllowed.add(Permission.PIN_MESSAGES);

        List<Permission> denied = List.of(Permission.VIEW_CHANNEL);

        var action = guild.createTextChannel(channelName)
                .setParent(category)
                .addPermissionOverride(
                        guild.getPublicRole(),
                        List.of(),
                        denied
                )
                .addPermissionOverride(
                        guild.getSelfMember(),
                        botAllowed,
                        List.of()
                );

        for (Role role : staffRoles) {
            action = action.addPermissionOverride(role, allowed, List.of());
        }

        String durationLabel = getDurationLabel(durationKey);

        action.setTopic(
                "Punishment evidence for " + playerName
                        + " | Reason: " + reason.replaceAll("\\s+", " ")
                        + " | Duration: " + durationLabel
        ).queue(channel -> {
            boolean autoDelete = !durationKey.equals("permanent")
                    && !durationKey.equals(DURATION_NONE);

            String deletingIn = "Never";
            long deleteAt = 0;

            if (autoDelete) {
                deleteAt = calculateDeletionTime(durationKey);
                deletingIn = "<t:" + (deleteAt / 1000) + ":R>";
            }

            channel.sendMessage(
                    "**Player:** " + playerName + "\n"
                            + "**Reason:** " + reason + "\n"
                            + "**Duration:** " + durationLabel + "\n"
                            + "**Created by:** <@" + creatorId + ">\n"
                            + "**Deleting in:** " + deletingIn
            ).setAllowedMentions(
                    EnumSet.of(Message.MentionType.USER)
            ).queue(
                    message -> message.pin().queue(
                            ignored -> { },
                            error -> System.err.println(
                                    "[Grass.GG Utilities] Could not pin "
                                            + "message: " + error.getMessage()
                            )
                    )
            );

            if (autoDelete) {
                saveDeletion(channel.getId(), deleteAt);
                scheduleDeletion(guild, channel.getId(), deleteAt);
            }

            responder.reply(
                    "Created " + channel.getAsMention()
                            + " for **" + playerName + "**.\n"
                            + (durationKey.equals("permanent")
                            || durationKey.equals(DURATION_NONE)
                            ? "This punishment channel will not "
                              + "be automatically deleted."
                            : "It will be deleted 24 hours after the "
                              + "punishment duration ends.")
            );
        }, error -> {
            System.err.println(
                    "[Grass.GG Utilities] Failed to create punishment channel: "
                            + error.getMessage()
            );

            responder.reply(
                    "Could not create the punishment channel. "
                            + "Check the bot's role hierarchy and permissions."
            );
        });
    }

    private long calculateDeletionTime(String durationKey) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        return switch (durationKey) {
            case "1d" -> now.plusDays(2).toInstant().toEpochMilli();
            case "3d" -> now.plusDays(4).toInstant().toEpochMilli();
            case "7d" -> now.plusDays(8).toInstant().toEpochMilli();
            case "14d" -> now.plusDays(15).toInstant().toEpochMilli();
            case "30d" -> now.plusDays(31).toInstant().toEpochMilli();
            case "60d" -> now.plusDays(61).toInstant().toEpochMilli();
            case "90d" -> now.plusDays(91).toInstant().toEpochMilli();
            case "6m" -> now.plusMonths(6).plusHours(24)
                    .toInstant().toEpochMilli();
            default -> throw new IllegalArgumentException(
                    "Unknown punishment duration: " + durationKey
            );
        };
    }

    private String getDurationLabel(String durationKey) {
        return switch (durationKey) {
            case "1d" -> "1 day";
            case "3d" -> "3 days";
            case "7d" -> "7 days";
            case "14d" -> "14 days";
            case "30d" -> "30 days";
            case "60d" -> "60 days";
            case "90d" -> "90 days";
            case "6m" -> "6 months";
            case "permanent" -> "Permanent";
            case DURATION_NONE -> "None";
            default -> "Unknown";
        };
    }

    private synchronized void loadScheduledDeletions() {
        if (!Files.exists(CLEANUP_FILE)) {
            return;
        }

        try (var input = Files.newInputStream(CLEANUP_FILE)) {
            scheduledDeletions.load(input);
        } catch (IOException exception) {
            System.err.println(
                    "[Grass.GG Utilities] Could not load cleanup schedule."
            );
            exception.printStackTrace();
        }
    }

    private synchronized void saveDeletion(
            String channelId,
            long deleteAt
    ) {
        scheduledDeletions.setProperty(
                channelId,
                Long.toString(deleteAt)
        );

        saveCleanupFile();
    }

    private synchronized void removeDeletion(String channelId) {
        scheduledDeletions.remove(channelId);
        saveCleanupFile();
    }

    private synchronized void saveCleanupFile() {
        try (var output = Files.newOutputStream(CLEANUP_FILE)) {
            scheduledDeletions.store(
                    output,
                    "Scheduled punishment channel deletions"
            );
        } catch (IOException exception) {
            System.err.println(
                    "[Grass.GG Utilities] Could not save cleanup schedule."
            );
            exception.printStackTrace();
        }
    }

    private void rescheduleDeletions(Guild guild) {
        Properties snapshot = new Properties();

        synchronized (this) {
            snapshot.putAll(scheduledDeletions);
        }

        for (String channelId : snapshot.stringPropertyNames()) {
            try {
                long deleteAt = Long.parseLong(
                        snapshot.getProperty(channelId)
                );

                scheduleDeletion(guild, channelId, deleteAt);
            } catch (NumberFormatException exception) {
                System.err.println(
                        "[Grass.GG Utilities] Invalid cleanup entry for "
                                + channelId
                );
            }
        }
    }

    private void scheduleDeletion(
            Guild guild,
            String channelId,
            long deleteAt
    ) {
        long delay = Math.max(0, deleteAt - System.currentTimeMillis());

        scheduler.schedule(() -> {
            TextChannel channel = guild.getTextChannelById(channelId);

            if (channel == null) {
                removeDeletion(channelId);
                return;
            }

            channel.delete().queue(
                    ignored -> {
                        System.out.println(
                                "[Grass.GG Utilities] Deleted expired "
                                        + "punishment channel " + channelId
                        );
                        removeDeletion(channelId);
                    },
                    error -> {
                        System.err.println(
                                "[Grass.GG Utilities] Could not delete "
                                        + channelId + "; retrying in one hour."
                        );
                        scheduleDeletion(
                                guild,
                                channelId,
                                System.currentTimeMillis()
                                        + TimeUnit.HOURS.toMillis(1)
                        );
                    }
            );
        }, delay, TimeUnit.MILLISECONDS);
    }

    @FunctionalInterface
    private interface MessageResponder {
        void reply(String message);
    }

    private boolean isStaff(Member member) {
        if (member == null) {
            return false;
        }

        if (member.hasPermission(Permission.ADMINISTRATOR)) {
            return true;
        }

        return member.getRoles().stream()
                .anyMatch(role ->
                        config.getStaffRoleIds().contains(role.getId())
                );
    }
}