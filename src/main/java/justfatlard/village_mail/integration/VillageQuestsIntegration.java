package justfatlard.village_mail.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration with Village Quests mod.
 *
 * All interaction is reflection-based so village-mail compiles and runs
 * without village-quests on the classpath.  Targets the public API surface:
 *
 *   justfatlard.village_quests.api.VillageQuestsAPI
 *   justfatlard.village_quests.api.QuestRegistry
 *   justfatlard.village_quests.api.DialogueRegistry
 *   justfatlard.village_quests.manager.RecentActionsMemory
 *   justfatlard.village_quests.integration.MailSystemIntegration
 */
public class VillageQuestsIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
    private static final String VILLAGE_QUESTS_MOD_ID = "village-quests-justfatlard";
    private static boolean isLoaded = false;


    // Session-side dedup for the confession arc; VillagerMemory (if present) persists it across restarts
    private static final java.util.Set<UUID> DEEP_ARC_DELIVERED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean reflectionInitialized = false;
    private static boolean reflectionAvailable = false;

    // VillageQuestsAPI
    private static Method cachedModifyReputationMethod;   // static: (ServerPlayer, BlockPos, int, String) -> boolean

    // RecentActionsMemory
    private static Method cachedRecordActionMethod;       // static: (ServerPlayer, ActionType, BlockPos, String) -> void
    private static Object cachedGiftGivenAction;          // enum constant GIFT_GIVEN

    // QuestRegistry
    private static Class<?> cachedQuestGeneratorInterface;
    private static Method cachedRegisterProfessionQuestMethod;

    // DialogueRegistry.DialogueBuilder
    private static Class<?> cachedDialogueBuilderClass;
    private static Class<?> cachedDialogueHandlerInterface;
    private static Method cachedBuilderAddOptionMethod;   // (String, String, int, int, DialogueHandler) -> DialogueBuilder
    private static Method cachedBuilderRegisterMethod;    // (String) -> void

    // MailSystemIntegration (VQ side)
    private static Method cachedSendLetterFromVillagerMethod; // static: (MinecraftServer, UUID, String, String, String) -> void

    // VillagerMemory (optional: persistent tracking across restarts)
    private static Method cachedRecordMemoryMethod;   // static: (UUID, MemoryType) -> void
    private static Method cachedHasMemoryMethod;       // static: (UUID, MemoryType) -> boolean
    private static Object cachedSecretKeptEnum;        // MemoryType.SECRET_KEPT

    /**
     * Initialize cached reflection references.  Safe to call multiple times; only runs once.
     */
    @SuppressWarnings("unchecked")
    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            // --- VillageQuestsAPI ---
            Class<?> apiClass = Class.forName("justfatlard.village_quests.api.VillageQuestsAPI");
            cachedModifyReputationMethod = apiClass.getMethod("modifyPlayerReputation",
                ServerPlayer.class, BlockPos.class, int.class, String.class);

            // --- RecentActionsMemory ---
            Class<?> memoryClass = Class.forName("justfatlard.village_quests.manager.RecentActionsMemory");
            Class<?> actionTypeEnum = Class.forName("justfatlard.village_quests.manager.RecentActionsMemory$ActionType");
            cachedRecordActionMethod = memoryClass.getMethod("recordAction",
                ServerPlayer.class, actionTypeEnum, BlockPos.class, String.class);
            cachedGiftGivenAction = Enum.valueOf((Class<Enum>) actionTypeEnum, "GIFT_GIVEN");

            // --- QuestRegistry ---
            Class<?> questRegistryClass = Class.forName("justfatlard.village_quests.api.QuestRegistry");
            cachedQuestGeneratorInterface = Class.forName("justfatlard.village_quests.api.QuestRegistry$QuestGenerator");
            cachedRegisterProfessionQuestMethod = questRegistryClass.getMethod("registerProfessionQuest",
                String.class, cachedQuestGeneratorInterface);

            // --- DialogueRegistry.DialogueBuilder ---
            cachedDialogueBuilderClass = Class.forName("justfatlard.village_quests.api.DialogueRegistry$DialogueBuilder");
            cachedDialogueHandlerInterface = Class.forName("justfatlard.village_quests.api.DialogueRegistry$DialogueHandler");
            cachedBuilderAddOptionMethod = cachedDialogueBuilderClass.getMethod("addOption",
                String.class, String.class, int.class, int.class, cachedDialogueHandlerInterface);
            cachedBuilderRegisterMethod = cachedDialogueBuilderClass.getMethod("register", String.class);

            // --- MailSystemIntegration (VQ side) ---
            Class<?> mailIntegrationClass = Class.forName("justfatlard.village_quests.integration.MailSystemIntegration");
            cachedSendLetterFromVillagerMethod = mailIntegrationClass.getMethod("sendLetterFromVillager",
                MinecraftServer.class, UUID.class, String.class, String.class, String.class);

            reflectionAvailable = true;
            LOGGER.info("Village Quests reflection cache initialized");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Village Quests reflection: {}", e.getMessage(), e);
        }

        // VillagerMemory reflection (optional: persists deep confession across restarts)
        try {
            @SuppressWarnings("unchecked")
            Class<?> villagerMemoryClass = Class.forName("justfatlard.village_quests.quest.VillagerMemory");
            Class<?> memoryTypeEnum = Class.forName("justfatlard.village_quests.quest.VillagerMemory$MemoryType");
            cachedRecordMemoryMethod = villagerMemoryClass.getMethod("recordMemory", UUID.class, memoryTypeEnum);
            cachedHasMemoryMethod = villagerMemoryClass.getMethod("hasMemory", UUID.class, memoryTypeEnum);
            cachedSecretKeptEnum = Enum.valueOf((Class<Enum>) memoryTypeEnum, "SECRET_KEPT");
            LOGGER.debug("VillagerMemory reflection available; deep confession will persist across restarts");
        } catch (Exception e) {
            LOGGER.debug("VillagerMemory not available; deep confession tracking is session-only");
        }
    }

    /**
     * Initialize integration if Village Quests is present.
     */
    public static void init() {
        if (FabricLoader.getInstance().isModLoaded(VILLAGE_QUESTS_MOD_ID)) {
            isLoaded = true;
            initReflection();
            if (reflectionAvailable) {
                registerQuestsAndDialogues();
                LOGGER.info("Village Quests integration enabled!");
            }
        } else {
            LOGGER.info("Village Quests not found, integration disabled");
        }
    }

    /**
     * Check if Village Quests is loaded.
     */
    public static boolean isVillageQuestsLoaded() {
        return isLoaded;
    }

    // ========================================================================
    // Registration
    // ========================================================================

    private static void registerQuestsAndDialogues() {
        try {
            registerMailQuests();
            registerMailDialogues();
        } catch (Exception e) {
            LOGGER.error("Failed to integrate with Village Quests: {}", e.getMessage(), e);
        }
    }

    /**
     * Register a QuestGenerator for "mail_person" that returns null.
     * VQ's own MailSystemIntegration handles quest generation for the mail person
     * profession; this registration ensures the profession is recognized by the
     * quest system without overriding VQ's built-in behaviour.
     */
    private static void registerMailQuests() throws Exception {
        Object questGenerator = java.lang.reflect.Proxy.newProxyInstance(
            cachedQuestGeneratorInterface.getClassLoader(),
            new Class<?>[] { cachedQuestGeneratorInterface },
            (proxy, method, args) -> {
                if ("generate".equals(method.getName())) {
                    return null;
                }
                return null;
            }
        );

        cachedRegisterProfessionQuestMethod.invoke(null, "mail_person", questGenerator);
    }

    /**
     * Register dialogue options for the mail_person profession using DialogueBuilder.
     * Handlers receive (Villager, ServerPlayer, String) via the
     * DialogueHandler functional interface.
     */
    private static void registerMailDialogues() throws Exception {
        Object builder = cachedDialogueBuilderClass.getDeclaredConstructor().newInstance();

        // --- "Do you have any mail for me?" (any reputation) ---
        Object checkMailHandler = createDialogueHandler((villager, player, optionId) -> {
            // Whatever the post is holding changes hands here, mailbox or none.
            justfatlard.village_mail.mail.PlayerMailStorage.get(player.level().getServer())
                .collectPending(player.getUUID());
            int unreadCount = justfatlard.village_mail.api.MailApi.getUnreadCount(
                player.level().getServer(), player.getUUID());
            if (unreadCount > 0) {
                return Component.translatable("village-mail.quest.check_mail.has_mail", unreadCount)
                    .withStyle(ChatFormatting.GREEN);
            } else {
                return Component.translatable("village-mail.quest.check_mail.no_mail")
                    .withStyle(ChatFormatting.GRAY);
            }
        });

        // --- "I'd like to send a package" (reputation >= 10) ---
        Object sendPackageHandler = createDialogueHandler((villager, player, optionId) -> {
            if (player.getInventory().contains(new ItemStack(Items.PAPER))) {
                return Component.translatable("village-mail.quest.send_package.has_paper")
                    .withStyle(ChatFormatting.YELLOW);
            } else {
                return Component.translatable("village-mail.quest.send_package.no_paper")
                    .withStyle(ChatFormatting.RED);
            }
        });

        // --- "How's the mail route today?" (reputation >= 25) ---
        // Literal strings by design: randomized NPC personality lines, not UI chrome.
        // Translation keys per banter line would defeat the variety.
        Object mailRouteHandler = createDialogueHandler((villager, player, optionId) -> {
            if (villager.level() instanceof ServerLevel sw) {
                long timeOfDay = sw.getGameTime() % 24000;
                if (sw.isThundering()) return Component.literal("Not going out in that. Letters can wait.").withStyle(ChatFormatting.YELLOW);
                if (sw.isRaining()) return Component.literal("Wrapped everything in leather today. Still damp.").withStyle(ChatFormatting.WHITE);
                if (timeOfDay >= 13000) return Component.literal("Last delivery was after dark. Don't recommend it.").withStyle(ChatFormatting.GRAY);
                if (timeOfDay < 4000) return Component.literal("Good road today. Made all my stops before noon.").withStyle(ChatFormatting.WHITE);

                // Biome flavor, 40% chance
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.40) {
                    String biomePath = sw.getBiome(villager.blockPosition()).unwrapKey()
                        .map(k -> k.identifier().getPath()).orElse("");
                    if (biomePath.contains("desert")) return Component.literal("Sand gets into the letters. I wrap them twice now.").withStyle(ChatFormatting.WHITE);
                    if (biomePath.contains("taiga") || biomePath.contains("snowy") || biomePath.contains("ice"))
                        return Component.literal("The ink freezes on the road. I keep the letters inside my coat.").withStyle(ChatFormatting.WHITE);
                    if (biomePath.contains("jungle") || biomePath.contains("bamboo"))
                        return Component.literal("The humidity curls the paper. Half the addresses are unreadable.").withStyle(ChatFormatting.WHITE);
                    if (biomePath.contains("swamp") || biomePath.contains("mangrove"))
                        return Component.literal("Mud season. Lost a whole satchel in the bog last week.").withStyle(ChatFormatting.WHITE);
                }
            }
            String[] responses = {
                "Busy as always. Dogs chased me twice today.",
                "Pretty quiet. Only had to dodge one zombie.",
                "The road to the next village is getting rough.",
                "Saw some pillagers near the eastern route. Took the long way back.",
                "Found a letter on the ground halfway between here and the ridge. Seal was broken. Wind, probably.",
                "Village to the east hasn't sent anything in days. Used to be regular.",
                "Someone's been leaving bread by the road marker. Every morning. I don't ask."
            };
            return Component.literal(responses[(int)(java.util.concurrent.ThreadLocalRandom.current().nextDouble() * responses.length)])
                .withStyle(ChatFormatting.WHITE);
        });

        // --- "You ever get tired of carrying other people's words?" (reputation >= 50) ---
        Object weightOfMailHandler = createDialogueHandler((villager, player, optionId) -> {
            String[] responses = {
                "You carry a letter long enough, you start to feel what's inside. Even sealed.",
                "Delivered a letter once to someone who'd already left. Three days gone. Left it on the step. It's still there.",
                "People write things they'd never say to your face. I don't read them. But you can tell.",
                "There's a letter in the post office that's been there since before I started. Addressed. Sealed. Never sent.",
                "The worst ones are the ones that come back. Address doesn't exist anymore.",
                "Sometimes I stand at a door and I know -- before I knock -- that the news is bad. The letter tells you, somehow."
            };
            return Component.literal(responses[(int)(java.util.concurrent.ThreadLocalRandom.current().nextDouble() * responses.length)])
                .withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC);
        });

        // --- Route gossip (rep 10+) ---
        Object routeGossipHandler = createDialogueHandler((villager, player, optionId) -> {
            String[] gossip = {
                "The village to the east has been quiet. Too quiet, if you ask me.",
                "Saw smoke from a campfire past the ridge. Nobody lives out there.",
                "The roads are getting worse. Nobody's maintaining them.",
                "Ran into a trader heading south. Said things are rough everywhere.",
                "The farmer near the crossroads stopped waving at me last week. Don't know what that's about.",
                "Heard there was a raid two villages over. They're rebuilding.",
                "Someone left a lantern by the road. Still lit. Nobody claims it.",
                "The bridge is holding but I wouldn't trust it with a cart.",
            };
            return Component.literal(gossip[java.util.concurrent.ThreadLocalRandom.current().nextInt(gossip.length)])
                .withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC);
        });

        // --- Deep confession arc (rep 75+, once per player, persists across restarts) ---
        Object deepConfessionHandler = createDialogueHandler((villager, player, optionId) -> {
            UUID pid = player.getUUID();
            UUID vid = villager.getUUID();

            // Persistent memory outranks the session set: another session may have delivered it
            if (!DEEP_ARC_DELIVERED.contains(pid) && cachedHasMemoryMethod != null && cachedSecretKeptEnum != null) {
                try {
                    boolean persisted = (boolean) cachedHasMemoryMethod.invoke(null, vid, cachedSecretKeptEnum);
                    if (persisted) DEEP_ARC_DELIVERED.add(pid);
                } catch (Exception ignored) {}
            }

            if (DEEP_ARC_DELIVERED.add(pid)) {
                // First time: the confession. Persist it.
                if (cachedRecordMemoryMethod != null && cachedSecretKeptEnum != null) {
                    try { cachedRecordMemoryMethod.invoke(null, vid, cachedSecretKeptEnum); } catch (Exception ignored) {}
                }
                return Component.literal("I read one. Once. Years ago. A love letter. Wasn't for me. I delivered it like nothing happened. But I remember every word.")
                    .withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC);
            } else {
                return Component.literal("I told you about the letter. I still think about it sometimes.")
                    .withStyle(ChatFormatting.GRAY);
            }
        });

        // Option labels are translatable: the player's words, not NPC voice
        cachedBuilderAddOptionMethod.invoke(builder, "check_mail", Component.translatable("village-mail.dialogue.option.check_mail").getString(), 0, Integer.MAX_VALUE, checkMailHandler);
        cachedBuilderAddOptionMethod.invoke(builder, "send_package", Component.translatable("village-mail.dialogue.option.send_package").getString(), 10, Integer.MAX_VALUE, sendPackageHandler);
        // One question about the round, not four.
        //
        // These were four separate buttons - the route, the gossip, the weight of it, and a
        // confession - each unlocking at a higher reputation and none of them replacing the one
        // below, so a player who had earned the mailperson's trust was rewarded with a wall of
        // six choices. A conversation is not a menu.
        //
        // Now it is one question, asked the same way at every standing, and the bands decide
        // who answers it: the same lines, reached by knowing them better rather than by
        // collecting another button. Only one is ever eligible, so only one appears.
        String theRound = Component.translatable("village-mail.dialogue.option.the_round").getString();
        cachedBuilderAddOptionMethod.invoke(builder, "the_round", theRound, 10, 24, mailRouteHandler);
        cachedBuilderAddOptionMethod.invoke(builder, "the_round_gossip", theRound, 25, 49, routeGossipHandler);
        cachedBuilderAddOptionMethod.invoke(builder, "the_round_weight", theRound, 50, 74, weightOfMailHandler);
        cachedBuilderAddOptionMethod.invoke(builder, "the_round_deep", theRound, 75, Integer.MAX_VALUE, deepConfessionHandler);


        cachedBuilderRegisterMethod.invoke(builder, "mail_person");
    }

    /**
     * Create a DialogueHandler proxy from a typed lambda.
     * The DialogueHandler interface: Component handle(Villager, ServerPlayer, String)
     */
    private static Object createDialogueHandler(DialogueHandlerImpl handler) {
        return java.lang.reflect.Proxy.newProxyInstance(
            cachedDialogueHandlerInterface.getClassLoader(),
            new Class<?>[] { cachedDialogueHandlerInterface },
            (proxy, method, args) -> {
                if ("handle".equals(method.getName())) {
                    return handler.handle(
                        (Villager) args[0],
                        (ServerPlayer) args[1],
                        (String) args[2]
                    );
                }
                return null;
            }
        );
    }

    /**
     * Typed mirror of DialogueRegistry.DialogueHandler's signature.
     */
    @FunctionalInterface
    private interface DialogueHandlerImpl {
        Component handle(Villager villager, ServerPlayer player, String optionId);
    }

    // ========================================================================
    // Reputation
    // ========================================================================

    /**
     * Process reputation increase for a village donation; rarer items give more.
     */
    public static void processDonationReputation(ServerLevel world, BlockPos donationPos,
                                                 ServerPlayer donor, List<ItemStack> donatedItems) {
        if (!isVillageQuestsLoaded() || donatedItems.isEmpty()) {
            return;
        }

        initReflection();
        if (!reflectionAvailable) {
            return;
        }

        try {
            int totalReputation = 0;
            int foodCount = 0;
            int rareCount = 0;
            int buildingMaterialCount = 0;

            for (ItemStack stack : donatedItems) {
                if (stack.isEmpty()) continue;

                int count = stack.getCount();
                Item item = stack.getItem();

                if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                    foodCount += count;
                    totalReputation += count * 1;
                }
                else if (item == Items.DIAMOND || item == Items.EMERALD) {
                    rareCount += count;
                    totalReputation += count * 10;
                }
                else if (item == Items.GOLD_INGOT || item == Items.IRON_INGOT) {
                    rareCount += count;
                    totalReputation += count * 5;
                }
                else if (item == Items.NETHERITE_INGOT) {
                    rareCount += count;
                    totalReputation += count * 20;
                }
                else if (item == Items.ENCHANTED_BOOK || item == Items.TOTEM_OF_UNDYING) {
                    rareCount += count;
                    totalReputation += count * 15;
                }
                else if (stack.getItem().builtInRegistryHolder().is(ItemTags.LOGS) || stack.getItem().builtInRegistryHolder().is(ItemTags.PLANKS) ||
                         stack.getItem().builtInRegistryHolder().is(ItemTags.STONE_TOOL_MATERIALS) || item == Items.COBBLESTONE) {
                    buildingMaterialCount += count;
                    totalReputation += count * 2;
                }
                else if (stack.has(net.minecraft.core.component.DataComponents.TOOL) || item == Items.IRON_PICKAXE ||
                         item == Items.IRON_AXE || item == Items.IRON_SHOVEL) {
                    totalReputation += 8;
                }
                else {
                    totalReputation += count * 1;
                }
            }

            if (totalReputation > 0) {
                // Cap per donation to prevent reputation farming
                totalReputation = Math.min(totalReputation, 50);

                cachedModifyReputationMethod.invoke(null, donor, donationPos, totalReputation, "donation");

                String details = String.format("Donated %d items to the village", donatedItems.size());
                cachedRecordActionMethod.invoke(null, donor, cachedGiftGivenAction, donationPos, details);

                if (rareCount > 0) {
                    donor.sendSystemMessage(
                        Component.translatable("village-mail.quest.donation.rare")
                            .withStyle(ChatFormatting.GREEN),
                        false);
                } else {
                    donor.sendSystemMessage(
                        Component.translatable("village-mail.quest.donation.normal")
                            .withStyle(ChatFormatting.GREEN),
                        false);
                }

                LOGGER.info("{} donated {} items, earned {} reputation",
                    donor.getName().getString(), donatedItems.size(), totalReputation);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to process donation reputation: {}", e.getMessage(), e);
        }
    }

    // ========================================================================
    // Mail-based quest offers
    // ========================================================================

    /**
     * Send a quest offer by mail using VQ's MailSystemIntegration.sendLetterFromVillager().
     *
     * Called by MailDeliveryManager when the mail person visits a player's mailbox
     * and VQ is available.  VQ handles the quest attachment/formatting on its end;
     * we just relay the letter.
     *
     * @param server         the server instance
     * @param playerId       UUID of the player to receive the letter
     * @param villagerName   display name of the mail person villager
     * @param subject        letter subject line
     * @param body           letter body text
     * @return true if the letter was sent, false if VQ is unavailable or reflection failed
     */
    public static boolean sendQuestOfferByMail(MinecraftServer server, UUID playerId,
                                               String villagerName, String subject, String body) {
        if (!isVillageQuestsLoaded()) {
            return false;
        }

        initReflection();
        if (!reflectionAvailable || cachedSendLetterFromVillagerMethod == null) {
            return false;
        }

        try {
            cachedSendLetterFromVillagerMethod.invoke(null, server, playerId, villagerName, subject, body);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to send quest offer by mail: {}", e.getMessage(), e);
            return false;
        }
    }

    // ========================================================================
    // Mail delivery backbone
    // ========================================================================

    /**
     * Entry point for village-quests to route its letters through the postal system.
     * VQ invokes this via reflection, so it has no in-repo callers; do not delete.
     *
     * @param player       the recipient
     * @param villagerName who the letter is from
     * @param message      the letter body
     */
    public static void sendMailFromVillager(ServerPlayer player, String villagerName, String message) {
        if (player == null || message == null) return;
        try {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                justfatlard.village_mail.api.MailApi.sendMessage(server, player.getUUID(), villagerName, message);
            }
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY).withStyle(ChatFormatting.ITALIC), false);
        }
    }
}
