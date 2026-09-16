package justfatlard.village_mail.mail;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.illager.Evoker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.block.MailboxBlockEntity;
import justfatlard.village_mail.integration.VillageBuilderIntegration;
import justfatlard.village_mail.integration.VillageQuestsIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MailDeliveryManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	private final List<DonationSubmission> pendingDonations = new ArrayList<>();
	private final Map<UUID, ActiveDelivery> activeDeliveries = new HashMap<>();

	/**
	 * Tracks a mail person actively walking to a player's mailbox.
	 */
	private record ActiveDelivery(UUID villagerUuid, BlockPos targetMailbox, UUID mailboxOwner, long startTick) {}
	private final List<DelayedTask> delayedTasks = new ArrayList<>();

	private static final int VILLAGER_MAIL_INTERVAL = 6000; // 5 minutes in ticks

	/**
	 * A task that executes after a delay (in server ticks).
	 */
	public record DelayedTask(long executeAtTick, Runnable action) {}

	/**
	 * Schedule a task to run after a delay in ticks.
	 */
	public void scheduleDelayed(MinecraftServer server, int delayTicks, Runnable action) {
		long executeAt = server.getTickCount() + delayTicks;
		delayedTasks.add(new DelayedTask(executeAt, action));
	}

	private void processDelayedTasks(MinecraftServer server) {
		if (delayedTasks.isEmpty()) return;
		// Collected first and run after, because a task that runs may schedule the next one,
		// and that appends to the list it would otherwise still be walking.
		long currentTick = server.getTickCount();
		List<Runnable> due = new ArrayList<>();
		Iterator<DelayedTask> it = delayedTasks.iterator();
		while (it.hasNext()) {
			DelayedTask task = it.next();
			if (currentTick >= task.executeAtTick()) {
				it.remove();
				due.add(task.action());
			}
		}
		for (Runnable action : due) {
			try {
				action.run();
			} catch (Exception e) {
				LOGGER.error("Error in delayed task: " + e.getMessage());
			}
		}
	}

	// Donation submitted by a player via the outbox
	public static class DonationSubmission {
		public final List<ItemStack> items;
		public final UUID senderUuid;
		public final String senderName;
		public final BlockPos sourcePos;
		public final ServerLevel world;

		public DonationSubmission(List<ItemStack> items, UUID senderUuid, String senderName,
								  BlockPos sourcePos, ServerLevel world) {
			this.items = items;
			this.senderUuid = senderUuid;
			this.senderName = senderName;
			this.sourcePos = sourcePos;
			this.world = world;
		}
	}

	/**
	 * Submit a village donation from a player.
	 */
	public void submitDonation(List<ItemStack> items, UUID senderUuid, String senderName,
							   BlockPos sourcePos, ServerLevel world) {
		if (items.isEmpty()) return;
		pendingDonations.add(new DonationSubmission(
			items, senderUuid, senderName,
			sourcePos, world
		));
	}

	/**
	 * Clear all transient state. Called on server stop so singleplayer
	 * world re-entry doesn't carry stale deliveries/tasks from a previous session.
	 */
	public void reset() {
		pendingDonations.clear();
		activeDeliveries.clear();
		delayedTasks.clear();
		lastObituaryTime.clear();
	}

	public void tick(MinecraftServer server) {
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		storage.tick();

		processPendingDonations(server);

		processDelayedTasks(server);

		if (server.getTickCount() % 100 == 0) { // Check every 5 seconds
			processVillagerMail(server);
		}

	}

	private void processPendingDonations(MinecraftServer server) {
		if (pendingDonations.isEmpty()) return;

		List<DonationSubmission> toRemove = new ArrayList<>();

		for (DonationSubmission donation : pendingDonations) {
			deliverToVillage(donation);
			toRemove.add(donation);
		}

		pendingDonations.removeAll(toRemove);
	}

	private void deliverToVillage(DonationSubmission mail) {
		// Village Builder takes construction materials first; the rest comes back to us.
		VillageBuilderIntegration.DonationOutcome outcome = VillageBuilderIntegration.processDonation(
			mail.world, mail.sourcePos, mail.items
		);

		// Items the village won't accept (e.g. a mis-mailed sword) are our responsibility:
		// return them to the sender so nothing is silently discarded.
		returnRejectedItemsToSender(mail, outcome.rejected());

		// Building materials that didn't fit the village's inventory are destroyed by
		// Village Builder's API path (only a count survives). Tell the sender they were lost.
		if (outcome.overflowLost() > 0) {
			notifyOverflowLost(mail, outcome.overflowLost());
		}

		if (mail.senderUuid != null) {
			ServerPlayer sender = mail.world.getServer().getPlayerList().getPlayer(mail.senderUuid);
			if (sender != null) {
				// Pass all donated items for reputation calculation (not just remaining)
				VillageQuestsIntegration.processDonationReputation(
					mail.world, mail.sourcePos, sender, mail.items
				);
			}
		}

		AABB searchBox = new AABB(mail.sourcePos).inflate(32);
		List<Villager> villagers = mail.world.getEntities(
				EntityTypeTest.forClass(Villager.class), searchBox, v -> true
		);

		if (!villagers.isEmpty()) {
			int totalFoodCount = 0;
			int buildingMaterialCount = 0;
			int otherCount = 0;

			// Count all donated items (not just remaining)
			for (ItemStack item : mail.items) {
				if (item.has(DataComponents.FOOD)) {
					totalFoodCount += item.getCount();
				} else if (item.getItem().builtInRegistryHolder().is(ItemTags.LOGS) ||
						   item.getItem().builtInRegistryHolder().is(ItemTags.PLANKS)) {
					buildingMaterialCount += item.getCount();
				} else {
					otherCount += item.getCount();
				}
			}

			if (totalFoodCount > 0) {
				distributeFood(villagers, totalFoodCount, mail.world);
			}

			Component donationText;
			if (totalFoodCount > 0 && (buildingMaterialCount > 0 || otherCount > 0)) {
				donationText = Component.translatable("village-mail.delivery.donation_received_mixed",
					totalFoodCount, buildingMaterialCount, otherCount);
			} else if (totalFoodCount > 0) {
				donationText = Component.translatable("village-mail.delivery.donation_received_food", totalFoodCount);
			} else if (buildingMaterialCount > 0) {
				donationText = Component.translatable("village-mail.delivery.donation_received_building", buildingMaterialCount);
			} else {
				donationText = Component.translatable("village-mail.delivery.donation_received_other", otherCount);
			}

			final Component finalDonationText = donationText;
			mail.world.players().forEach(player -> {
				if (player.blockPosition().closerThan(mail.sourcePos, 64)) {
					player.sendSystemMessage(finalDonationText.copy().withStyle(ChatFormatting.GREEN), true);

					// Construction status in chat so it doesn't overwrite the actionbar donation text
					Component constructionStatus = VillageBuilderIntegration.getConstructionStatus(
						mail.world, mail.sourcePos
					);
					if (constructionStatus != null) {
						player.sendSystemMessage(constructionStatus, false);
					}
				}
			});

			LOGGER.info("Delivered donation to village: {} food, {} building materials, {} other items",
				totalFoodCount, buildingMaterialCount, otherCount);
		}
	}

	/**
	 * Return items the village rejected (non-building materials) to the sender so
	 * nothing is silently discarded. Each rejected stack becomes its own mail message
	 * with a collect-items button (mail supports one attachment per message). If the
	 * donation had no identifiable sender, the items are dropped at the donation
	 * location instead of vanishing.
	 */
	private void returnRejectedItemsToSender(DonationSubmission mail, List<ItemStack> rejectedItems) {
		if (rejectedItems == null || rejectedItems.isEmpty()) return;

		MinecraftServer server = mail.world.getServer();

		if (mail.senderUuid == null || server == null) {
			for (ItemStack stack : rejectedItems) {
				if (stack != null && !stack.isEmpty()) {
					Block.popResource(mail.world, mail.sourcePos, stack.copy());
				}
			}
			LOGGER.info("Dropped {} rejected donation item stack(s) at {} (no sender to return to)",
				rejectedItems.size(), mail.sourcePos);
			return;
		}

		String senderName = Component.translatable("village-mail.return.sender").getString();
		String body = Component.translatable("village-mail.return.rejected_body").getString();
		int returned = 0;
		for (ItemStack stack : rejectedItems) {
			if (stack == null || stack.isEmpty()) continue;
			justfatlard.village_mail.api.MailApi.sendMessageWithItems(
				server, mail.senderUuid, senderName, body, stack.copy()
			);
			returned++;
		}
		if (returned > 0) {
			LOGGER.info("Returned {} rejected donation item stack(s) to sender {}", returned, mail.senderName);
		}
	}

	/**
	 * Tell the sender that some of their donated building materials didn't fit the
	 * village inventory and were lost. Village Builder's API path destroys overflow
	 * items (only a count survives), so we can notify but not return the items.
	 */
	private void notifyOverflowLost(DonationSubmission mail, int overflowLost) {
		MinecraftServer server = mail.world.getServer();
		if (mail.senderUuid == null || server == null) {
			LOGGER.warn("{} donated building-material items were lost to village inventory overflow (no sender to notify)",
				overflowLost);
			return;
		}
		justfatlard.village_mail.api.MailApi.sendMessage(
			server, mail.senderUuid,
			Component.translatable("village-mail.return.sender").getString(),
			Component.translatable("village-mail.return.overflow_body", overflowLost).getString()
		);
	}

	/**
	 * Distribute donated food to villagers to enable breeding.
	 * Villagers need 12 food points (3 bread = 12 points) to breed.
	 * Each food item donated converts to 1 bread given to a villager,
	 * spending from the donated count rather than creating items from nothing.
	 */
	private void distributeFood(List<Villager> villagers, int foodCount, ServerLevel world) {
		if (villagers.isEmpty() || foodCount <= 0) return;

		int breadPerVillager = 3;
		int villagersToFeed = Math.min(villagers.size(), foodCount / breadPerVillager);
		int foodRemaining = foodCount;

		int villagersReadyToBreed = 0;
		for (int i = 0; i < villagersToFeed; i++) {
			Villager villager = villagers.get(i);

			int toGive = Math.min(breadPerVillager, foodRemaining);
			for (int j = 0; j < toGive; j++) {
				villager.getInventory().addItem(new ItemStack(Items.BREAD));
			}
			foodRemaining -= toGive;

			villager.setAge(0);

			if (world.getRandom().nextFloat() < 0.5f) {
				world.sendParticles(
					ParticleTypes.HEART,
					villager.getX(), villager.getY() + 1.5, villager.getZ(),
					1, 0.25, 0.25, 0.25, 0.0
				);
			}

			villagersReadyToBreed++;
		}

		if (villagersReadyToBreed > 0) {
			LOGGER.info("Made {} villagers ready to breed with donated food ({} items used)", villagersReadyToBreed, foodCount - foodRemaining);
		}
	}

	private void processVillagerMail(MinecraftServer server) {
		long currentTime = server.overworld().getGameTime();
		long currentTick = server.getTickCount();
		PlayerMailStorage storage = PlayerMailStorage.get(server);

		// Phase 1: check whether any active delivery's mail person has arrived
		Iterator<Map.Entry<UUID, ActiveDelivery>> deliveryIt = activeDeliveries.entrySet().iterator();
		while (deliveryIt.hasNext()) {
			Map.Entry<UUID, ActiveDelivery> entry = deliveryIt.next();
			ActiveDelivery delivery = entry.getValue();

			// Timeout: if the villager has been walking for more than 30 seconds, give up
			if (currentTick - delivery.startTick() > 600) {
				deliveryIt.remove();
				continue;
			}

			MailboxBlockEntity mailbox = findPlayerMailbox(server, delivery.mailboxOwner());
			if (mailbox == null) {
				deliveryIt.remove();
				continue;
			}

			ServerLevel mailboxWorld = (ServerLevel) mailbox.getLevel();
			if (mailboxWorld == null) {
				deliveryIt.remove();
				continue;
			}

			AABB searchBox = new AABB(delivery.targetMailbox()).inflate(64);
			List<Villager> villagers = mailboxWorld.getEntities(
				EntityTypeTest.forClass(Villager.class), searchBox,
				v -> v.getUUID().equals(delivery.villagerUuid())
			);

			if (villagers.isEmpty()) {
				deliveryIt.remove();
				continue;
			}

			Villager villager = villagers.get(0);

			if (villager.blockPosition().closerThan(delivery.targetMailbox(), 3.0)) {
				sendVillagerMail(mailbox, villager, server);
				storage.setVillagerMailCooldown(delivery.targetMailbox(), currentTime);
				deliveryIt.remove();
			} else {
				// Re-issue navigation in case it got interrupted
				villager.getNavigation().moveTo(
					delivery.targetMailbox().getX() + 0.5,
					delivery.targetMailbox().getY(),
					delivery.targetMailbox().getZ() + 0.5,
					0.6
				);
			}
		}

		// Phase 2: start new deliveries for mailboxes that are due.
		// Entity search runs per player, O(players * entities_in_box). For a small SMP this is fine.
		// On larger servers, consider staggering players across ticks.
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			MailboxBlockEntity mailbox = findPlayerMailbox(server, player.getUUID());
			if (mailbox == null) continue;

			BlockPos pos = mailbox.getBlockPos();
			Optional<Long> lastTime = storage.getVillagerMailCooldown(pos);

			if (lastTime.isPresent() && currentTime - lastTime.get() <= VILLAGER_MAIL_INTERVAL) continue;

			if (activeDeliveries.containsKey(player.getUUID())) continue;

			ServerLevel mailboxWorld = (ServerLevel) mailbox.getLevel();
			if (mailboxWorld == null) continue;

			// Search wider: the villager will walk to the mailbox. Only a mail person who knows
			// the player writes to them: one they have dealt with, which buying the mailbox is.
			// A stranger with a mailbox got quest offers and gifts from villagers they had never
			// met, before they had spoken to a single one.
			AABB searchBox = new AABB(pos).inflate(64);
			UUID owner = player.getUUID();
			List<Villager> mailPersons = mailboxWorld.getEntities(
				EntityTypeTest.forClass(Villager.class), searchBox,
				v -> v.getVillagerData().profession().is(Main.MAIL_PERSON_KEY)
					&& knows(v, owner)
					&& !activeDeliveries.values().stream().anyMatch(d -> d.villagerUuid().equals(v.getUUID()))
			);

			if (!mailPersons.isEmpty() && ThreadLocalRandom.current().nextFloat() < 0.4f) {
				Villager mailPerson = mailPersons.get(0);

				if (mailPerson.blockPosition().closerThan(pos, 3.0)) {
					sendVillagerMail(mailbox, mailPerson, server);
					storage.setVillagerMailCooldown(pos, currentTime);
				} else {
					mailPerson.getNavigation().moveTo(
						pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6
					);
					activeDeliveries.put(player.getUUID(), new ActiveDelivery(
						mailPerson.getUUID(), pos, player.getUUID(), currentTick
					));
				}
			}
		}

		if (currentTick % 6000 == 0) {
			storage.cleanStaleCooldowns(currentTime, VILLAGER_MAIL_INTERVAL * 10);
		}
	}

	/**
	 * Whether this villager has any regard for this player: they have traded, or the player has
	 * done the village a good turn that villagers gossip about. A villager writes to people it
	 * knows, and a mailbox alone is not an introduction.
	 */
	private static boolean knows(Villager villager, UUID player) {
		return villager.getGossips().getReputation(player, type -> true) > 0;
	}

	private void sendVillagerMail(MailboxBlockEntity mailbox, Villager villager, MinecraftServer server) {
		if (mailbox.getOwnerUuid() == null) return;

		float roll = ThreadLocalRandom.current().nextFloat();

		if (roll < 0.20f && VillageQuestsIntegration.isVillageQuestsLoaded()) {
			// 20% chance: quest offer by mail (when VQ is present)
			sendQuestOffer(mailbox, villager, server);
		} else if (roll < 0.50f) {
			// 30% chance: small gift
			sendVillagerGift(mailbox, villager, server);
		}
		// Remaining 50%: just presence, the mail person walks over but leaves nothing

		ServerPlayer owner = server.getPlayerList().getPlayer(mailbox.getOwnerUuid());
		if (owner != null) {
			if (roll < 0.50f) {
				owner.sendSystemMessage(Component.translatable("village-mail.delivery.villager_gift").withStyle(ChatFormatting.YELLOW), true);
			}
		}
	}

	private void sendVillagerGift(MailboxBlockEntity mailbox, Villager villager, MinecraftServer server) {
		ItemStack gift = getRandomVillagerGift();
		if (gift.isEmpty()) return;

		MailMessage message = new MailMessage.Builder()
			.sender(villager.getUUID(), villager.getName().getString())
			.recipient(mailbox.getOwnerUuid())
			.type(MailMessage.MessageType.VILLAGER)
			.body(Component.translatable("village-mail.delivery.villager_gift_body").getString())
			.attachment(gift)
			.button(MessageButton.collectItems())
			.build();

		PlayerMailStorage.get(server).addMessage(mailbox.getOwnerUuid(), message);
	}

	private void sendQuestOffer(MailboxBlockEntity mailbox, Villager villager, MinecraftServer server) {
		VillageQuestsIntegration.sendQuestOfferByMail(server, mailbox.getOwnerUuid(),
			villager.getName().getString(),
			Component.translatable("village-mail.delivery.quest_offer_subject").getString(),
			Component.translatable("village-mail.delivery.quest_offer").getString());
	}

	private ItemStack getRandomVillagerGift() {
		var rng = ThreadLocalRandom.current();
		ItemStack[] possibleGifts = {
			new ItemStack(Items.BREAD, 1 + rng.nextInt(3)),
			new ItemStack(Items.APPLE, 1 + rng.nextInt(2)),
			new ItemStack(Items.CARROT, 1 + rng.nextInt(4)),
			new ItemStack(Items.POTATO, 1 + rng.nextInt(4)),
			new ItemStack(Items.WHEAT, 1 + rng.nextInt(6)),
			new ItemStack(Items.COOKIE, 1 + rng.nextInt(4)),
			new ItemStack(Items.PUMPKIN_PIE),
			new ItemStack(Items.EMERALD, 1),
			new ItemStack(Items.BOOK),
			new ItemStack(Items.PAPER, 1 + rng.nextInt(8)),
			new ItemStack(Items.MAP),
		};

		return possibleGifts[rng.nextInt(possibleGifts.length)].copy();
	}

	// ========== Obituaries ==========

	private static final int OBITUARY_RADIUS = 128;
	// Cooldown per player to prevent obituary floods (e.g., raids killing many villagers)
	private final Map<UUID, Long> lastObituaryTime = new HashMap<>();
	private static final long OBITUARY_COOLDOWN_TICKS = 600; // 30 seconds between obituaries per player

	/**
	 * Send obituary letters to player mailboxes near a villager that just died.
	 * Delivered with a short delay; the mail person needs a moment to write it up.
	 */
	/**
	 * Villagers who died and immediately came back as something curable.
	 *
	 * <p>Vanilla converts a villager to a zombie villager in {@code killedEntity},
	 * which runs *after* the villager has died, so a death event has already
	 * fired by then. On normal difficulty it is a coin flip whether a zombie kill
	 * converts or kills outright, which means half of all zombie "deaths" were
	 * being mourned for someone standing in the village a cure later.
	 */
	private final Set<UUID> convertedNotDead = ConcurrentHashMap.newKeySet();

	/** Called from the conversion event; see {@link #convertedNotDead}. */
	public void noteConverted(UUID villagerUuid) {
		convertedNotDead.add(villagerUuid);
	}

	public void sendObituaries(Villager villager, DamageSource damageSource, MinecraftServer server) {
		// Held a tick so the conversion, which happens after the death, has had its
		// say. Zombification is not a death: they can be cured and walk back in.
		UUID villagerUuid = villager.getUUID();
		scheduleDelayed(server, 2, () -> {
			if (convertedNotDead.remove(villagerUuid)) return;
			sendObituariesNow(villager, damageSource, server);
		});
	}

	private void sendObituariesNow(Villager villager, DamageSource damageSource, MinecraftServer server) {
		String dimension = villager.level().dimension().identifier().toString();
		BlockPos deathPos = villager.blockPosition();
		String villagerName = villager.getName().getString();

		VillagerProfession profession = villager.getVillagerData().profession().value();
		String professionName = getProfessionName(profession);

		String body = composeObituary(villagerName, professionName, damageSource, villager);

		// Pinned to the village's board as well as mailed. The board is not
		// rate-limited per player the way the letters below are: a notice posted
		// once is read by whoever walks past, including someone who arrives after
		// the funeral, and nobody gets it twice for owning two mailboxes.
		if (villager.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			VillageBulletin.get(server).post(serverLevel, deathPos, body);
		}

		PlayerMailStorage storage = PlayerMailStorage.get(server);
		long currentTick = server.getTickCount();
		for (UUID ownerUuid : storage.getMailboxOwners()) {
			var locOpt = storage.getMailboxLocation(ownerUuid);
			if (locOpt.isEmpty()) continue;

			var loc = locOpt.get();
			if (!loc.dimension().equals(dimension)) continue;
			if (!loc.pos().closerThan(deathPos, OBITUARY_RADIUS)) continue;
			// A letter about a death is for those who knew them; the notice on the board is for everyone.
			if (!knows(villager, ownerUuid)) continue;

			Long lastTime = lastObituaryTime.get(ownerUuid);
			if (lastTime != null && currentTick - lastTime < OBITUARY_COOLDOWN_TICKS) continue;
			lastObituaryTime.put(ownerUuid, currentTick);

			// Deliver with a short delay (10 to 30 seconds)
			final String finalBody = body;
			scheduleDelayed(server, 200 + ThreadLocalRandom.current().nextInt(400), () -> {
				MailMessage message = new MailMessage.Builder()
					.sender(null, Component.translatable("village-mail.obituary.sender").getString())
					.recipient(ownerUuid)
					.type(MailMessage.MessageType.VILLAGER)
					.body(finalBody)
					.build();

				PlayerMailStorage.get(server).addMessage(ownerUuid, message);

				ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
				if (owner != null) {
					justfatlard.village_mail.pandorical.MailHud.updateUnreadCount(owner);
				}
			});
		}
	}

	private String getProfessionName(VillagerProfession profession) {
		var profId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
		if (profId == null || profId.getPath().equals("none") || profId.getPath().equals("nitwit")) {
			return null;
		}
		return profession.name().getString();
	}

	private String composeObituary(String name, String professionName, DamageSource source, Villager villager) {
		var rng = ThreadLocalRandom.current();

		// Villagers are unnamed unless VQ (or a name tag) gives them one.
		// "Villager" is the vanilla default: treat it as unnamed.
		boolean hasName = name != null && !name.equals("Villager") && !name.isEmpty();

		// Opening line: shaped by what we know about them
		String opening;
		if (hasName && professionName != null) {
			String[] templates = {
				name + " the " + professionName + " has passed away.",
				"The village lost " + name + ", our " + professionName + ".",
				name + " the " + professionName + " is gone.",
			};
			opening = templates[rng.nextInt(templates.length)];
		} else if (hasName) {
			String[] templates = {
				name + " has passed away.",
				"The village lost " + name + ".",
				name + " is gone.",
			};
			opening = templates[rng.nextInt(templates.length)];
		} else if (professionName != null) {
			String[] templates = {
				"Our " + professionName + " has passed away.",
				"The village lost its " + professionName + ".",
				"The " + professionName + " is gone.",
			};
			opening = templates[rng.nextInt(templates.length)];
		} else {
			String[] templates = {
				"A villager has passed away.",
				"The village lost one of its own.",
				"Someone is missing from the village.",
			};
			opening = templates[rng.nextInt(templates.length)];
		}

		String cause = getCauseOfDeathFlavor(source, villager);

		String[] closings = {
			"The village will be quieter for a while.",
			"They will be missed.",
			"We carry on.",
			"The bell rang twice this morning.",
		};
		String closing = closings[rng.nextInt(closings.length)];

		if (cause != null) {
			return opening + " " + cause + " " + closing;
		}
		return opening + " " + closing;
	}

	private String getCauseOfDeathFlavor(DamageSource source, Villager villager) {
		if (source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.PLAYER_EXPLOSION)) {
			return "It seems someone did this.";
		}
		if (source.getEntity() instanceof Zombie) {
			return "The undead took them in the night.";
		}
		if (source.getEntity() instanceof Ravager
			|| source.getEntity() instanceof Pillager
			|| source.getEntity() instanceof Vindicator
			|| source.getEntity() instanceof Evoker) {
			return "Raiders were responsible.";
		}
		if (source.getEntity() != null) {
			return "Something got to them.";
		}
		if (source.is(DamageTypes.FALL)) {
			return "They fell.";
		}
		if (source.is(DamageTypes.DROWN)) {
			return "The water claimed them.";
		}
		if (source.is(DamageTypes.ON_FIRE) || source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.LAVA)) {
			return "A fire took them.";
		}
		if (source.is(DamageTypes.LIGHTNING_BOLT)) {
			return "Lightning struck.";
		}
		if (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.BAD_RESPAWN_POINT)) {
			return "An explosion. Nothing left to find.";
		}
		return null;
	}

	private MailboxBlockEntity findPlayerMailbox(MinecraftServer server, UUID playerUuid) {
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		var locationOpt = storage.getMailboxLocation(playerUuid);
		if (locationOpt.isEmpty()) return null;

		var location = locationOpt.get();
		for (ServerLevel world : server.getAllLevels()) {
			if (world.dimension().identifier().toString().equals(location.dimension())) {
				// Only check if the chunk is loaded; don't force-load
				if (world.hasChunk(location.pos().getX() >> 4, location.pos().getZ() >> 4)) {
					if (world.getBlockEntity(location.pos()) instanceof MailboxBlockEntity mailbox) {
						if (playerUuid.equals(mailbox.getOwnerUuid())) {
							return mailbox;
						}
					}
					// Block at registered position isn't a mailbox anymore: stale entry
					storage.unregisterMailbox(playerUuid, location.pos());
				}
				return null;
			}
		}
		return null;
	}

}
