package justfatlard.village_mail.mail;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-player mail storage using manual file-based persistence.
 * Stores all messages in the world's data folder, surviving mailbox destruction.
 * All of a player's mailboxes share the same message storage.
 */
public class PlayerMailStorage {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	private static final String DATA_FILE = "village_mail_messages.dat";
	private static final int DATA_VERSION = 1; // Increment on format changes to enable migration
	private static final int MAX_MESSAGES_PER_PLAYER = 100;
	private static final int HARD_MESSAGE_CAP = 200;
	private static PlayerMailStorage INSTANCE;

	// Player UUID -> List of messages (ordered by timestamp, newest first)
	private final Map<UUID, List<MailMessage>> playerMessages = new HashMap<>();

	// Players who have placed at least one mailbox (eligible for immediate delivery)
	private final Set<UUID> playersWithMailbox = new HashSet<>();

	// Pending messages for players who haven't placed a mailbox yet
	private final Map<UUID, List<MailMessage>> pendingMessages = new HashMap<>();

	// Mailbox registry: player UUID -> mailbox location (most recently placed)
	private final Map<UUID, MailboxLocation> mailboxLocations = new HashMap<>();

	// Player name cache: player UUID -> last known name (for offline recipient lookup)
	private final Map<UUID, String> playerNames = new HashMap<>();

	// Villager mail cooldowns: mailbox BlockPos -> last delivery world time
	private final Map<net.minecraft.core.BlockPos, Long> villagerMailCooldowns = new HashMap<>();

	private MinecraftServer server;

	private static final int AUTO_SAVE_INTERVAL = 6000; // 5 minutes in ticks
	private int ticksSinceLastSave = 0;

	/**
	 * Tracks a mailbox's physical location for delivery lookup.
	 */
	public record MailboxLocation(String dimension, net.minecraft.core.BlockPos pos) {
		public CompoundTag toNbt() {
			CompoundTag nbt = new CompoundTag();
			nbt.putString("dimension", dimension);
			nbt.putInt("x", pos.getX());
			nbt.putInt("y", pos.getY());
			nbt.putInt("z", pos.getZ());
			return nbt;
		}

		public static MailboxLocation fromNbt(CompoundTag nbt) {
			String dim = nbt.getString("dimension").orElse("minecraft:overworld");
			int x = nbt.getInt("x").orElse(0);
			int y = nbt.getInt("y").orElse(0);
			int z = nbt.getInt("z").orElse(0);
			return new MailboxLocation(dim, new net.minecraft.core.BlockPos(x, y, z));
		}
	}

	private PlayerMailStorage() {}

	/**
	 * Initialize the storage system. Call from mod initializer.
	 */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (INSTANCE == null) {
				INSTANCE = new PlayerMailStorage();
				INSTANCE.server = server;
				INSTANCE.load();
			} else {
				LOGGER.warn("PlayerMailStorage was pre-initialized before SERVER_STARTED (via get()), skipping re-creation");
				// Update server reference in case it changed (e.g. integrated server restart)
				INSTANCE.server = server;
			}
			LOGGER.info("Loaded mail storage");
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (INSTANCE != null) {
				INSTANCE.save();
				LOGGER.info("Saved mail storage");
				INSTANCE = null;
			}
		});
	}

	/**
	 * Get the storage instance for the current server.
	 * If called before SERVER_STARTED, creates and loads the instance eagerly.
	 * The SERVER_STARTED callback will detect this and skip re-creation.
	 */
	public static PlayerMailStorage get(MinecraftServer server) {
		if (INSTANCE == null) {
			LOGGER.info("PlayerMailStorage created early via get() (before SERVER_STARTED)");
			INSTANCE = new PlayerMailStorage();
			INSTANCE.server = server;
			INSTANCE.load();
		}
		return INSTANCE;
	}

	// ========== Message Operations ==========

	/**
	 * Add a message for a recipient.
	 * If the recipient has a mailbox, delivers immediately.
	 * Otherwise, queues for delivery when they place one.
	 */
	public void addMessage(UUID recipientUuid, MailMessage message) {
		if (playersWithMailbox.contains(recipientUuid)) {
			List<MailMessage> messages = playerMessages.computeIfAbsent(recipientUuid, k -> new ArrayList<>());
			enforceMessageCap(messages, recipientUuid);
			messages.add(0, message);
		} else {
			List<MailMessage> messages = pendingMessages.computeIfAbsent(recipientUuid, k -> new ArrayList<>());
			enforceMessageCap(messages, recipientUuid);
			messages.add(0, message);
		}
		markDirty();

		// Save immediately when item attachments are involved to minimize crash-loss window
		if (message.hasAttachments()) {
			save();
		}
	}

	/**
	 * Evict oldest messages to stay within the per-player cap.
	 * Removes oldest read messages first (skipping those with uncollected items),
	 * then oldest unread without uncollected items.
	 *
	 * If all messages have uncollected items, allows overflow up to HARD_MESSAGE_CAP.
	 * Beyond the hard cap, the oldest messages are forcibly removed regardless of
	 * uncollected items to prevent unbounded growth. Items in forcibly-removed
	 * messages are lost.
	 */
	private void enforceMessageCap(List<MailMessage> messages, UUID playerUuid) {
		// Hard cap: forcibly remove oldest messages regardless of uncollected items
		boolean itemsLost = false;
		while (messages.size() >= HARD_MESSAGE_CAP) {
			MailMessage removed = messages.remove(messages.size() - 1);
			if (removed.hasUncollectedItems()) itemsLost = true;
			LOGGER.warn("Hard cap ({}) reached, forcibly removing oldest message {} (uncollected items lost: {})",
				HARD_MESSAGE_CAP, removed.getId(), removed.hasUncollectedItems());
		}
		if (itemsLost && server != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
			if (player != null) {
				player.sendSystemMessage(
					Component.translatable("village-mail.feedback.mailbox_full")
						.withStyle(ChatFormatting.RED),
					false
				);
			}
		}

		// Soft cap: try to evict gracefully, preserving messages with uncollected items
		while (messages.size() >= MAX_MESSAGES_PER_PLAYER) {
			int removeIdx = -1;
			// First pass: oldest read message without uncollected items
			for (int i = messages.size() - 1; i >= 0; i--) {
				MailMessage m = messages.get(i);
				if (m.isRead() && !m.hasUncollectedItems()) {
					removeIdx = i;
					break;
				}
			}
			// Second pass: oldest unread without uncollected items
			if (removeIdx == -1) {
				for (int i = messages.size() - 1; i >= 0; i--) {
					if (!messages.get(i).hasUncollectedItems()) {
						removeIdx = i;
						break;
					}
				}
			}
			// All remaining messages have uncollected items: allow overflow up to hard cap
			if (removeIdx == -1) {
				LOGGER.warn("Mailbox at cap ({}) but all messages have uncollected items; allowing overflow (hard cap: {})",
					MAX_MESSAGES_PER_PLAYER, HARD_MESSAGE_CAP);
				break;
			}
			messages.remove(removeIdx);
		}
	}

	/**
	 * Get all messages for a player (both read and unread).
	 *
	 * Returns a shallow copy: callers may add/remove freely, but the MailMessage
	 * objects are shared references and mutations (setRead, setItemsCollected)
	 * propagate to storage. markAsRead() and handleCollectItems() depend on that;
	 * do not deep-copy without updating every mutation path.
	 */
	public List<MailMessage> getMessages(UUID playerUuid) {
		return new ArrayList<>(playerMessages.getOrDefault(playerUuid, Collections.emptyList()));
	}

	/**
	 * Get unread messages for a player.
	 */
	public List<MailMessage> getUnreadMessages(UUID playerUuid) {
		return getMessages(playerUuid).stream()
			.filter(m -> !m.isRead())
			.collect(Collectors.toList());
	}

	/**
	 * Get read messages for a player.
	 */
	public List<MailMessage> getReadMessages(UUID playerUuid) {
		return getMessages(playerUuid).stream()
			.filter(MailMessage::isRead)
			.collect(Collectors.toList());
	}

	/**
	 * Get a specific message by ID.
	 *
	 * Returns the stored MailMessage itself, not a copy: mutations modify storage
	 * state directly. Persist changes via markAsRead/markItemsCollected or markDirty().
	 */
	public Optional<MailMessage> getMessage(UUID playerUuid, UUID messageId) {
		List<MailMessage> messages = playerMessages.get(playerUuid);
		if (messages == null) return Optional.empty();
		return messages.stream()
			.filter(m -> m.getId().equals(messageId))
			.findFirst();
	}

	/**
	 * Mark a message as read.
	 */
	public void markAsRead(UUID playerUuid, UUID messageId) {
		getMessage(playerUuid, messageId).ifPresent(m -> {
			m.setRead(true);
			markDirty();
		});
	}

	/**
	 * Mark a message's items as collected.
	 */
	public void markItemsCollected(UUID playerUuid, UUID messageId) {
		getMessage(playerUuid, messageId).ifPresent(m -> {
			m.setItemsCollected(true);
			markDirty();
		});
	}

	/**
	 * Delete a message.
	 */
	public void deleteMessage(UUID playerUuid, UUID messageId) {
		List<MailMessage> messages = playerMessages.get(playerUuid);
		if (messages != null) {
			messages.removeIf(m -> m.getId().equals(messageId));
			markDirty();
		}
	}

	/**
	 * Get unread message count for a player.
	 */
	public int getUnreadCount(UUID playerUuid) {
		return (int) playerMessages.getOrDefault(playerUuid, Collections.emptyList()).stream()
			.filter(m -> !m.isRead())
			.count();
	}

	/**
	 * Get total message count for a player.
	 */
	public int getMessageCount(UUID playerUuid) {
		return playerMessages.getOrDefault(playerUuid, Collections.emptyList()).size();
	}

	// ========== Mailbox Registration ==========

	/**
	 * Register that a player has placed a mailbox, with its physical location.
	 * Delivers any pending messages.
	 */
	public void registerMailbox(UUID playerUuid, String dimension, net.minecraft.core.BlockPos pos) {
		playersWithMailbox.add(playerUuid);
		mailboxLocations.put(playerUuid, new MailboxLocation(dimension, pos));

		if (server != null) {
			var player = server.getPlayerList().getPlayer(playerUuid);
			if (player != null) {
				playerNames.put(playerUuid, player.getName().getString());
			}
		}

		List<MailMessage> pending = pendingMessages.remove(playerUuid);
		if (pending != null && !pending.isEmpty()) {
			playerMessages.computeIfAbsent(playerUuid, k -> new ArrayList<>()).addAll(0, pending);
		}
		markDirty();
	}

	/**
	 * Register that a player has placed a mailbox (without location, for backwards compat).
	 * Delivers any pending messages.
	 */
	public void registerMailbox(UUID playerUuid) {
		if (playersWithMailbox.add(playerUuid)) {
			List<MailMessage> pending = pendingMessages.remove(playerUuid);
			if (pending != null && !pending.isEmpty()) {
				playerMessages.computeIfAbsent(playerUuid, k -> new ArrayList<>()).addAll(0, pending);
			}
			markDirty();
		}
	}

	/**
	 * Unregister a mailbox when it's broken. Only removes the location if it matches.
	 * The player stays in playersWithMailbox: they placed a mailbox once, so they can
	 * still receive mail. They'll re-register a location when they open any mailbox.
	 */
	public void unregisterMailbox(UUID playerUuid, net.minecraft.core.BlockPos pos) {
		MailboxLocation loc = mailboxLocations.get(playerUuid);
		if (loc != null && loc.pos().equals(pos)) {
			mailboxLocations.remove(playerUuid);
			// Don't remove from playersWithMailbox: the player may have other mailboxes,
			// and removing them would redirect all future mail to the pending queue.
			markDirty();
		}
	}

	/**
	 * Get the registered mailbox location for a player.
	 */
	public Optional<MailboxLocation> getMailboxLocation(UUID playerUuid) {
		return Optional.ofNullable(mailboxLocations.get(playerUuid));
	}

	/**
	 * Check if a player has registered a mailbox.
	 */
	public boolean hasMailbox(UUID playerUuid) {
		return playersWithMailbox.contains(playerUuid);
	}

	/**
	 * Get UUIDs of all players who have placed a mailbox.
	 */
	public Set<UUID> getMailboxOwners() {
		return Collections.unmodifiableSet(playersWithMailbox);
	}

	/**
	 * Get cached player name, or null if unknown.
	 */
	public String getPlayerName(UUID playerUuid) {
		return playerNames.get(playerUuid);
	}

	/**
	 * Hand a player the mail held for them at the post, mailbox or none: what a public
	 * mailbox or a mail person does when asked. Returns how many letters changed hands.
	 */
	public int collectPending(UUID playerUuid) {
		List<MailMessage> pending = pendingMessages.remove(playerUuid);
		if (pending == null || pending.isEmpty()) return 0;
		playerMessages.computeIfAbsent(playerUuid, k -> new ArrayList<>()).addAll(0, pending);
		markDirty();
		return pending.size();
	}

	/**
	 * Get count of pending messages (for players without mailboxes).
	 */
	public int getPendingCount(UUID playerUuid) {
		return pendingMessages.getOrDefault(playerUuid, Collections.emptyList()).size();
	}

	// ========== Villager Mail Cooldowns ==========

	/**
	 * Get the last villager mail time for a mailbox position.
	 */
	public Optional<Long> getVillagerMailCooldown(net.minecraft.core.BlockPos pos) {
		return Optional.ofNullable(villagerMailCooldowns.get(pos));
	}

	/**
	 * Set the last villager mail time for a mailbox position.
	 */
	public void setVillagerMailCooldown(net.minecraft.core.BlockPos pos, long worldTime) {
		villagerMailCooldowns.put(pos, worldTime);
		markDirty();
	}

	/**
	 * Remove stale cooldown entries older than the threshold.
	 */
	public void cleanStaleCooldowns(long currentWorldTime, long maxAge) {
		if (villagerMailCooldowns.entrySet().removeIf(e -> currentWorldTime - e.getValue() > maxAge)) {
			markDirty();
		}
	}

	// ========== Auto-Save Tick ==========

	/**
	 * Called every server tick. Saves periodically if data is dirty.
	 */
	public void tick() {
		if (dirty) {
			ticksSinceLastSave++;
			if (ticksSinceLastSave >= AUTO_SAVE_INTERVAL) {
				save();
				ticksSinceLastSave = 0;
			}
		}
	}

	// ========== Persistence ==========

	private boolean dirty = false;

	private void markDirty() {
		dirty = true;
	}

	private File getDataFile() {
		if (server == null) return null;
		Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
		return dataDir.resolve(DATA_FILE).toFile();
	}

	public void save() {
		if (!dirty || server == null) return;

		HolderLookup.Provider registries = server.registryAccess();
		CompoundTag nbt = new CompoundTag();
		nbt.putInt("dataVersion", DATA_VERSION);

		// Player messages
		CompoundTag messagesNbt = new CompoundTag();
		for (Map.Entry<UUID, List<MailMessage>> entry : playerMessages.entrySet()) {
			ListTag messageList = new ListTag();
			for (MailMessage message : entry.getValue()) {
				messageList.add(message.toNbt(registries));
			}
			messagesNbt.put(entry.getKey().toString(), messageList);
		}
		nbt.put("playerMessages", messagesNbt);

		// Players with mailboxes
		ListTag mailboxPlayersNbt = new ListTag();
		for (UUID uuid : playersWithMailbox) {
			CompoundTag uuidNbt = new CompoundTag();
			uuidNbt.putString("uuid", uuid.toString());
			mailboxPlayersNbt.add(uuidNbt);
		}
		nbt.put("playersWithMailbox", mailboxPlayersNbt);

		// Pending messages
		CompoundTag pendingNbt = new CompoundTag();
		for (Map.Entry<UUID, List<MailMessage>> entry : pendingMessages.entrySet()) {
			ListTag messageList = new ListTag();
			for (MailMessage message : entry.getValue()) {
				messageList.add(message.toNbt(registries));
			}
			pendingNbt.put(entry.getKey().toString(), messageList);
		}
		nbt.put("pendingMessages", pendingNbt);

		// Mailbox locations
		CompoundTag locationsNbt = new CompoundTag();
		for (Map.Entry<UUID, MailboxLocation> entry : mailboxLocations.entrySet()) {
			locationsNbt.put(entry.getKey().toString(), entry.getValue().toNbt());
		}
		nbt.put("mailboxLocations", locationsNbt);

		// Player names
		CompoundTag namesNbt = new CompoundTag();
		for (Map.Entry<UUID, String> entry : playerNames.entrySet()) {
			namesNbt.putString(entry.getKey().toString(), entry.getValue());
		}
		nbt.put("playerNames", namesNbt);

		// Villager mail cooldowns
		CompoundTag cooldownsNbt = new CompoundTag();
		for (Map.Entry<net.minecraft.core.BlockPos, Long> entry : villagerMailCooldowns.entrySet()) {
			CompoundTag posNbt = new CompoundTag();
			posNbt.putInt("x", entry.getKey().getX());
			posNbt.putInt("y", entry.getKey().getY());
			posNbt.putInt("z", entry.getKey().getZ());
			posNbt.putLong("time", entry.getValue());
			cooldownsNbt.put(entry.getKey().toShortString(), posNbt);
		}
		nbt.put("villagerMailCooldowns", cooldownsNbt);

		// Write to temp file, then atomic rename for crash safety
		try {
			File file = getDataFile();
			if (file != null) {
				file.getParentFile().mkdirs();
				Path filePath = file.toPath();
				Path tempPath = filePath.resolveSibling(DATA_FILE + ".tmp");
				Path backupPath = filePath.resolveSibling(DATA_FILE + ".bak");

				NbtIo.writeCompressed(nbt, tempPath);

				if (file.exists()) {
					Files.move(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
				}
				try {
					Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException e) {
					// ATOMIC_MOVE not supported on this filesystem, fall back
					Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
				}
				Files.deleteIfExists(backupPath);
				dirty = false;
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save mail storage: " + e.getMessage());
			// Try to restore from backup if the main file was moved
			try {
				File file = getDataFile();
				if (file != null) {
					Path backupPath = file.toPath().resolveSibling(DATA_FILE + ".bak");
					if (Files.exists(backupPath) && !file.exists()) {
						Files.move(backupPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
						LOGGER.info("Restored mail data from backup after save failure");
					}
				}
			} catch (IOException restoreEx) {
				LOGGER.error("Failed to restore backup after save failure: " + restoreEx.getMessage());
			}
		}
	}

	public void load() {
		if (server == null) return;

		File file = getDataFile();
		if (file == null) return;

		// Fall back to backup if main file is missing (crash during save)
		if (!file.exists()) {
			File backupFile = new File(file.getParentFile(), DATA_FILE + ".bak");
			if (backupFile.exists()) {
				LOGGER.warn("Main data file missing, restoring from backup");
				if (backupFile.renameTo(file)) {
					LOGGER.info("Restored mail data from backup");
				} else {
					LOGGER.error("Failed to restore backup file");
					return;
				}
			} else {
				return;
			}
		}

		HolderLookup.Provider registries = server.registryAccess();

		try {
			CompoundTag nbt = NbtIo.readCompressed(file.toPath(), NbtAccounter.create(128L * 1024 * 1024));

			int savedVersion = nbt.getInt("dataVersion").orElse(0);
			if (savedVersion > DATA_VERSION) {
				LOGGER.warn("Mail data is from a newer version ({} > {}), loading anyway; some data may be lost",
					savedVersion, DATA_VERSION);
			}

			// Player messages
			nbt.getCompound("playerMessages").ifPresent(messagesNbt -> {
				for (String uuidStr : messagesNbt.keySet()) {
					UUID playerUuid = UUID.fromString(uuidStr);
					messagesNbt.getList(uuidStr).ifPresent(messageList -> {
						List<MailMessage> messages = new ArrayList<>();
						for (int i = 0; i < messageList.size(); i++) {
							if (messageList.get(i) instanceof CompoundTag msgNbt) {
								try {
									MailMessage msg = MailMessage.fromNbt(msgNbt, registries);
									if (msg != null) {
										messages.add(msg);
									}
								} catch (Exception e) {
									LOGGER.error("Skipping corrupt message for player {}: {}", uuidStr, e.getMessage());
								}
							}
						}
						playerMessages.put(playerUuid, messages);
					});
				}
			});

			// Players with mailboxes
			nbt.getList("playersWithMailbox").ifPresent(mailboxPlayersNbt -> {
				for (int i = 0; i < mailboxPlayersNbt.size(); i++) {
					if (mailboxPlayersNbt.get(i) instanceof CompoundTag uuidNbt) {
						uuidNbt.getString("uuid").ifPresent(uuidStr -> {
							if (!uuidStr.isEmpty()) {
								try {
									playersWithMailbox.add(UUID.fromString(uuidStr));
								} catch (IllegalArgumentException e) {
									LOGGER.error("Skipping corrupt mailbox UUID: {}", uuidStr);
								}
							}
						});
					}
				}
			});

			// Pending messages
			nbt.getCompound("pendingMessages").ifPresent(pendingNbt -> {
				for (String uuidStr : pendingNbt.keySet()) {
					UUID playerUuid = UUID.fromString(uuidStr);
					pendingNbt.getList(uuidStr).ifPresent(messageList -> {
						List<MailMessage> messages = new ArrayList<>();
						for (int i = 0; i < messageList.size(); i++) {
							if (messageList.get(i) instanceof CompoundTag msgNbt) {
								try {
									MailMessage msg = MailMessage.fromNbt(msgNbt, registries);
									if (msg != null) {
										messages.add(msg);
									}
								} catch (Exception e) {
									LOGGER.error("Skipping corrupt pending message for player {}: {}", uuidStr, e.getMessage());
								}
							}
						}
						pendingMessages.put(playerUuid, messages);
					});
				}
			});

			// Mailbox locations
			nbt.getCompound("mailboxLocations").ifPresent(locationsNbt -> {
				for (String uuidStr : locationsNbt.keySet()) {
					UUID playerUuid = UUID.fromString(uuidStr);
					locationsNbt.getCompound(uuidStr).ifPresent(locNbt -> {
						mailboxLocations.put(playerUuid, MailboxLocation.fromNbt(locNbt));
						playersWithMailbox.add(playerUuid);
					});
				}
			});

			// Player names
			nbt.getCompound("playerNames").ifPresent(namesNbt -> {
				for (String uuidStr : namesNbt.keySet()) {
					try {
						UUID playerUuid = UUID.fromString(uuidStr);
						namesNbt.getString(uuidStr).ifPresent(name -> {
							if (!name.isEmpty()) {
								playerNames.put(playerUuid, name);
							}
						});
					} catch (IllegalArgumentException e) {
						LOGGER.error("Skipping corrupt player name UUID: {}", uuidStr);
					}
				}
			});

			// Villager mail cooldowns
			nbt.getCompound("villagerMailCooldowns").ifPresent(cooldownsNbt -> {
				for (String key : cooldownsNbt.keySet()) {
					cooldownsNbt.getCompound(key).ifPresent(posNbt -> {
						int cx = posNbt.getInt("x").orElse(0);
						int cy = posNbt.getInt("y").orElse(0);
						int cz = posNbt.getInt("z").orElse(0);
						long time = posNbt.getLong("time").orElse(0L);
						villagerMailCooldowns.put(new net.minecraft.core.BlockPos(cx, cy, cz), time);
					});
				}
			});

		} catch (IOException e) {
			LOGGER.error("Failed to load mail storage: " + e.getMessage());
		}
	}
}
