package justfatlard.village_mail.mail;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.HolderLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable after construction except for read/collected state (volatile).
 * Built via {@link Builder}; serialized to NBT for persistence.
 */
public class MailMessage {
	public static final int MAX_BODY_LENGTH = 256;
	public static final int MAX_ATTACHMENTS = 1;

	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");

	private final UUID id;
	private final UUID senderUuid;      // null for system/villager messages
	private final String senderName;
	private final UUID recipientUuid;
	private final String body;
	private final long timestamp;
	private final MessageType type;
	private final List<ItemStack> attachments;
	private final List<MessageButton> buttons;
	private final CompoundTag metadata;

	private volatile boolean read;
	private volatile boolean itemsCollected;

	public enum MessageType {
		PLAYER,      // Player-to-player message
		VILLAGER,    // From villager/village system
		SYSTEM,      // System notification (migration, errors)
		QUEST,       // Quest-related (from Village Quests integration)
		MOD          // From other mods via API
	}

	private MailMessage(Builder builder) {
		this.id = builder.id != null ? builder.id : UUID.randomUUID();
		this.senderUuid = builder.senderUuid;
		this.senderName = builder.senderName;
		this.recipientUuid = builder.recipientUuid;
		this.body = truncateBody(builder.body);
		this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
		this.type = builder.type != null ? builder.type : MessageType.PLAYER;
		this.attachments = new ArrayList<>(builder.attachments);
		this.buttons = new ArrayList<>(builder.buttons);
		this.metadata = builder.metadata != null ? builder.metadata.copy() : new CompoundTag();
		this.read = builder.read;
		this.itemsCollected = builder.itemsCollected;
	}

	private static String truncateBody(String body) {
		if (body == null) return "";
		return body.length() > MAX_BODY_LENGTH ? body.substring(0, MAX_BODY_LENGTH) : body;
	}

	public UUID getId() { return id; }
	public UUID getSenderUuid() { return senderUuid; }
	public String getSenderName() { return senderName; }
	public UUID getRecipientUuid() { return recipientUuid; }
	public String getBody() { return body; }
	public long getTimestamp() { return timestamp; }
	public MessageType getType() { return type; }
	public List<ItemStack> getAttachments() { return new ArrayList<>(attachments); }
	public List<MessageButton> getButtons() { return new ArrayList<>(buttons); }
	public CompoundTag getMetadata() { return metadata.copy(); }
	public boolean isRead() { return read; }
	public boolean isItemsCollected() { return itemsCollected; }

	public boolean hasAttachments() {
		return !attachments.isEmpty() && attachments.stream().anyMatch(s -> !s.isEmpty());
	}

	public boolean hasUncollectedItems() {
		return hasAttachments() && !itemsCollected;
	}

	public void setRead(boolean read) { this.read = read; }
	public void setItemsCollected(boolean collected) { this.itemsCollected = collected; }

	/**
	 * Get a preview of the message body (first 50 chars).
	 */
	public String getBodyPreview() {
		if (body.length() <= 50) return body;
		return body.substring(0, 47) + "...";
	}

	public CompoundTag toNbt(HolderLookup.Provider registries) {
		CompoundTag nbt = new CompoundTag();

		nbt.putString("id", id.toString());
		if (senderUuid != null) {
			nbt.putString("senderUuid", senderUuid.toString());
		}
		nbt.putString("senderName", senderName != null ? senderName : "");
		nbt.putString("recipientUuid", recipientUuid.toString());
		nbt.putString("body", body);
		nbt.putLong("timestamp", timestamp);
		nbt.putString("type", type.name());
		nbt.putBoolean("read", read);
		nbt.putBoolean("itemsCollected", itemsCollected);

		if (!attachments.isEmpty()) {
			ListTag attachmentList = new ListTag();
			for (ItemStack stack : attachments) {
				if (!stack.isEmpty()) {
					CompoundTag stackNbt = new CompoundTag();
					stackNbt.store(ItemStack.MAP_CODEC, registries.createSerializationContext(NbtOps.INSTANCE), stack);
					attachmentList.add(stackNbt);
				}
			}
			nbt.put("attachments", attachmentList);
		}

		if (!buttons.isEmpty()) {
			ListTag buttonList = new ListTag();
			for (MessageButton button : buttons) {
				buttonList.add(button.toNbt());
			}
			nbt.put("buttons", buttonList);
		}

		if (!metadata.isEmpty()) {
			nbt.put("metadata", metadata.copy());
		}

		return nbt;
	}

	/**
	 * Deserialize a MailMessage from NBT.
	 *
	 * <p>If the recipient UUID is empty or malformed, the message is skipped and {@code null}
	 * is returned with a warning logged. Callers must handle the null case.</p>
	 *
	 * @param nbt The NBT compound to deserialize from
	 * @param registries The registry wrapper for item deserialization
	 * @return The deserialized message, or {@code null} if the recipient UUID is missing/invalid
	 */
	public static MailMessage fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
		Builder builder = new Builder();

		String idStr = nbt.getString("id").orElse("");
		builder.id = idStr.isEmpty() ? UUID.randomUUID() : UUID.fromString(idStr);

		nbt.getString("senderUuid").ifPresent(s -> builder.senderUuid = UUID.fromString(s));
		builder.senderName = nbt.getString("senderName").orElse("");

		String recipientStr = nbt.getString("recipientUuid").orElse("");
		if (recipientStr.isEmpty()) {
			LOGGER.warn("[village-mail] Skipping message {}: recipient UUID is empty", builder.id);
			return null;
		}
		try {
			builder.recipientUuid = UUID.fromString(recipientStr);
		} catch (IllegalArgumentException e) {
			LOGGER.warn("[village-mail] Skipping message {}: malformed recipient UUID '{}'", builder.id, recipientStr);
			return null;
		}

		builder.body = nbt.getString("body").orElse("");
		builder.timestamp = nbt.getLong("timestamp").orElse(0L);
		try {
			builder.type = MessageType.valueOf(nbt.getString("type").orElse("PLAYER"));
		} catch (IllegalArgumentException e) {
			builder.type = MessageType.PLAYER;
		}
		builder.read = nbt.getBoolean("read").orElse(false);
		builder.itemsCollected = nbt.getBoolean("itemsCollected").orElse(false);

		nbt.getList("attachments").ifPresent(attachmentList -> {
			for (int i = 0; i < attachmentList.size(); i++) {
				if (attachmentList.get(i) instanceof CompoundTag stackNbt) {
					stackNbt.read(ItemStack.MAP_CODEC, registries.createSerializationContext(NbtOps.INSTANCE)).ifPresent(stack -> {
						if (!stack.isEmpty()) {
							builder.attachments.add(stack);
						}
					});
				}
			}
		});

		nbt.getList("buttons").ifPresent(buttonList -> {
			for (int i = 0; i < buttonList.size(); i++) {
				if (buttonList.get(i) instanceof CompoundTag buttonNbt) {
					builder.buttons.add(MessageButton.fromNbt(buttonNbt));
				}
			}
		});

		nbt.getCompound("metadata").ifPresent(meta -> builder.metadata = meta.copy());

		return builder.build();
	}

	/**
	 * Builder for creating MailMessage instances.
	 */
	public static class Builder {
		private UUID id;
		private UUID senderUuid;
		private String senderName;
		private UUID recipientUuid;
		private String body = "";
		private long timestamp;
		private MessageType type = MessageType.PLAYER;
		private final List<ItemStack> attachments = new ArrayList<>();
		private final List<MessageButton> buttons = new ArrayList<>();
		private CompoundTag metadata;
		private boolean read = false;
		private boolean itemsCollected = false;

		public Builder() {}

		public Builder id(UUID id) {
			this.id = id;
			return this;
		}

		public Builder sender(UUID uuid, String name) {
			this.senderUuid = uuid;
			this.senderName = name;
			return this;
		}

		public Builder recipient(UUID uuid) {
			this.recipientUuid = uuid;
			return this;
		}

		public Builder body(String body) {
			this.body = body;
			return this;
		}

		public Builder timestamp(long timestamp) {
			this.timestamp = timestamp;
			return this;
		}

		public Builder type(MessageType type) {
			this.type = type;
			return this;
		}

		public Builder attachment(ItemStack stack) {
			if (attachments.size() < MAX_ATTACHMENTS && !stack.isEmpty()) {
				this.attachments.add(stack.copy());
			}
			return this;
		}

		public Builder attachments(List<ItemStack> stacks) {
			for (ItemStack stack : stacks) {
				attachment(stack);
			}
			return this;
		}

		public Builder button(MessageButton button) {
			this.buttons.add(button);
			return this;
		}

		public Builder buttons(List<MessageButton> buttons) {
			this.buttons.addAll(buttons);
			return this;
		}

		public Builder metadata(CompoundTag metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder metadata(String key, CompoundTag data) {
			if (this.metadata == null) {
				this.metadata = new CompoundTag();
			}
			this.metadata.put(key, data);
			return this;
		}

		public Builder read(boolean read) {
			this.read = read;
			return this;
		}

		public Builder itemsCollected(boolean collected) {
			this.itemsCollected = collected;
			return this;
		}

		public MailMessage build() {
			if (recipientUuid == null) {
				throw new IllegalStateException("MailMessage requires a recipient UUID");
			}
			return new MailMessage(this);
		}
	}
}
