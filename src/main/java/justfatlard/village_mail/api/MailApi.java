package justfatlard.village_mail.api;

import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.mail.MessageButton;
import justfatlard.village_mail.mail.PlayerMailStorage;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public API for the Village Mail mod.
 * Other mods can use this to send messages, register button handlers, and subscribe to events.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Send a simple message
 * MailApi.sendMessage(server, recipientUuid, "Quest Master", "Your quest awaits!");
 *
 * // Send a message with an item attached
 * MailApi.sendMessageWithItems(server, recipientUuid, "Reward System",
 *     "Here's your reward!", new ItemStack(Items.DIAMOND, 5));
 *
 * // Send a message with custom buttons
 * MailApi.registerButtonHandler(Identifier.of("mymod", "accept_quest"),
 *     (server, player, message, button) -> {
 *         // Handle button click
 *         return true; // true = delete message after handling
 *     });
 *
 * MailApi.sendModMessage(server, Identifier.of("mymod", "quest"), "Quest System",
 *     recipientUuid, "Do you accept this quest?", ItemStack.EMPTY,
 *     List.of(
 *         MessageButton.accept(Identifier.of("mymod", "accept_quest"), customData),
 *         MessageButton.decline(Identifier.of("mymod", "decline_quest"), customData)
 *     ));
 * }</pre>
 */
public final class MailApi {
	private MailApi() {} // Static-only class

	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");

	// ========== SENDING MESSAGES ==========

	/**
	 * Send a simple text message (from system/NPC).
	 *
	 * <p>Automatically adds Reply and Forward buttons to the message.</p>
	 *
	 * @param server The server instance
	 * @param recipientUuid UUID of the recipient player
	 * @param senderName Display name for the sender (e.g., "Quest Master")
	 * @param body Message body (max 256 characters)
	 * @return The UUID of the sent message
	 */
	public static UUID sendMessage(MinecraftServer server, UUID recipientUuid, String senderName, String body) {
		return sendMessage(server, recipientUuid, null, senderName, body);
	}

	/**
	 * Send a player-to-player text message.
	 *
	 * <p>Automatically adds Reply and Forward buttons to the message.</p>
	 *
	 * @param server The server instance
	 * @param recipientUuid UUID of the recipient player
	 * @param senderUuid UUID of the sending player (null for system/NPC)
	 * @param senderName Display name for the sender
	 * @param body Message body (max 256 characters)
	 * @return The UUID of the sent message
	 */
	public static UUID sendMessage(MinecraftServer server, UUID recipientUuid, UUID senderUuid, String senderName, String body) {
		MailMessage.MessageType type = senderUuid != null ? MailMessage.MessageType.PLAYER : MailMessage.MessageType.MOD;

		MailMessage message = new MailMessage.Builder()
			.sender(senderUuid, senderName)
			.recipient(recipientUuid)
			.body(body)
			.type(type)
			.button(MessageButton.reply())
			.button(MessageButton.forward())
			.build();

		PlayerMailStorage.get(server).addMessage(recipientUuid, message);
		MailApiImpl.fireMessageSent(recipientUuid, message);
		notifyPlayerIfOnline(server, recipientUuid, senderName);

		return message.getId();
	}

	/**
	 * Send a message with a single item attachment.
	 *
	 * <p>Automatically adds Reply, Forward, and Collect Items buttons to the message.</p>
	 *
	 * @param server The server instance
	 * @param recipientUuid UUID of the recipient player
	 * @param senderName Display name for the sender
	 * @param body Message body (max 256 characters)
	 * @param attachment The ItemStack to attach (use {@link ItemStack#EMPTY} for no attachment)
	 * @return The UUID of the sent message
	 */
	public static UUID sendMessageWithItems(MinecraftServer server, UUID recipientUuid,
			String senderName, String body, ItemStack attachment) {
		MailMessage.Builder builder = new MailMessage.Builder()
			.sender(null, senderName)
			.recipient(recipientUuid)
			.body(body)
			.type(MailMessage.MessageType.MOD)
			.button(MessageButton.reply())
			.button(MessageButton.forward());

		if (attachment != null && !attachment.isEmpty()) {
			builder.attachment(attachment);
			builder.button(MessageButton.collectItems());
		}

		MailMessage message = builder.build();
		PlayerMailStorage.get(server).addMessage(recipientUuid, message);
		MailApiImpl.fireMessageSent(recipientUuid, message);
		notifyPlayerIfOnline(server, recipientUuid, senderName);

		return message.getId();
	}

	/**
	 * Send a message from a mod with a custom sender name and custom buttons.
	 *
	 * @param server The server instance
	 * @param modId Identifier for the sending mod (used for message type tracking)
	 * @param senderName Human-readable display name for the sender (e.g., "Quest System")
	 * @param recipientUuid UUID of the recipient player
	 * @param body Message body (max 256 characters)
	 * @param attachment The ItemStack to attach (use {@link ItemStack#EMPTY} for no attachment)
	 * @param buttons List of custom buttons
	 * @return The UUID of the sent message
	 */
	public static UUID sendModMessage(MinecraftServer server, Identifier modId, String senderName,
			UUID recipientUuid, String body, ItemStack attachment, List<MessageButton> buttons) {
		MailMessage.Builder builder = new MailMessage.Builder()
			.sender(null, senderName)
			.recipient(recipientUuid)
			.body(body)
			.type(MailMessage.MessageType.MOD)
			.buttons(buttons);

		if (attachment != null && !attachment.isEmpty()) {
			builder.attachment(attachment);
		}

		MailMessage message = builder.build();
		PlayerMailStorage.get(server).addMessage(recipientUuid, message);
		MailApiImpl.fireMessageSent(recipientUuid, message);
		notifyPlayerIfOnline(server, recipientUuid, senderName);

		return message.getId();
	}

	/**
	 * Send a message from a mod with custom buttons.
	 *
	 * <p><b>Deprecated:</b> Uses {@code modId.getNamespace()} as the sender display name.
	 * Prefer {@link #sendModMessage(MinecraftServer, Identifier, String, UUID, String, ItemStack, List)}
	 * which accepts an explicit sender name.</p>
	 *
	 * @param server The server instance
	 * @param modId Identifier for the sending mod (namespace used as sender name)
	 * @param recipientUuid UUID of the recipient player
	 * @param body Message body (max 256 characters)
	 * @param attachments List of ItemStacks to attach (only the first item is used; max 1)
	 * @param buttons List of custom buttons
	 * @return The UUID of the sent message
	 * @deprecated Use {@link #sendModMessage(MinecraftServer, Identifier, String, UUID, String, ItemStack, List)} instead
	 */
	@Deprecated
	public static UUID sendModMessage(MinecraftServer server, Identifier modId, UUID recipientUuid,
			String body, List<ItemStack> attachments, List<MessageButton> buttons) {
		LOGGER.warn("[village-mail] sendModMessage called without explicit senderName; using modId namespace '{}'. "
			+ "Prefer the overload with a senderName parameter.", modId.getNamespace());

		ItemStack attachment = (attachments != null && !attachments.isEmpty()) ? attachments.get(0) : ItemStack.EMPTY;
		if (attachments != null && attachments.size() > 1) {
			LOGGER.warn("[village-mail] sendModMessage received {} attachments but only 1 is supported. Using the first item only.",
				attachments.size());
		}

		return sendModMessage(server, modId, modId.getNamespace(), recipientUuid, body, attachment, buttons);
	}

	/**
	 * Send a pre-built message. Uses the message as-is. No buttons are added automatically.
	 *
	 * <p><b>Important:</b> Unlike the convenience methods ({@link #sendMessage(MinecraftServer, UUID, String, String)},
	 * {@link #sendMessageWithItems}), this method does NOT automatically add Reply or Forward buttons.
	 * The message is sent exactly as constructed. Add buttons via {@link MailMessage.Builder#button} before
	 * calling this method if interactive buttons are desired.</p>
	 *
	 * @param server The server instance
	 * @param message The message to send (must have a non-null recipientUuid)
	 * @return {@code true} if the message was sent, {@code false} if it was rejected (null recipient)
	 */
	public static boolean sendMessage(MinecraftServer server, MailMessage message) {
		if (message.getRecipientUuid() == null) {
			LOGGER.warn("[village-mail] Refusing to send message {}: recipientUuid is null", message.getId());
			return false;
		}

		PlayerMailStorage.get(server).addMessage(message.getRecipientUuid(), message);
		MailApiImpl.fireMessageSent(message.getRecipientUuid(), message);
		notifyPlayerIfOnline(server, message.getRecipientUuid(), message.getSenderName());
		return true;
	}

	/**
	 * Get a message builder for full control over message creation.
	 */
	public static MailMessage.Builder messageBuilder() {
		return new MailMessage.Builder();
	}

	// ========== BUTTON HANDLERS ==========

	/**
	 * Register a handler for custom button clicks.
	 *
	 * @param handlerId The handler identifier (e.g., "mymod:accept_quest")
	 * @param handler The handler to invoke when the button is clicked
	 */
	public static void registerButtonHandler(Identifier handlerId, ButtonHandler handler) {
		MailApiImpl.registerHandler(handlerId, handler);
	}

	/**
	 * <b>Internal use only -- do not call directly.</b>
	 *
	 * <p>This method is invoked by the network handler when a player clicks a button in
	 * the mail UI. It is part of the internal wiring between the network layer and the
	 * button handler registry. External mods should use
	 * {@link #registerButtonHandler(Identifier, ButtonHandler)} to register handlers
	 * instead of calling this method.</p>
	 *
	 * @param server The server instance
	 * @param player The player who clicked the button
	 * @param message The message containing the button
	 * @param button The button that was clicked
	 * @return true if the button was handled successfully
	 */
	public static boolean handleButton(MinecraftServer server, ServerPlayerEntity player,
			MailMessage message, MessageButton button) {
		Identifier handlerId = button.getHandler();
		if (handlerId == null) {
			return false;
		}

		if (!MailApiImpl.hasHandler(handlerId)) {
			return false;
		}
		// Delegate to Impl which has proper try-catch around handler invocation
		boolean shouldDelete = MailApiImpl.invokeButtonHandler(handlerId, server, player, message, button);
		if (shouldDelete) {
			PlayerMailStorage.get(server).deleteMessage(player.getUuid(), message.getId());
		}
		return true;
	}

	/**
	 * Handler interface for custom button clicks.
	 */
	@FunctionalInterface
	public interface ButtonHandler {
		/**
		 * Handle a button click.
		 *
		 * @param server The server instance
		 * @param player The player who clicked the button
		 * @param message The message containing the button
		 * @param button The button that was clicked
		 * @return true if the message should be deleted after handling, false to keep it
		 */
		boolean handle(MinecraftServer server, ServerPlayerEntity player, MailMessage message, MessageButton button);
	}

	// ========== EVENTS ==========

	/**
	 * Register a callback for when a message is read.
	 *
	 * @param callback Called with (recipientUuid, message) when a message is first read
	 */
	public static void onMessageRead(BiConsumer<UUID, MailMessage> callback) {
		MailApiImpl.addMessageReadCallback(callback);
	}

	/**
	 * Register a callback for when items are collected from a message.
	 *
	 * @param callback Called with (recipientUuid, message) when items are collected
	 */
	public static void onItemsCollected(BiConsumer<UUID, MailMessage> callback) {
		MailApiImpl.addItemsCollectedCallback(callback);
	}

	/**
	 * Register a callback for when a message is sent.
	 *
	 * @param callback Called with (recipientUuid, message) when a message is sent
	 */
	public static void onMessageSent(BiConsumer<UUID, MailMessage> callback) {
		MailApiImpl.addMessageSentCallback(callback);
	}

	// ========== QUERIES ==========

	/**
	 * Check if a player has a mailbox (can receive mail immediately).
	 */
	public static boolean hasMailbox(MinecraftServer server, UUID playerUuid) {
		return PlayerMailStorage.get(server).hasMailbox(playerUuid);
	}

	/**
	 * Get unread message count for a player.
	 */
	public static int getUnreadCount(MinecraftServer server, UUID playerUuid) {
		return PlayerMailStorage.get(server).getUnreadCount(playerUuid);
	}

	/**
	 * Get total message count for a player.
	 */
	public static int getMessageCount(MinecraftServer server, UUID playerUuid) {
		return PlayerMailStorage.get(server).getMessageCount(playerUuid);
	}

	// ========== HELPERS ==========

	private static void notifyPlayerIfOnline(MinecraftServer server, UUID recipientUuid, String senderName) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(recipientUuid);
		if (player != null) {
			if (senderName != null) {
				player.sendMessage(
					Text.translatable("village-mail.api.new_mail_from", senderName)
						.formatted(net.minecraft.util.Formatting.YELLOW),
					true
				);
			} else {
				player.sendMessage(
					Text.translatable("village-mail.api.new_mail")
						.formatted(net.minecraft.util.Formatting.YELLOW),
					true
				);
			}
			// Push updated unread count to HUD badge
			justfatlard.village_mail.network.MailNetworking.sendUnreadCount(player);
		}
	}
}
