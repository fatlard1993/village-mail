package justfatlard.village_mail.mail;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

	// Tick interval for villager mail (about every 5 minutes)
	private static final int VILLAGER_MAIL_INTERVAL = 6000; // 5 minutes in ticks

	/**
	 * A task that executes after a delay (in server ticks).
	 */
	public record DelayedTask(long executeAtTick, Runnable action) {}

	/**
	 * Schedule a task to run after a delay in ticks.
	 */
	public void scheduleDelayed(MinecraftServer server, int delayTicks, Runnable action) {
		long executeAt = server.getTicks() + delayTicks;
		delayedTasks.add(new DelayedTask(executeAt, action));
	}

	private void processDelayedTasks(MinecraftServer server) {
		if (delayedTasks.isEmpty()) return;
		long currentTick = server.getTicks();
		Iterator<DelayedTask> it = delayedTasks.iterator();
		while (it.hasNext()) {
			DelayedTask task = it.next();
			if (currentTick >= task.executeAtTick()) {
				try {
					task.action().run();
				} catch (Exception e) {
					LOGGER.error("Error in delayed task: " + e.getMessage());
				}
				it.remove();
			}
		}
	}

	// Donation submitted by a player via the outbox
	public static class DonationSubmission {
		public final List<ItemStack> items;
		public final UUID senderUuid;
		public final String senderName;
		public final BlockPos sourcePos;
		public final ServerWorld world;

		public DonationSubmission(List<ItemStack> items, UUID senderUuid, String senderName,
								  BlockPos sourcePos, ServerWorld world) {
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
							   BlockPos sourcePos, ServerWorld world) {
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

	// Called every server tick
	public void tick(MinecraftServer server) {
		// Auto-save mail storage periodically
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		storage.tick();

		// Process pending donations
		processPendingDonations(server);

		// Process delayed tasks (gratitude mail, etc.)
		processDelayedTasks(server);

		// Periodically send villager mail
		if (server.getTicks() % 100 == 0) { // Check every 5 seconds
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
		// First, try to process through Village Builder if it's installed
		// This will siphon off any materials needed for construction
		List<ItemStack> remainingItems = VillageBuilderIntegration.processDonation(
			mail.world, mail.sourcePos, mail.items
		);

		// Process reputation increases for Village Quests if installed
		if (mail.senderUuid != null) {
			ServerPlayerEntity sender = mail.world.getServer().getPlayerManager().getPlayer(mail.senderUuid);
			if (sender != null) {
				// Pass all donated items for reputation calculation (not just remaining)
				VillageQuestsIntegration.processDonationReputation(
					mail.world, mail.sourcePos, sender, mail.items
				);
			}
		}

		// Find nearby villagers
		Box searchBox = new Box(mail.sourcePos).expand(32);
		List<VillagerEntity> villagers = mail.world.getEntitiesByClass(
			VillagerEntity.class, searchBox, v -> true
		);

		if (!villagers.isEmpty()) {
			// Track what was donated
			int totalFoodCount = 0;
			int buildingMaterialCount = 0;
			int otherCount = 0;

			// Count all donated items (not just remaining)
			for (ItemStack item : mail.items) {
				if (item.contains(net.minecraft.component.DataComponentTypes.FOOD)) {
					totalFoodCount += item.getCount();
				} else if (item.isIn(net.minecraft.registry.tag.ItemTags.LOGS) ||
						   item.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
					buildingMaterialCount += item.getCount();
				} else {
					otherCount += item.getCount();
				}
			}

			// Distribute food to villagers to enable breeding
			if (totalFoodCount > 0) {
				distributeFood(villagers, totalFoodCount, mail.world);
			}

			// Notify nearby players about the donation
			Text donationText;
			if (totalFoodCount > 0 && (buildingMaterialCount > 0 || otherCount > 0)) {
				donationText = Text.translatable("village-mail.delivery.donation_received_mixed",
					totalFoodCount, buildingMaterialCount, otherCount);
			} else if (totalFoodCount > 0) {
				donationText = Text.translatable("village-mail.delivery.donation_received_food", totalFoodCount);
			} else if (buildingMaterialCount > 0) {
				donationText = Text.translatable("village-mail.delivery.donation_received_building", buildingMaterialCount);
			} else {
				donationText = Text.translatable("village-mail.delivery.donation_received_other", otherCount);
			}

			final Text finalDonationText = donationText;
			mail.world.getPlayers().forEach(player -> {
				if (player.getBlockPos().isWithinDistance(mail.sourcePos, 64)) {
					player.sendMessage(finalDonationText.copy().formatted(net.minecraft.util.Formatting.GREEN), true);

					// Construction status in chat so it doesn't overwrite the actionbar donation text
					Text constructionStatus = VillageBuilderIntegration.getConstructionStatus(
						mail.world, mail.sourcePos
					);
					if (constructionStatus != null) {
						player.sendMessage(constructionStatus, false);
					}
				}
			});

			LOGGER.info("Delivered donation to village: {} food, {} building materials, {} other items",
				totalFoodCount, buildingMaterialCount, otherCount);
		}
	}

	/**
	 * Distribute donated food to villagers to enable breeding.
	 * Villagers need 12 food points (3 bread = 12 points) to breed.
	 * Each food item donated converts to 1 bread given to a villager,
	 * spending from the donated count rather than creating items from nothing.
	 */
	private void distributeFood(List<VillagerEntity> villagers, int foodCount, ServerWorld world) {
		if (villagers.isEmpty() || foodCount <= 0) return;

		// 3 bread per villager to trigger breeding willingness
		int breadPerVillager = 3;
		int villagersToFeed = Math.min(villagers.size(), foodCount / breadPerVillager);
		int foodRemaining = foodCount;

		int villagersReadyToBreed = 0;
		for (int i = 0; i < villagersToFeed; i++) {
			VillagerEntity villager = villagers.get(i);

			int toGive = Math.min(breadPerVillager, foodRemaining);
			for (int j = 0; j < toGive; j++) {
				villager.getInventory().addStack(new ItemStack(Items.BREAD));
			}
			foodRemaining -= toGive;

			villager.setBreedingAge(0);

			if (world.getRandom().nextFloat() < 0.5f) {
				world.spawnParticles(
					net.minecraft.particle.ParticleTypes.HEART,
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
		long currentTime = server.getOverworld().getTime();
		long currentTick = server.getTicks();
		PlayerMailStorage storage = PlayerMailStorage.get(server);

		// Phase 1: Check active deliveries — did any mail person arrive?
		Iterator<Map.Entry<UUID, ActiveDelivery>> deliveryIt = activeDeliveries.entrySet().iterator();
		while (deliveryIt.hasNext()) {
			Map.Entry<UUID, ActiveDelivery> entry = deliveryIt.next();
			ActiveDelivery delivery = entry.getValue();

			// Timeout: if the villager has been walking for more than 30 seconds, give up
			if (currentTick - delivery.startTick() > 600) {
				deliveryIt.remove();
				continue;
			}

			// Find the villager
			MailboxBlockEntity mailbox = findPlayerMailbox(server, delivery.mailboxOwner());
			if (mailbox == null) {
				deliveryIt.remove();
				continue;
			}

			ServerWorld mailboxWorld = (ServerWorld) mailbox.getWorld();
			if (mailboxWorld == null) {
				deliveryIt.remove();
				continue;
			}

			// Search for the specific villager by UUID
			Box searchBox = new Box(delivery.targetMailbox()).expand(64);
			List<VillagerEntity> villagers = mailboxWorld.getEntitiesByClass(
				VillagerEntity.class, searchBox,
				v -> v.getUuid().equals(delivery.villagerUuid())
			);

			if (villagers.isEmpty()) {
				deliveryIt.remove();
				continue;
			}

			VillagerEntity villager = villagers.get(0);

			// Check if the villager has arrived (within 3 blocks of the mailbox)
			if (villager.getBlockPos().isWithinDistance(delivery.targetMailbox(), 3.0)) {
				sendVillagerMail(mailbox, villager, server);
				storage.setVillagerMailCooldown(delivery.targetMailbox(), currentTime);
				deliveryIt.remove();
			} else {
				// Re-issue navigation in case it got interrupted
				villager.getNavigation().startMovingTo(
					delivery.targetMailbox().getX() + 0.5,
					delivery.targetMailbox().getY(),
					delivery.targetMailbox().getZ() + 0.5,
					0.6
				);
			}
		}

		// Phase 2: Start new deliveries for mailboxes that are due.
		// Entity search runs per player — O(players * entities_in_box). For a small SMP this is fine.
		// On larger servers, consider staggering players across ticks.
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			MailboxBlockEntity mailbox = findPlayerMailbox(server, player.getUuid());
			if (mailbox == null) continue;

			BlockPos pos = mailbox.getPos();
			Optional<Long> lastTime = storage.getVillagerMailCooldown(pos);

			if (lastTime.isPresent() && currentTime - lastTime.get() <= VILLAGER_MAIL_INTERVAL) continue;

			// Skip if there's already an active delivery for this mailbox owner
			if (activeDeliveries.containsKey(player.getUuid())) continue;

			ServerWorld mailboxWorld = (ServerWorld) mailbox.getWorld();
			if (mailboxWorld == null) continue;

			// Search wider — the villager will walk to the mailbox
			Box searchBox = new Box(pos).expand(64);
			List<VillagerEntity> mailPersons = mailboxWorld.getEntitiesByClass(
				VillagerEntity.class, searchBox,
				v -> v.getVillagerData().profession().value() == Main.MAIL_PERSON
					&& !activeDeliveries.values().stream().anyMatch(d -> d.villagerUuid().equals(v.getUuid()))
			);

			if (!mailPersons.isEmpty() && ThreadLocalRandom.current().nextFloat() < 0.4f) {
				VillagerEntity mailPerson = mailPersons.get(0);

				// If already close, deliver immediately
				if (mailPerson.getBlockPos().isWithinDistance(pos, 3.0)) {
					sendVillagerMail(mailbox, mailPerson, server);
					storage.setVillagerMailCooldown(pos, currentTime);
				} else {
					// Start walking to the mailbox
					mailPerson.getNavigation().startMovingTo(
						pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.6
					);
					activeDeliveries.put(player.getUuid(), new ActiveDelivery(
						mailPerson.getUuid(), pos, player.getUuid(), currentTick
					));
				}
			}
		}

		// Clean stale cooldown entries periodically
		if (currentTick % 6000 == 0) {
			storage.cleanStaleCooldowns(currentTime, VILLAGER_MAIL_INTERVAL * 10);
		}
	}

	private void sendVillagerMail(MailboxBlockEntity mailbox, VillagerEntity villager, MinecraftServer server) {
		if (mailbox.getOwnerUuid() == null) return;

		float roll = ThreadLocalRandom.current().nextFloat();

		if (roll < 0.20f && VillageQuestsIntegration.isVillageQuestsLoaded()) {
			// 20% chance: quest offer by mail (when VQ is present)
			sendQuestOffer(mailbox, villager, server);
		} else if (roll < 0.50f) {
			// 30% chance: small gift
			sendVillagerGift(mailbox, villager, server);
		}
		// 50% chance: just presence — the mail person walks over but leaves nothing.
		// "The mail person is ambient. Visits happen. Gifts are occasional. Presence, not mechanics."

		// Notify player the mail person visited
		ServerPlayerEntity owner = server.getPlayerManager().getPlayer(mailbox.getOwnerUuid());
		if (owner != null) {
			if (roll < 0.50f) {
				owner.sendMessage(Text.translatable("village-mail.delivery.villager_gift").formatted(net.minecraft.util.Formatting.YELLOW), true);
			}
		}
	}

	private void sendVillagerGift(MailboxBlockEntity mailbox, VillagerEntity villager, MinecraftServer server) {
		ItemStack gift = getRandomVillagerGift();
		if (gift.isEmpty()) return;

		MailMessage message = new MailMessage.Builder()
			.sender(villager.getUuid(), villager.getName().getString())
			.recipient(mailbox.getOwnerUuid())
			.type(MailMessage.MessageType.VILLAGER)
			.body(Text.translatable("village-mail.delivery.villager_gift_body").getString())
			.attachment(gift)
			.button(MessageButton.collectItems())
			.build();

		PlayerMailStorage.get(server).addMessage(mailbox.getOwnerUuid(), message);
	}

	private void sendQuestOffer(MailboxBlockEntity mailbox, VillagerEntity villager, MinecraftServer server) {
		// Ask VQ to generate a quest offer, delivered by mail
		VillageQuestsIntegration.sendQuestOfferByMail(server, mailbox.getOwnerUuid(),
			villager.getName().getString(),
			Text.translatable("village-mail.delivery.quest_offer_subject").getString(),
			Text.translatable("village-mail.delivery.quest_offer").getString());
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
	 * Delivered with a short delay — the mail person needs a moment to write it up.
	 */
	public void sendObituaries(VillagerEntity villager, DamageSource damageSource, MinecraftServer server) {
		String dimension = villager.getEntityWorld().getRegistryKey().getValue().toString();
		BlockPos deathPos = villager.getBlockPos();
		String villagerName = villager.getName().getString();

		// Build the profession label
		VillagerProfession profession = villager.getVillagerData().profession().value();
		String professionName = getProfessionName(profession);

		// Build the obituary body
		String body = composeObituary(villagerName, professionName, damageSource, villager);

		// Find all mailboxes in the same dimension within range
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		long currentTick = server.getTicks();
		for (UUID ownerUuid : storage.getMailboxOwners()) {
			var locOpt = storage.getMailboxLocation(ownerUuid);
			if (locOpt.isEmpty()) continue;

			var loc = locOpt.get();
			if (!loc.dimension().equals(dimension)) continue;
			if (!loc.pos().isWithinDistance(deathPos, OBITUARY_RADIUS)) continue;

			// Throttle obituaries per player to prevent flood during raids
			Long lastTime = lastObituaryTime.get(ownerUuid);
			if (lastTime != null && currentTick - lastTime < OBITUARY_COOLDOWN_TICKS) continue;
			lastObituaryTime.put(ownerUuid, currentTick);

			// Deliver with a short delay (10–30 seconds)
			final String finalBody = body;
			scheduleDelayed(server, 200 + ThreadLocalRandom.current().nextInt(400), () -> {
				MailMessage message = new MailMessage.Builder()
					.sender(null, Text.translatable("village-mail.obituary.sender").getString())
					.recipient(ownerUuid)
					.type(MailMessage.MessageType.VILLAGER)
					.body(finalBody)
					.build();

				PlayerMailStorage.get(server).addMessage(ownerUuid, message);

				ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
				if (owner != null) {
					justfatlard.village_mail.network.MailNetworking.sendUnreadCount(owner);
				}
			});
		}
	}

	private String getProfessionName(VillagerProfession profession) {
		var profId = Registries.VILLAGER_PROFESSION.getId(profession);
		if (profId == null || profId.getPath().equals("none") || profId.getPath().equals("nitwit")) {
			return null;
		}
		return profession.id().getString();
	}

	private String composeObituary(String name, String professionName, DamageSource source, VillagerEntity villager) {
		var rng = ThreadLocalRandom.current();

		// Villagers are unnamed unless VQ (or a name tag) gives them one.
		// "Villager" is the vanilla default — treat it as unnamed.
		boolean hasName = name != null && !name.equals("Villager") && !name.isEmpty();

		// Opening line — shaped by what we know about them
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

		// Cause of death — kept brief
		String cause = getCauseOfDeathFlavor(source, villager);

		// Closing — one quiet line
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

	private String getCauseOfDeathFlavor(DamageSource source, VillagerEntity villager) {
		if (source.isOf(DamageTypes.PLAYER_ATTACK) || source.isOf(DamageTypes.PLAYER_EXPLOSION)) {
			return "It seems someone did this.";
		}
		if (source.getAttacker() instanceof net.minecraft.entity.mob.ZombieEntity) {
			return "The undead took them in the night.";
		}
		if (source.getAttacker() instanceof net.minecraft.entity.mob.RavagerEntity
			|| source.getAttacker() instanceof net.minecraft.entity.mob.PillagerEntity
			|| source.getAttacker() instanceof net.minecraft.entity.mob.VindicatorEntity
			|| source.getAttacker() instanceof net.minecraft.entity.mob.EvokerEntity) {
			return "Raiders were responsible.";
		}
		if (source.getAttacker() != null) {
			return "Something got to them.";
		}
		if (source.isOf(DamageTypes.FALL)) {
			return "They fell.";
		}
		if (source.isOf(DamageTypes.DROWN)) {
			return "The water claimed them.";
		}
		if (source.isOf(DamageTypes.ON_FIRE) || source.isOf(DamageTypes.IN_FIRE) || source.isOf(DamageTypes.LAVA)) {
			return "A fire took them.";
		}
		if (source.isOf(DamageTypes.LIGHTNING_BOLT)) {
			return "Lightning struck.";
		}
		if (source.isOf(DamageTypes.EXPLOSION) || source.isOf(DamageTypes.BAD_RESPAWN_POINT)) {
			return "An explosion. Nothing left to find.";
		}
		return null;
	}

	// Find a mailbox owned by a specific player using the registry
	private MailboxBlockEntity findPlayerMailbox(MinecraftServer server, UUID playerUuid) {
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		var locationOpt = storage.getMailboxLocation(playerUuid);
		if (locationOpt.isEmpty()) return null;

		var location = locationOpt.get();
		// Find the world by dimension identifier
		for (ServerWorld world : server.getWorlds()) {
			if (world.getRegistryKey().getValue().toString().equals(location.dimension())) {
				// Only check if the chunk is loaded — don't force-load
				if (world.isChunkLoaded(location.pos())) {
					if (world.getBlockEntity(location.pos()) instanceof MailboxBlockEntity mailbox) {
						if (playerUuid.equals(mailbox.getOwnerUuid())) {
							return mailbox;
						}
					}
					// Block at registered position isn't a mailbox anymore — stale entry
					storage.unregisterMailbox(playerUuid, location.pos());
				}
				return null;
			}
		}
		return null;
	}

}
