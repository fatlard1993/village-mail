package justfatlard.village_mail.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.api.MailApi;
import justfatlard.village_mail.api.MailApiImpl;
import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.mail.MessageButton;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.network.MailPayloads.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles network packet registration and server-side processing for the mail system.
 */
public class MailNetworking {

	private static final long SEND_COOLDOWN_MS = 1000; // 1 second between sends
	private static final long READ_COOLDOWN_MS = 200;  // 200ms between read-only requests
	private static final Map<UUID, Long> lastSendTime = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> lastReadTime = new ConcurrentHashMap<>();

	/**
	 * Clean up rate limiter state when a player disconnects.
	 */
	public static void onPlayerDisconnect(UUID playerUuid) {
		lastSendTime.remove(playerUuid);
		lastReadTime.remove(playerUuid);
	}

	private static boolean isReadThrottled(ServerPlayerEntity player) {
		long now = System.currentTimeMillis();
		Long last = lastReadTime.get(player.getUuid());
		if (last != null && now - last < READ_COOLDOWN_MS) return true;
		lastReadTime.put(player.getUuid(), now);
		return false;
	}

	/**
	 * Register all payload types. Call from mod initializer.
	 */
	public static void registerPayloads() {
		// Client to Server payloads
		PayloadTypeRegistry.playC2S().register(SwitchTabC2S.ID, SwitchTabC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(OpenMessageC2S.ID, OpenMessageC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(ButtonClickC2S.ID, ButtonClickC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(CollectItemsC2S.ID, CollectItemsC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(SendMessageC2S.ID, SendMessageC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(DeleteMessageC2S.ID, DeleteMessageC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(RefreshMessagesC2S.ID, RefreshMessagesC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestRecipientsC2S.ID, RequestRecipientsC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(DonateC2S.ID, DonateC2S.CODEC);

		// Server to Client payloads
		PayloadTypeRegistry.playS2C().register(MessageListS2C.ID, MessageListS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(MessageDetailS2C.ID, MessageDetailS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(UnreadCountS2C.ID, UnreadCountS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(RecipientsListS2C.ID, RecipientsListS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(ActionResultS2C.ID, ActionResultS2C.CODEC);
	}

	/**
	 * Register server-side packet handlers. Call from mod initializer.
	 */
	public static void registerServerHandlers() {
		// Handle tab switching — requires mailbox screen to be open
		ServerPlayNetworking.registerGlobalReceiver(SwitchTabC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)) return;
			if (isReadThrottled(player)) return;

			sendMessageList(player, payload.showRead());
		});

		// Handle opening a specific message — requires mailbox screen to be open
		ServerPlayNetworking.registerGlobalReceiver(OpenMessageC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)) return;
			String messageIdStr = payload.messageId();

			try {
				UUID messageId = UUID.fromString(messageIdStr);
				PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
				Optional<MailMessage> messageOpt = storage.getMessage(player.getUuid(), messageId);

				if (messageOpt.isPresent()) {
					MailMessage message = messageOpt.get();

					// Mark as read
					if (!message.isRead()) {
						storage.markAsRead(player.getUuid(), messageId);
						MailApiImpl.fireMessageRead(player.getUuid(), message);
						sendUnreadCount(player);
					}

					// Send full message details
					sendMessageDetail(player, message);
				} else {
					sendActionResult(player, false, Text.translatable("village-mail.error.message_not_found").getString());
				}
			} catch (IllegalArgumentException e) {
				sendActionResult(player, false, Text.translatable("village-mail.error.invalid_message").getString());
			}
		});

		// Handle button clicks — requires mailbox screen to be open
		ServerPlayNetworking.registerGlobalReceiver(ButtonClickC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)
				&& !(player.currentScreenHandler instanceof justfatlard.village_mail.screen.PublicMailboxScreenHandler)) return;
			String messageIdStr = payload.messageId();
			String buttonId = payload.buttonId();

			try {
				UUID messageId = UUID.fromString(messageIdStr);
				PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
				Optional<MailMessage> messageOpt = storage.getMessage(player.getUuid(), messageId);

				if (messageOpt.isPresent()) {
					MailMessage message = messageOpt.get();

					// Find the button
					Optional<MessageButton> buttonOpt = message.getButtons().stream()
						.filter(b -> b.getId().equals(buttonId))
						.findFirst();

					if (buttonOpt.isPresent()) {
						MessageButton button = buttonOpt.get();

						// Handle built-in button types
						switch (button.getType()) {
							case REPLY, FORWARD -> {
								// Handled entirely client-side — no server action needed
							}
							case COLLECT_ITEMS -> {
								handleCollectItems(player, message, storage);
							}
							case ACCEPT, DECLINE, CUSTOM -> {
								// Delegate to registered handler
								if (button.getHandler() != null) {
									boolean handled = MailApi.handleButton(
										player.getEntityWorld().getServer(), player, message, button
									);
									if (handled) {
										// Handler may have deleted the message
										sendActionResult(player, true, "handled");
									} else {
										sendActionResult(player, false, Text.translatable("village-mail.feedback.no_handler").getString());
									}
								} else {
									sendActionResult(player, false,
										Text.translatable("village-mail.feedback.no_handler").getString());
								}
							}
						}
					} else {
						sendActionResult(player, false, Text.translatable("village-mail.error.button_not_found").getString());
					}
				} else {
					sendActionResult(player, false, Text.translatable("village-mail.error.message_not_found").getString());
				}
			} catch (IllegalArgumentException e) {
				sendActionResult(player, false, Text.translatable("village-mail.error.invalid_message").getString());
			}
		});

		// Handle collecting items — requires mailbox screen to be open
		ServerPlayNetworking.registerGlobalReceiver(CollectItemsC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)) return;
			String messageIdStr = payload.messageId();

			try {
				UUID messageId = UUID.fromString(messageIdStr);
				PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
				Optional<MailMessage> messageOpt = storage.getMessage(player.getUuid(), messageId);

				if (messageOpt.isPresent()) {
					handleCollectItems(player, messageOpt.get(), storage);
				} else {
					sendActionResult(player, false, Text.translatable("village-mail.error.message_not_found").getString());
				}
			} catch (IllegalArgumentException e) {
				sendActionResult(player, false, Text.translatable("village-mail.error.invalid_message").getString());
			}
		});

		// Handle sending a new message — requires a mail-related screen to be open
		ServerPlayNetworking.registerGlobalReceiver(SendMessageC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)
				&& !(player.currentScreenHandler instanceof justfatlard.village_mail.screen.PublicMailboxScreenHandler)) return;
			String recipientUuidStr = payload.recipientUuid();
			String body = payload.body();

			// Rate limit
			long now = System.currentTimeMillis();
			Long lastSend = lastSendTime.get(player.getUuid());
			if (lastSend != null && now - lastSend < SEND_COOLDOWN_MS) {
				sendActionResult(player, false, Text.translatable("village-mail.error.rate_limited").getString());
				return;
			}
			lastSendTime.put(player.getUuid(), now);

			try {
				UUID recipientUuid = UUID.fromString(recipientUuidStr);

				// Don't allow sending to yourself
				if (recipientUuid.equals(player.getUuid())) {
					sendActionResult(player, false, Text.translatable("village-mail.error.self_send").getString());
					return;
				}

				// Validate recipient is a known player (online or has a mailbox)
				PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
				boolean knownRecipient = storage.hasMailbox(recipientUuid)
					|| player.getEntityWorld().getServer().getPlayerManager().getPlayer(recipientUuid) != null;
				if (!knownRecipient) {
					sendActionResult(player, false, Text.translatable("village-mail.error.recipient_not_found").getString());
					return;
				}

				if (body.length() > MailMessage.MAX_BODY_LENGTH) {
					body = body.substring(0, MailMessage.MAX_BODY_LENGTH);
				}

				// Strip Minecraft formatting codes (§ followed by any character)
				body = body.replaceAll("\u00a7.", "");

				// Reject empty body after sanitization
				if (body.trim().isEmpty()) {
					sendActionResult(player, false, Text.translatable("village-mail.error.empty_message").getString());
					return;
				}

				// Attachments only come from the public mailbox — the personal mailbox is text-only compose.
			// This is the "Compose UI split" from VISION.md: you go to the post office to send a package.
				net.minecraft.item.ItemStack attachment = net.minecraft.item.ItemStack.EMPTY;
				if (player.currentScreenHandler instanceof justfatlard.village_mail.screen.PublicMailboxScreenHandler publicHandler) {
					attachment = publicHandler.takeAttachment();
				}

				// Build and send the message
				MailMessage.Builder builder = new MailMessage.Builder()
					.sender(player.getUuid(), player.getName().getString())
					.recipient(recipientUuid)
					.type(MailMessage.MessageType.PLAYER)
					.body(body)
					.button(MessageButton.reply())
					.button(MessageButton.forward());

				if (!attachment.isEmpty()) {
					builder.attachment(attachment);
					builder.button(MessageButton.collectItems());
				}

				MailMessage message = builder.build();
				MailApi.sendMessage(player.getEntityWorld().getServer(), message);

				sendActionResult(player, true,
					Text.translatable("village-mail.feedback.message_sent").getString());
				player.sendMessage(Text.translatable("village-mail.feedback.message_sent").formatted(Formatting.GREEN), true);

				// MailApi.sendMessage() already notifies the recipient via notifyPlayerIfOnline()
			} catch (IllegalArgumentException e) {
				sendActionResult(player, false, Text.translatable("village-mail.error.invalid_recipient").getString());
			}
		});

		// Handle message deletion — auto-collect uncollected items before deleting
		ServerPlayNetworking.registerGlobalReceiver(DeleteMessageC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)) return;
			String messageIdStr = payload.messageId();

			try {
				UUID messageId = UUID.fromString(messageIdStr);
				PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
				Optional<MailMessage> messageOpt = storage.getMessage(player.getUuid(), messageId);

				if (messageOpt.isPresent()) {
					MailMessage message = messageOpt.get();
					// Auto-collect uncollected items before deletion to prevent item loss
					if (message.hasUncollectedItems()) {
						handleCollectItems(player, message, storage);
					}
					storage.deleteMessage(player.getUuid(), messageId);
					sendMessageList(player, false);
					sendUnreadCount(player);
					sendActionResult(player, true, Text.translatable("village-mail.feedback.message_deleted").getString());
				} else {
					sendActionResult(player, false, Text.translatable("village-mail.error.message_not_found").getString());
				}
			} catch (IllegalArgumentException e) {
				sendActionResult(player, false, Text.translatable("village-mail.error.invalid_message").getString());
			}
		});

		// Handle refresh request — requires mailbox screen to be open
		ServerPlayNetworking.registerGlobalReceiver(RefreshMessagesC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)) return;
			if (isReadThrottled(player)) return;
			sendMessageList(player, payload.showRead());
		});

		// Handle recipient list request — requires a mail-related screen to be open
		ServerPlayNetworking.registerGlobalReceiver(RequestRecipientsC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.MessageListScreenHandler)
				&& !(player.currentScreenHandler instanceof justfatlard.village_mail.screen.PublicMailboxScreenHandler)) return;
			if (isReadThrottled(player)) return;
			sendRecipientsList(player);
		});

		// Handle village donation
		ServerPlayNetworking.registerGlobalReceiver(DonateC2S.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();

			if (!(player.currentScreenHandler instanceof justfatlard.village_mail.screen.PublicMailboxScreenHandler publicHandler)) {
				sendActionResult(player, false, Text.translatable("village-mail.error.no_mailbox_open").getString());
				return;
			}

			net.minecraft.item.ItemStack donationItem = publicHandler.takeAttachment();
			if (donationItem.isEmpty()) {
				sendActionResult(player, false, Text.translatable("village-mail.error.no_item").getString());
				return;
			}

			net.minecraft.util.math.BlockPos pos = publicHandler.getBlockPos();
			if (pos == null) {
				sendActionResult(player, false, Text.translatable("village-mail.error.no_mailbox_open").getString());
				return;
			}

			Main.MAIL_MANAGER.submitDonation(
				java.util.List.of(donationItem),
				player.getUuid(),
				player.getName().getString(),
				pos,
				(net.minecraft.server.world.ServerWorld) player.getEntityWorld()
			);

			sendActionResult(player, true,
				Text.translatable("village-mail.feedback.donation_submitted").getString());
			player.sendMessage(Text.translatable("village-mail.feedback.donation_submitted").formatted(Formatting.GREEN), true);

			// Schedule a delayed thank-you letter from the village
			var server = player.getEntityWorld().getServer();
			UUID donorUuid = player.getUuid();
			Main.MAIL_MANAGER.scheduleDelayed(server, 200 + java.util.concurrent.ThreadLocalRandom.current().nextInt(400), () -> {
				MailMessage thankYou = new MailMessage.Builder()
					.sender(null, "The Village")
					.recipient(donorUuid)
					.type(MailMessage.MessageType.VILLAGER)
					.body(Text.translatable("village-mail.delivery.gratitude").getString())
					.build();
				PlayerMailStorage.get(server).addMessage(donorUuid, thankYou);

				// Notify if player is still online
				ServerPlayerEntity onlineDonor = server.getPlayerManager().getPlayer(donorUuid);
				if (onlineDonor != null) {
					sendUnreadCount(onlineDonor);
				}
			});
		});
	}

	/**
	 * Send the message list to a player.
	 */
	public static void sendMessageList(ServerPlayerEntity player, boolean showRead) {
		PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());

		List<MailMessage> messages = showRead
			? storage.getReadMessages(player.getUuid())
			: storage.getUnreadMessages(player.getUuid());

		List<MessageSummary> summaries = messages.stream()
			.map(m -> new MessageSummary(
				m.getId().toString(),
				m.getSenderName() != null ? m.getSenderName() : "Unknown",
				m.getBodyPreview(),
				m.getTimestamp(),
				m.isRead(),
				m.hasAttachments(),
				m.getType().name()
			))
			.collect(Collectors.toList());

		int unreadCount = storage.getUnreadCount(player.getUuid());

		ServerPlayNetworking.send(player, new MessageListS2C(summaries, showRead, unreadCount));
	}

	/**
	 * Send full message details to a player.
	 */
	public static void sendMessageDetail(ServerPlayerEntity player, MailMessage message) {
		List<ButtonData> buttons = message.getButtons().stream()
			.map(b -> new ButtonData(b.getId(), b.getLabel(), b.getType().name()))
			.collect(Collectors.toList());

		ServerPlayNetworking.send(player, new MessageDetailS2C(
			message.getId().toString(),
			message.getSenderUuid() != null ? message.getSenderUuid().toString() : "",
			message.getSenderName() != null ? message.getSenderName() : "Unknown",
			message.getBody(),
			message.getTimestamp(),
			message.isRead(),
			message.isItemsCollected(),
			message.getType().name(),
			buttons,
			message.getAttachments().size()
		));
	}

	/**
	 * Send unread count update to a player.
	 */
	public static void sendUnreadCount(ServerPlayerEntity player) {
		PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
		int count = storage.getUnreadCount(player.getUuid());
		ServerPlayNetworking.send(player, new UnreadCountS2C(count));
	}

	/**
	 * Send action result to a player.
	 */
	public static void sendActionResult(ServerPlayerEntity player, boolean success, String message) {
		ServerPlayNetworking.send(player, new ActionResultS2C(success, message));
	}

	/**
	 * Send available recipients to a player — all players with mailboxes plus online players.
	 */
	public static void sendRecipientsList(ServerPlayerEntity player) {
		var server = player.getEntityWorld().getServer();
		PlayerMailStorage storage = PlayerMailStorage.get(server);

		// Collect all known recipients: mailbox owners + online players
		Map<UUID, RecipientInfo> recipients = new HashMap<>();

		// Add all players with mailboxes (may be offline)
		for (UUID ownerUuid : storage.getMailboxOwners()) {
			if (ownerUuid.equals(player.getUuid())) continue;

			String name = "Unknown";
			boolean online = false;

			// Try to find name from online players first
			ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(ownerUuid);
			if (onlinePlayer != null) {
				name = onlinePlayer.getName().getString();
				online = true;
			} else {
				// Look up cached name from mailbox location data or message history
				String cachedName = storage.getPlayerName(ownerUuid);
				if (cachedName != null) {
					name = cachedName;
				}
			}

			recipients.put(ownerUuid, new RecipientInfo(ownerUuid.toString(), name, online));
		}

		// Add online players who may not have mailboxes yet
		for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
			if (onlinePlayer.getUuid().equals(player.getUuid())) continue;
			recipients.putIfAbsent(onlinePlayer.getUuid(),
				new RecipientInfo(onlinePlayer.getUuid().toString(), onlinePlayer.getName().getString(), true));
		}

		ServerPlayNetworking.send(player, new RecipientsListS2C(new ArrayList<>(recipients.values())));
	}

	/**
	 * Handle collecting items from a message.
	 * Synchronized on the message to prevent duplication via concurrent packets.
	 */
	private static void handleCollectItems(ServerPlayerEntity player, MailMessage message, PlayerMailStorage storage) {
		synchronized (message) {
			if (message.isItemsCollected()) {
				sendActionResult(player, false, Text.translatable("village-mail.error.already_collected").getString());
				return;
			}

			if (!message.hasAttachments()) {
				sendActionResult(player, false, Text.translatable("village-mail.error.no_items").getString());
				return;
			}

			// Mark as collected FIRST to prevent concurrent collection
			message.setItemsCollected(true);
			storage.markItemsCollected(player.getUuid(), message.getId());
		}

		// Give items to player (outside synchronized block — no need to hold lock for inventory ops)
		for (var stack : message.getAttachments()) {
			if (!stack.isEmpty()) {
				if (!player.getInventory().insertStack(stack.copy())) {
					player.dropItem(stack.copy(), false);
				}
			}
		}

		MailApiImpl.fireItemsCollected(player.getUuid(), message);
		player.sendMessage(Text.translatable("village-mail.feedback.items_collected").formatted(Formatting.GREEN), true);
		sendActionResult(player, true,
			Text.translatable("village-mail.feedback.items_collected").getString());
	}
}
