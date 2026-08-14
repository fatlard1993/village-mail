package justfatlard.village_mail.mail;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * Represents an interactive button in a mail message.
 * Buttons can trigger various actions when clicked by the recipient.
 */
public class MessageButton {
	private final String id;
	private final String label;
	private final ButtonType type;
	private final Identifier handler; // For CUSTOM type: mod_id:handler_name
	private final CompoundTag data;

	public enum ButtonType {
		REPLY,           // Opens compose screen addressed to sender
		FORWARD,         // Opens compose screen with message body pre-filled
		COLLECT_ITEMS,   // Moves attached items to player inventory
		ACCEPT,          // Generic accept action (triggers registered handler)
		DECLINE,         // Generic decline action (triggers registered handler)
		CUSTOM           // Fully custom action via registered handler
	}

	public MessageButton(String id, String label, ButtonType type, Identifier handler, CompoundTag data) {
		this.id = id;
		this.label = label;
		this.type = type;
		this.handler = handler;
		this.data = data != null ? data : new CompoundTag();
	}

	public String getId() { return id; }
	public String getLabel() { return label; }
	public ButtonType getType() { return type; }
	public Identifier getHandler() { return handler; }
	public CompoundTag getData() { return data.copy(); }

	public CompoundTag toNbt() {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("id", id);
		nbt.putString("label", label);
		nbt.putString("type", type.name());
		if (handler != null) {
			nbt.putString("handler", handler.toString());
		}
		if (!data.isEmpty()) {
			nbt.put("data", data.copy());
		}
		return nbt;
	}

	public static MessageButton fromNbt(CompoundTag nbt) {
		String id = nbt.getString("id").orElse("");
		String label = nbt.getString("label").orElse("");
		ButtonType type;
		try {
			type = ButtonType.valueOf(nbt.getString("type").orElse("CUSTOM"));
		} catch (IllegalArgumentException e) {
			type = ButtonType.CUSTOM;
		}
		Identifier handler = nbt.getString("handler")
			.map(Identifier::tryParse)
			.orElse(null);
		CompoundTag data = nbt.getCompound("data").orElse(new CompoundTag());
		return new MessageButton(id, label, type, handler, data);
	}

	/**
	 * Creates a Reply button that opens compose screen addressed to sender.
	 */
	public static MessageButton reply() {
		return new MessageButton("reply", "village-mail.button.reply", ButtonType.REPLY, null, null);
	}

	/**
	 * Creates a Forward button that opens compose with message content.
	 */
	public static MessageButton forward() {
		return new MessageButton("forward", "village-mail.button.forward", ButtonType.FORWARD, null, null);
	}

	/**
	 * Creates a Collect Items button for messages with attachments.
	 */
	public static MessageButton collectItems() {
		return new MessageButton("collect", "village-mail.button.collect_items", ButtonType.COLLECT_ITEMS, null, null);
	}

	/**
	 * Creates an Accept button with a custom handler.
	 * @param handler The mod handler identifier (e.g., "my-mod:accept_quest")
	 * @param data Custom data to pass to the handler
	 */
	public static MessageButton accept(Identifier handler, CompoundTag data) {
		return new MessageButton("accept", "village-mail.button.accept", ButtonType.ACCEPT, handler, data);
	}

	/**
	 * Creates a Decline button with a custom handler.
	 * @param handler The mod handler identifier (e.g., "my-mod:decline_quest")
	 * @param data Custom data to pass to the handler
	 */
	public static MessageButton decline(Identifier handler, CompoundTag data) {
		return new MessageButton("decline", "village-mail.button.decline", ButtonType.DECLINE, handler, data);
	}

	/**
	 * Creates a fully custom button.
	 * @param id Unique button ID within the message
	 * @param label Display text for the button
	 * @param handler The mod handler identifier
	 * @param data Custom data to pass to the handler
	 */
	public static MessageButton custom(String id, String label, Identifier handler, CompoundTag data) {
		return new MessageButton(id, label, ButtonType.CUSTOM, handler, data);
	}
}
