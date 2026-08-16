package justfatlard.village_mail.pandorical;

import net.minecraft.util.Prediction;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.integration.VillageBuilderIntegration;
import justfatlard.village_mail.api.MailApi;
import justfatlard.village_mail.api.MailApiImpl;
import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.mail.MessageButton;
import justfatlard.village_mail.mail.PlayerMailStorage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds and drives all village-mail Pandorical screens (mailbox list, message detail,
 * compose, public mailbox) and registers their server-side action handlers.
 *
 * All screens are server-driven; there is no client-side screen code. Component IDs
 * for dynamic/per-message content (e.g. "msgbtn:{buttonId}") encode enough context for
 * onActionFallback handlers to recover state, mirroring village-quests' DialogueScreens.
 */
public final class MailScreens {
	private MailScreens() {}

	public static final String SCREEN_MAILBOX = "village-mail:mailbox";
	public static final String SCREEN_DETAIL = "village-mail:message_detail";
	public static final String SCREEN_COMPOSE = "village-mail:compose";
	public static final String SCREEN_PUBLIC_MAILBOX = "village-mail:public_mailbox";

	private static final int VISIBLE_MESSAGES = 5;
	private static final long SEND_COOLDOWN_MS = 1000;

	private static final DateTimeFormatter DATE_FORMAT =
		DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

	public enum ComposeMode { NEW, REPLY, FORWARD }

	private record RecipientEntry(UUID uuid, String name, boolean online) {}

	private record MailboxSession(String ownerName, boolean showingRead, int scrollOffset, List<MailMessage> visibleSlice) {}

	private static final class ComposeSession {
		ComposeMode mode;
		List<RecipientEntry> recipients;
		int selectedIndex;
		String body;
	}

	private static final class PublicMailboxSession {
		SimpleContainer container;
		BlockPos pos;
		List<RecipientEntry> recipients;
		int selectedIndex;
		String body;
		boolean consumed;
	}

	// --- Per-player state ---
	private static final Map<UUID, MailboxSession> mailboxSessions = new ConcurrentHashMap<>();
	private static final Map<UUID, MailMessage> detailMessage = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> detailParentShowingRead = new ConcurrentHashMap<>();
	private static final Map<UUID, ComposeSession> composeSessions = new ConcurrentHashMap<>();
	private static final Map<UUID, PublicMailboxSession> publicMailboxSessions = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> lastSendTime = new ConcurrentHashMap<>();

	// ========================================================================
	// Registration
	// ========================================================================

	public static void registerHandlers() {
		ScreenApi screens = PandoricalApi.screens();

		// --- Mailbox list ---
		screens.onAction(SCREEN_MAILBOX, "tab_new", (p, d) -> switchTab(p, false));
		screens.onAction(SCREEN_MAILBOX, "tab_read", (p, d) -> switchTab(p, true));
		screens.onAction(SCREEN_MAILBOX, "compose_btn", (p, d) -> openCompose(p, ComposeMode.NEW, null, null, null));
		screens.onAction(SCREEN_MAILBOX, "scroll_up", (p, d) -> scrollMailbox(p, -1));
		screens.onAction(SCREEN_MAILBOX, "scroll_down", (p, d) -> scrollMailbox(p, 1));
		for (int i = 0; i < VISIBLE_MESSAGES; i++) {
			final int slot = i;
			screens.onAction(SCREEN_MAILBOX, "msg_slot_" + i, (p, d) -> openSlot(p, slot));
		}
		screens.onClose(SCREEN_MAILBOX, p -> mailboxSessions.remove(p.getUUID()));

		// --- Message detail ---
		screens.onAction(SCREEN_DETAIL, "back", (p, d) -> backToMailbox(p));
		screens.onAction(SCREEN_DETAIL, "delete", (p, d) -> deleteCurrentMessage(p));
		screens.onActionFallback(SCREEN_DETAIL, (p, d) -> {
			String componentId = d.get(ScreenApi.FALLBACK_COMPONENT_ID_KEY);
			if (componentId != null && componentId.startsWith("msgbtn:")) {
				handleMessageButton(p, componentId.substring("msgbtn:".length()));
			}
		});
		screens.onClose(SCREEN_DETAIL, p -> {
			detailMessage.remove(p.getUUID());
			detailParentShowingRead.remove(p.getUUID());
		});

		// --- Compose ---
		screens.onAction(SCREEN_COMPOSE, "cancel_btn", (p, d) -> cancelCompose(p));
		screens.onAction(SCREEN_COMPOSE, "prev_btn", (p, d) -> navigateCompose(p, -1));
		screens.onAction(SCREEN_COMPOSE, "next_btn", (p, d) -> navigateCompose(p, 1));
		screens.onAction(SCREEN_COMPOSE, "body_input", (p, d) -> {
			ComposeSession session = composeSessions.get(p.getUUID());
			if (session != null) session.body = d.getOrDefault("text", "");
		});
		screens.onAction(SCREEN_COMPOSE, "send_btn", (p, d) -> sendComposeMessage(p));
		screens.onClose(SCREEN_COMPOSE, p -> composeSessions.remove(p.getUUID()));

		// --- Public mailbox ---
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "prev_btn", (p, d) -> navigatePublicMailbox(p, -1));
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "next_btn", (p, d) -> navigatePublicMailbox(p, 1));
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "body_input", (p, d) -> {
			PublicMailboxSession session = publicMailboxSessions.get(p.getUUID());
			if (session != null) session.body = d.getOrDefault("text", "");
		});
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "send_btn", (p, d) -> handlePublicSend(p));
		screens.onSlotChange(SCREEN_PUBLIC_MAILBOX, (p, slot, stack) -> updatePublicSendEnabled(p));
		screens.onContainerRemoved(SCREEN_PUBLIC_MAILBOX, MailScreens::returnUnconsumedAttachment);
	}

	public static void onPlayerDisconnect(UUID uuid) {
		mailboxSessions.remove(uuid);
		detailMessage.remove(uuid);
		detailParentShowingRead.remove(uuid);
		composeSessions.remove(uuid);
		publicMailboxSessions.remove(uuid);
		lastSendTime.remove(uuid);
	}

	// ========================================================================
	// Mailbox (personal) list
	// ========================================================================

	public static void openMailbox(ServerPlayer player, String ownerName) {
		if (!PandoricalApi.hasCapability(player, "screens")) {
			sendPandoricalRequiredMessage(player);
			return;
		}
		renderMailbox(player, ownerName, false, 0);
	}

	private static void renderMailbox(ServerPlayer player, String ownerName, boolean showingRead, int scrollOffset) {
		MinecraftServer server = player.level().getServer();
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		List<MailMessage> messages = showingRead
			? storage.getReadMessages(player.getUUID())
			: storage.getUnreadMessages(player.getUUID());
		int unreadCount = storage.getUnreadCount(player.getUUID());

		int maxScroll = Math.max(0, messages.size() - VISIBLE_MESSAGES);
		int clampedScroll = Math.max(0, Math.min(scrollOffset, maxScroll));
		int visibleEnd = Math.min(clampedScroll + VISIBLE_MESSAGES, messages.size());
		List<MailMessage> visibleSlice = new ArrayList<>(messages.subList(clampedScroll, visibleEnd));

		mailboxSessions.put(player.getUUID(), new MailboxSession(ownerName, showingRead, clampedScroll, visibleSlice));

		String title = Component.translatable("village-mail.screen.mailbox_title", ownerName).getString();

		ScreenBuilder b = new ScreenBuilder(SCREEN_MAILBOX)
			.size(220, 210)
			.title(title)
			.panel("bg", 0, 0, 220, 210, Map.of(ComponentType.PROP_BACKGROUND, "#CC1E1E1E", ComponentType.PROP_BORDER, "beveled"))
			.text("title", 10, 8, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_SHADOW, "true"));

		if (unreadCount > 0) {
			String unreadText = Component.translatable("village-mail.screen.unread_count", unreadCount).getString();
			b.text("unread_count", Math.max(10, 210 - unreadText.length() * 6), 8,
				Map.of(ComponentType.PROP_TEXT, unreadText, ComponentType.PROP_COLOR, "#CC0000"));
		}

		b.button("tab_new", 10, 24, 55, 16, Map.of(
			ComponentType.PROP_LABEL_KEY, "village-mail.screen.tab_new",
			ComponentType.PROP_ENABLED, String.valueOf(showingRead)));
		b.button("tab_read", 68, 24, 55, 16, Map.of(
			ComponentType.PROP_LABEL_KEY, "village-mail.screen.tab_read",
			ComponentType.PROP_ENABLED, String.valueOf(!showingRead)));
		b.button("compose_btn", 152, 188, 58, 16, Map.of(
			ComponentType.PROP_LABEL_KEY, "village-mail.screen.compose"));

		int rowY = 48;
		for (int i = 0; i < visibleSlice.size(); i++) {
			MailMessage msg = visibleSlice.get(i);
			String label = msg.getSenderName() != null ? msg.getSenderName() : "Unknown";
			if (label.length() > 16) label = label.substring(0, 13) + "...";
			if (msg.hasAttachments()) label = label + " ✉";
			b.button("msg_slot_" + i, 10, rowY, 178, 20, Map.of(ComponentType.PROP_LABEL, label));
			rowY += 22;
		}

		b.button("scroll_up", 192, 48, 16, 14, Map.of(
			ComponentType.PROP_LABEL, "^", ComponentType.PROP_ENABLED, String.valueOf(clampedScroll > 0)));
		b.button("scroll_down", 192, 48 + VISIBLE_MESSAGES * 22 - 14, 16, 14, Map.of(
			ComponentType.PROP_LABEL, "v", ComponentType.PROP_ENABLED, String.valueOf(clampedScroll < maxScroll)));

		if (messages.isEmpty()) {
			String emptyKey = showingRead ? "village-mail.screen.no_read_messages" : "village-mail.screen.no_new_messages";
			b.text("empty", 60, 90, Map.of(
				ComponentType.PROP_TEXT, Component.translatable(emptyKey).getString(),
				ComponentType.PROP_COLOR, "#808080"));
		}

		PandoricalApi.screens().open(player, b.build());
	}

	private static void switchTab(ServerPlayer player, boolean showingRead) {
		MailboxSession session = mailboxSessions.get(player.getUUID());
		String ownerName = session != null ? session.ownerName() : player.getName().getString();
		renderMailbox(player, ownerName, showingRead, 0);
	}

	private static void scrollMailbox(ServerPlayer player, int delta) {
		MailboxSession session = mailboxSessions.get(player.getUUID());
		if (session == null) return;
		renderMailbox(player, session.ownerName(), session.showingRead(), session.scrollOffset() + delta);
	}

	private static void openSlot(ServerPlayer player, int index) {
		MailboxSession session = mailboxSessions.get(player.getUUID());
		if (session == null || index >= session.visibleSlice().size()) return;
		MailMessage message = session.visibleSlice().get(index);

		if (!message.isRead()) {
			PlayerMailStorage storage = PlayerMailStorage.get(player.level().getServer());
			storage.markAsRead(player.getUUID(), message.getId());
			MailApiImpl.fireMessageRead(player.getUUID(), message);
			MailHud.updateUnreadCount(player);
		}

		detailParentShowingRead.put(player.getUUID(), session.showingRead());
		openMessageDetail(player, message);
	}

	private static void returnToMailbox(ServerPlayer player) {
		MailboxSession session = mailboxSessions.get(player.getUUID());
		if (session != null) {
			renderMailbox(player, session.ownerName(), session.showingRead(), session.scrollOffset());
		} else {
			openMailbox(player, player.getName().getString());
		}
	}

	// ========================================================================
	// Message detail
	// ========================================================================

	private static void openMessageDetail(ServerPlayer player, MailMessage message) {
		detailMessage.put(player.getUUID(), message);
		renderDetail(player, message);
	}

	private static void renderDetail(ServerPlayer player, MailMessage message) {
		ScreenBuilder b = new ScreenBuilder(SCREEN_DETAIL)
			.size(240, 210)
			.title(Component.translatable("village-mail.screen.message_title").getString())
			.panel("bg", 0, 0, 240, 210, Map.of(ComponentType.PROP_BACKGROUND, "#CCF5E6D3", ComponentType.PROP_BORDER, "beveled"))
			.button("back", 8, 6, 50, 16, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.back"))
			.button("delete", 182, 6, 50, 16, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.delete"));

		String senderName = message.getSenderName() != null ? message.getSenderName() : "Unknown";
		String senderLabel = Component.translatable("village-mail.screen.from", senderName).getString();
		b.text("sender_label", 12, 30, Map.of(ComponentType.PROP_TEXT, senderLabel, ComponentType.PROP_COLOR, "#3B2D1F"));
		b.text("time_label", 12, 42, Map.of(ComponentType.PROP_TEXT, formatTimestamp(message.getTimestamp()), ComponentType.PROP_COLOR, "#6B5B4F"));

		if (message.getType() != MailMessage.MessageType.PLAYER) {
			String badgeKey = switch (message.getType()) {
				case QUEST -> "village-mail.screen.badge_quest";
				case MOD -> "village-mail.screen.badge_mod";
				case SYSTEM, VILLAGER -> "village-mail.screen.badge_system";
				default -> null;
			};
			String badgeText = badgeKey != null
				? Component.translatable(badgeKey).getString()
				: "[" + message.getType() + "]";
			b.text("type_badge", 170, 30, Map.of(ComponentType.PROP_TEXT, badgeText, ComponentType.PROP_COLOR, "#8B7B6F"));
		}

		if (message.hasAttachments()) {
			String attachText = message.isItemsCollected()
				? Component.translatable("village-mail.screen.items_collected").getString()
				: Component.translatable("village-mail.screen.attachment_count", message.getAttachments().size()).getString();
			String attachColor = message.isItemsCollected() ? "#6B5B4F" : "#2D5A1F";
			b.text("attach_info", 12, 54, Map.of(ComponentType.PROP_TEXT, attachText, ComponentType.PROP_COLOR, attachColor));
		}

		b.text("body", 12, 70, Map.of(
			ComponentType.PROP_TEXT, message.getBody(),
			ComponentType.PROP_COLOR, "#3B2D1F",
			ComponentType.PROP_WRAP_WIDTH, "216",
			ComponentType.PROP_MAX_LINES, "10"
		));

		int buttonX = 8;
		int buttonY = 186;
		int buttonWidth = 70;
		for (MessageButton button : message.getButtons()) {
			if (button.getType() == MessageButton.ButtonType.COLLECT_ITEMS && message.isItemsCollected()) continue;
			if (button.getType() == MessageButton.ButtonType.REPLY && message.getSenderUuid() == null) continue;

			String label = button.getType() == MessageButton.ButtonType.CUSTOM
				? button.getLabel()
				: Component.translatable(button.getLabel()).getString();

			b.button("msgbtn:" + button.getId(), buttonX, buttonY, buttonWidth, 18, Map.of(ComponentType.PROP_LABEL, label));

			buttonX += buttonWidth + 4;
			if (buttonX + buttonWidth > 232) {
				buttonX = 8;
				buttonY -= 22;
			}
		}

		PandoricalApi.screens().open(player, b.build());
	}

	private static void backToMailbox(ServerPlayer player) {
		boolean showingRead = detailParentShowingRead.getOrDefault(player.getUUID(), false);
		detailMessage.remove(player.getUUID());
		detailParentShowingRead.remove(player.getUUID());
		MailboxSession session = mailboxSessions.get(player.getUUID());
		String ownerName = session != null ? session.ownerName() : player.getName().getString();
		int scrollOffset = session != null ? session.scrollOffset() : 0;
		renderMailbox(player, ownerName, showingRead, scrollOffset);
	}

	private static void deleteCurrentMessage(ServerPlayer player) {
		MailMessage message = detailMessage.get(player.getUUID());
		if (message == null) return;

		PlayerMailStorage storage = PlayerMailStorage.get(player.level().getServer());
		if (message.hasUncollectedItems()) {
			handleCollectItems(player, message, storage);
		}
		storage.deleteMessage(player.getUUID(), message.getId());
		MailHud.updateUnreadCount(player);
		player.sendSystemMessage(Component.translatable("village-mail.feedback.message_deleted").withStyle(ChatFormatting.GREEN), true);
		backToMailbox(player);
	}

	private static void handleMessageButton(ServerPlayer player, String buttonId) {
		MailMessage message = detailMessage.get(player.getUUID());
		if (message == null) return;

		Optional<MessageButton> buttonOpt = message.getButtons().stream()
			.filter(b -> b.getId().equals(buttonId))
			.findFirst();
		if (buttonOpt.isEmpty()) {
			sendError(player, "village-mail.error.button_not_found");
			return;
		}
		MessageButton button = buttonOpt.get();
		MinecraftServer server = player.level().getServer();

		switch (button.getType()) {
			case REPLY -> openComposeFromDetail(player, ComposeMode.REPLY, message);
			case FORWARD -> openComposeFromDetail(player, ComposeMode.FORWARD, message);
			case COLLECT_ITEMS -> {
				PlayerMailStorage storage = PlayerMailStorage.get(server);
				handleCollectItems(player, message, storage);
				MailHud.updateUnreadCount(player);
				renderDetail(player, message);
			}
			case ACCEPT, DECLINE, CUSTOM -> {
				boolean handled = MailApi.handleButton(server, player, message, button);
				if (!handled) {
					sendError(player, "village-mail.feedback.no_handler");
				}
			}
		}
	}

	private static void openComposeFromDetail(ServerPlayer player, ComposeMode mode, MailMessage message) {
		detailMessage.remove(player.getUUID());
		detailParentShowingRead.remove(player.getUUID());
		if (mode == ComposeMode.REPLY) {
			String recipientUuid = message.getSenderUuid() != null ? message.getSenderUuid().toString() : null;
			openCompose(player, mode, recipientUuid, message.getSenderName(), null);
		} else {
			openCompose(player, mode, null, null, message.getBody());
		}
	}

	private static String formatTimestamp(long timestamp) {
		long diff = System.currentTimeMillis() - timestamp;
		if (diff < 60_000) return Component.translatable("village-mail.screen.time_just_now").getString();
		if (diff < 3_600_000) return Component.translatable("village-mail.screen.time_minutes_ago", diff / 60_000).getString();
		if (diff < 86_400_000) return Component.translatable("village-mail.screen.time_hours_ago", diff / 3_600_000).getString();
		return DATE_FORMAT.format(Instant.ofEpochMilli(timestamp));
	}

	// ========================================================================
	// Compose
	// ========================================================================

	public static void openCompose(ServerPlayer player, ComposeMode mode,
			String prefillRecipientUuid, String prefillRecipientName, String prefillBody) {
		if (!PandoricalApi.hasCapability(player, "screens")) {
			sendPandoricalRequiredMessage(player);
			return;
		}

		List<RecipientEntry> recipients = mergePrefill(buildRecipients(player), prefillRecipientUuid, prefillRecipientName);

		ComposeSession session = new ComposeSession();
		session.mode = mode;
		session.recipients = recipients;
		session.selectedIndex = 0;
		session.body = "";
		if (mode == ComposeMode.FORWARD && prefillBody != null) {
			session.body = Component.translatable("village-mail.screen.forwarded_prefix").getString() + "\n" + prefillBody;
		}

		composeSessions.put(player.getUUID(), session);
		renderCompose(player);
	}

	private static void renderCompose(ServerPlayer player) {
		ComposeSession session = composeSessions.get(player.getUUID());
		if (session == null) return;

		String titleKey = switch (session.mode) {
			case REPLY -> "village-mail.screen.title_reply";
			case FORWARD -> "village-mail.screen.title_forward";
			case NEW -> "village-mail.screen.title_new_message";
		};
		String titleText = Component.translatable(titleKey).getString();

		RecipientEntry selected = session.recipients.isEmpty() ? null : session.recipients.get(session.selectedIndex);
		String recipientDisplay = selected == null
			? Component.translatable("village-mail.screen.no_players").getString()
			: displayName(selected);
		String countText = session.recipients.isEmpty() ? "" : (session.selectedIndex + 1) + "/" + session.recipients.size();

		ScreenBuilder b = new ScreenBuilder(SCREEN_COMPOSE)
			.size(240, 200)
			.title(titleText)
			.panel("bg", 0, 0, 240, 200, Map.of(ComponentType.PROP_BACKGROUND, "#CC1E1E1E", ComponentType.PROP_BORDER, "beveled"))
			.text("title", 90, 8, Map.of(ComponentType.PROP_TEXT, titleText, ComponentType.PROP_SHADOW, "true"))
			.button("cancel_btn", 8, 6, 50, 16, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.cancel"))
			.button("prev_btn", 8, 26, 20, 20, Map.of(
				ComponentType.PROP_LABEL, "<", ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex > 0)))
			.button("next_btn", 212, 26, 20, 20, Map.of(
				ComponentType.PROP_LABEL, ">", ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex < session.recipients.size() - 1)))
			.text("recipient_name", 90, 32, Map.of(ComponentType.PROP_TEXT, recipientDisplay))
			.text("recipient_count", 100, 48, Map.of(ComponentType.PROP_TEXT, countText, ComponentType.PROP_COLOR, "#808080"))
			.text("message_label", 10, 60, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.screen.message").getString()))
			.component(new ComponentBuilder("body_input", ComponentType.TEXT_INPUT)
				.bounds(10, 70, 220, 100)
				.prop(ComponentType.PROP_VALUE, session.body)
				.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(MailMessage.MAX_BODY_LENGTH))
				.prop(ComponentType.PROP_EDITABLE, "true"))
			.button("send_btn", 172, 176, 60, 18, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.send"));

		PandoricalApi.screens().open(player, b.build());
	}

	private static void navigateCompose(ServerPlayer player, int delta) {
		ComposeSession session = composeSessions.get(player.getUUID());
		if (session == null || session.recipients.isEmpty()) return;
		int newIndex = Math.max(0, Math.min(session.recipients.size() - 1, session.selectedIndex + delta));
		if (newIndex == session.selectedIndex) return;
		session.selectedIndex = newIndex;

		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId == null) return;

		RecipientEntry selected = session.recipients.get(session.selectedIndex);
		String countText = (session.selectedIndex + 1) + "/" + session.recipients.size();

		PandoricalApi.screens().update(player, screenId, List.of(
			new ComponentUpdate("recipient_name", Map.of(ComponentType.PROP_TEXT, displayName(selected))),
			new ComponentUpdate("recipient_count", Map.of(ComponentType.PROP_TEXT, countText)),
			new ComponentUpdate("prev_btn", Map.of(ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex > 0))),
			new ComponentUpdate("next_btn", Map.of(ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex < session.recipients.size() - 1)))
		));
	}

	private static void cancelCompose(ServerPlayer player) {
		composeSessions.remove(player.getUUID());
		returnToMailbox(player);
	}

	private static void sendComposeMessage(ServerPlayer player) {
		ComposeSession session = composeSessions.get(player.getUUID());
		if (session == null || session.recipients.isEmpty()) return;
		RecipientEntry recipient = session.recipients.get(session.selectedIndex);
		boolean sent = trySendMail(player, recipient.uuid(), session.body, ItemStack.EMPTY);
		if (sent) {
			composeSessions.remove(player.getUUID());
			returnToMailbox(player);
		}
	}

	// ========================================================================
	// Public mailbox
	// ========================================================================

	public static void openPublicMailbox(ServerPlayer player, BlockPos pos) {
		if (!PandoricalApi.hasCapability(player, "screens")) {
			sendPandoricalRequiredMessage(player);
			return;
		}

		PublicMailboxSession session = new PublicMailboxSession();
		session.container = new SimpleContainer(1);
		session.pos = pos;
		session.recipients = buildRecipients(player);
		if (VillageBuilderIntegration.isAvailable()) {
			// First in the carousel per the owner's call: donation is the
			// mailbox's headline civic act, person-to-person mail cycles after
			session.recipients.add(0, donationEntry());
		}
		session.selectedIndex = 0;
		session.body = "";
		session.consumed = false;
		publicMailboxSessions.put(player.getUUID(), session);

		String title = Component.translatable("block.village-mail.public_mailbox").getString();
		RecipientEntry selected = session.recipients.isEmpty() ? null : session.recipients.get(0);
		String recipientDisplay = selected == null
			? Component.translatable("village-mail.screen.no_players").getString()
			: displayName(selected);

		ScreenBuilder b = new ScreenBuilder(SCREEN_PUBLIC_MAILBOX)
			.size(200, 200)
			.title(title)
			.container(1, true)
			.panel("bg", 0, 0, 200, 200, Map.of(ComponentType.PROP_BACKGROUND, "#CC1E1E1E", ComponentType.PROP_BORDER, "beveled"))
			.text("title", 10, 6, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_SHADOW, "true"))
			.button("prev_btn", 7, 20, 16, 18, Map.of(ComponentType.PROP_LABEL, "<", ComponentType.PROP_ENABLED, "false"))
			.button("next_btn", 177, 20, 16, 18, Map.of(
				ComponentType.PROP_LABEL, ">", ComponentType.PROP_ENABLED, String.valueOf(session.recipients.size() > 1)))
			.text("recipient_name", 60, 24, Map.of(ComponentType.PROP_TEXT, recipientDisplay))
			.component(new ComponentBuilder("body_input", ComponentType.TEXT_INPUT)
				.bounds(8, 42, 184, 14)
				.prop(ComponentType.PROP_VALUE, "")
				.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(MailMessage.MAX_BODY_LENGTH))
				.prop(ComponentType.PROP_PLACEHOLDER_KEY, "village-mail.screen.message"))
			.text("attach_label", 8, 62, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.screen.attach").getString(),
				ComponentType.PROP_COLOR, "#606060"))
			.inventoryGrid("attachment_slot", 91, 60, 1, 1, 0)
			.button("send_btn", 132, 82, 60, 16, Map.of(
				ComponentType.PROP_LABEL_KEY, "village-mail.screen.send",
				ComponentType.PROP_ENABLED, String.valueOf(publicSendEnabled(session))))
			.inventoryGrid("player_inv", 8, 108, 3, 9, 1)
			.inventoryGrid("hotbar", 8, 166, 1, 9, 28);

		PandoricalApi.screens().openContainer(player, b.build(), session.container, Set.of());
	}

	private static void navigatePublicMailbox(ServerPlayer player, int delta) {
		PublicMailboxSession session = publicMailboxSessions.get(player.getUUID());
		if (session == null || session.recipients.isEmpty()) return;
		int newIndex = Math.max(0, Math.min(session.recipients.size() - 1, session.selectedIndex + delta));
		if (newIndex == session.selectedIndex) return;
		session.selectedIndex = newIndex;

		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId == null) return;

		RecipientEntry selected = session.recipients.get(session.selectedIndex);
		PandoricalApi.screens().update(player, screenId, List.of(
			new ComponentUpdate("recipient_name", Map.of(ComponentType.PROP_TEXT, displayName(selected))),
			new ComponentUpdate("prev_btn", Map.of(ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex > 0))),
			new ComponentUpdate("next_btn", Map.of(ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex < session.recipients.size() - 1))),
			new ComponentUpdate("send_btn", Map.of(ComponentType.PROP_ENABLED, String.valueOf(publicSendEnabled(session))))
		));
	}

	// The village-donation pseudo-recipient is marked by a null uuid; buildRecipients
	// never emits null uuids, so this cannot collide with a real recipient.
	private static RecipientEntry donationEntry() {
		return new RecipientEntry(null,
			Component.translatable("village-mail.screen.recipient_donation").getString(), true);
	}

	private static boolean isDonation(RecipientEntry entry) {
		return entry.uuid() == null;
	}

	private static boolean publicSendEnabled(PublicMailboxSession session) {
		if (session.recipients.isEmpty()) return false;
		RecipientEntry selected = session.recipients.get(session.selectedIndex);
		return !isDonation(selected) || !session.container.getItem(0).isEmpty();
	}

	private static void updatePublicSendEnabled(ServerPlayer player) {
		PublicMailboxSession session = publicMailboxSessions.get(player.getUUID());
		if (session == null) return;
		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId == null) return;
		PandoricalApi.screens().update(player, screenId, List.of(
			new ComponentUpdate("send_btn", Map.of(ComponentType.PROP_ENABLED, String.valueOf(publicSendEnabled(session))))
		));
	}

	private static void handleDonate(ServerPlayer player) {
		PublicMailboxSession session = publicMailboxSessions.get(player.getUUID());
		if (session == null) return;

		ItemStack donation = session.container.getItem(0);
		if (donation.isEmpty()) {
			sendError(player, "village-mail.error.no_item");
			return;
		}
		ItemStack taken = donation.copy();
		session.container.setItem(0, ItemStack.EMPTY);
		session.consumed = true;

		Main.MAIL_MANAGER.submitDonation(List.of(taken), player.getUUID(), player.getName().getString(),
			session.pos, (ServerLevel) player.level());

		player.sendSystemMessage(Component.translatable("village-mail.feedback.donation_submitted").withStyle(ChatFormatting.GREEN), true);

		MinecraftServer server = player.level().getServer();
		UUID donorUuid = player.getUUID();
		Main.MAIL_MANAGER.scheduleDelayed(server, 200 + ThreadLocalRandom.current().nextInt(400), () -> {
			MailMessage thankYou = new MailMessage.Builder()
				.sender(null, "The Village")
				.recipient(donorUuid)
				.type(MailMessage.MessageType.VILLAGER)
				.body(Component.translatable("village-mail.delivery.gratitude").getString())
				.build();
			PlayerMailStorage.get(server).addMessage(donorUuid, thankYou);

			ServerPlayer onlineDonor = server.getPlayerList().getPlayer(donorUuid);
			if (onlineDonor != null) {
				MailHud.updateUnreadCount(onlineDonor);
			}
		});

		closePublicMailbox(player);
	}

	private static void handlePublicSend(ServerPlayer player) {
		PublicMailboxSession session = publicMailboxSessions.get(player.getUUID());
		if (session == null || session.recipients.isEmpty()) {
			sendError(player, "village-mail.error.invalid_recipient");
			return;
		}
		RecipientEntry recipient = session.recipients.get(session.selectedIndex);
		if (isDonation(recipient)) {
			// handleDonate re-checks for an empty attachment server-side; the greyed
			// send button is client-side state and cannot be trusted as validation
			handleDonate(player);
			return;
		}
		ItemStack attachment = session.container.getItem(0).copy();
		boolean sent = trySendMail(player, recipient.uuid(), session.body, attachment);
		if (sent) {
			session.container.setItem(0, ItemStack.EMPTY);
			session.consumed = true;
			closePublicMailbox(player);
		}
	}

	private static void closePublicMailbox(ServerPlayer player) {
		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId != null) PandoricalApi.screens().close(player, screenId);
	}

	private static void returnUnconsumedAttachment(ServerPlayer player) {
		PublicMailboxSession session = publicMailboxSessions.remove(player.getUUID());
		if (session == null || session.consumed) return;
		ItemStack attachment = session.container.getItem(0);
		if (!attachment.isEmpty()) {
			if (!player.getInventory().add(attachment.copy())) {
				player.drop(attachment.copy(), false, Prediction.SERVER_ONLY);
			}
		}
	}

	// ========================================================================
	// Shared helpers
	// ========================================================================

	private static List<RecipientEntry> buildRecipients(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		PlayerMailStorage storage = PlayerMailStorage.get(server);
		Map<UUID, RecipientEntry> recipients = new LinkedHashMap<>();

		for (UUID ownerUuid : storage.getMailboxOwners()) {
			if (ownerUuid.equals(player.getUUID())) continue;
			String name = "Unknown";
			boolean online = false;
			ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(ownerUuid);
			if (onlinePlayer != null) {
				name = onlinePlayer.getName().getString();
				online = true;
			} else {
				String cachedName = storage.getPlayerName(ownerUuid);
				if (cachedName != null) name = cachedName;
			}
			recipients.put(ownerUuid, new RecipientEntry(ownerUuid, name, online));
		}

		for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
			if (onlinePlayer.getUUID().equals(player.getUUID())) continue;
			recipients.putIfAbsent(onlinePlayer.getUUID(),
				new RecipientEntry(onlinePlayer.getUUID(), onlinePlayer.getName().getString(), true));
		}

		// Mail is asynchronous: anyone who has EVER played is addressable, not
		// just current mailbox owners and online players. The playerdata dir is
		// the canonical roster; names resolve from the local usercache. Entries
		// the cache cannot name (stale files, cleared cache) are skipped rather
		// than shown as "Unknown".
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(
				server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR))) {
			files.filter(p -> p.getFileName().toString().endsWith(".dat")).forEach(p -> {
				String base = p.getFileName().toString();
				UUID uuid;
				try {
					uuid = UUID.fromString(base.substring(0, base.length() - 4));
				} catch (IllegalArgumentException e) {
					return;
				}
				if (uuid.equals(player.getUUID()) || recipients.containsKey(uuid)) return;
				server.services().nameToIdCache().get(uuid).ifPresent(nameAndId ->
					recipients.put(uuid, new RecipientEntry(uuid, nameAndId.name(), false)));
			});
		} catch (java.io.IOException e) {
			// Roster stays owners+online on IO trouble; mail still works
		}

		return new ArrayList<>(recipients.values());
	}

	private static List<RecipientEntry> mergePrefill(List<RecipientEntry> base, String prefillUuid, String prefillName) {
		if (prefillUuid == null || prefillName == null) return base;
		UUID uuid;
		try {
			uuid = UUID.fromString(prefillUuid);
		} catch (IllegalArgumentException e) {
			return base;
		}
		List<RecipientEntry> merged = new ArrayList<>();
		merged.add(new RecipientEntry(uuid, prefillName, true));
		for (RecipientEntry entry : base) {
			if (!entry.uuid().equals(uuid)) merged.add(entry);
		}
		return merged;
	}

	private static String displayName(RecipientEntry entry) {
		return entry.online()
			? entry.name()
			: entry.name() + " " + Component.translatable("village-mail.screen.offline").getString();
	}

	/**
	 * Validates and sends a text message with an optional item attachment.
	 * Shared by the compose screen and the public mailbox send button.
	 */
	private static boolean trySendMail(ServerPlayer sender, UUID recipientUuid, String rawBody, ItemStack attachment) {
		MinecraftServer server = sender.level().getServer();

		long now = System.currentTimeMillis();
		Long lastSend = lastSendTime.get(sender.getUUID());
		if (lastSend != null && now - lastSend < SEND_COOLDOWN_MS) {
			sendError(sender, "village-mail.error.rate_limited");
			return false;
		}
		lastSendTime.put(sender.getUUID(), now);

		if (recipientUuid == null) {
			sendError(sender, "village-mail.error.invalid_recipient");
			return false;
		}
		if (recipientUuid.equals(sender.getUUID())) {
			sendError(sender, "village-mail.error.self_send");
			return false;
		}

		PlayerMailStorage storage = PlayerMailStorage.get(server);
		boolean knownRecipient = storage.hasMailbox(recipientUuid) || server.getPlayerList().getPlayer(recipientUuid) != null;
		if (!knownRecipient) {
			sendError(sender, "village-mail.error.recipient_not_found");
			return false;
		}

		String body = rawBody == null ? "" : rawBody;
		if (body.length() > MailMessage.MAX_BODY_LENGTH) {
			body = body.substring(0, MailMessage.MAX_BODY_LENGTH);
		}
		body = body.replaceAll("§.", "");
		body = body.trim();
		if (body.isEmpty()) {
			sendError(sender, "village-mail.error.empty_message");
			return false;
		}

		MailMessage.Builder builder = new MailMessage.Builder()
			.sender(sender.getUUID(), sender.getName().getString())
			.recipient(recipientUuid)
			.type(MailMessage.MessageType.PLAYER)
			.body(body)
			.button(MessageButton.reply())
			.button(MessageButton.forward());

		if (attachment != null && !attachment.isEmpty()) {
			builder.attachment(attachment);
			builder.button(MessageButton.collectItems());
		}

		MailMessage message = builder.build();
		MailApi.sendMessage(server, message);

		sender.sendSystemMessage(Component.translatable("village-mail.feedback.message_sent").withStyle(ChatFormatting.GREEN), true);
		return true;
	}

	private static void handleCollectItems(ServerPlayer player, MailMessage message, PlayerMailStorage storage) {
		synchronized (message) {
			if (message.isItemsCollected()) {
				sendError(player, "village-mail.error.already_collected");
				return;
			}
			if (!message.hasAttachments()) {
				sendError(player, "village-mail.error.no_items");
				return;
			}
			message.setItemsCollected(true);
			storage.markItemsCollected(player.getUUID(), message.getId());
		}

		for (ItemStack stack : message.getAttachments()) {
			if (!stack.isEmpty()) {
				if (!player.getInventory().add(stack.copy())) {
					player.drop(stack.copy(), false, Prediction.SERVER_ONLY);
				}
			}
		}

		MailApiImpl.fireItemsCollected(player.getUUID(), message);
		player.sendSystemMessage(Component.translatable("village-mail.feedback.items_collected").withStyle(ChatFormatting.GREEN), true);
	}

	private static void sendError(ServerPlayer player, String translationKey) {
		player.sendSystemMessage(Component.translatable(translationKey).withStyle(ChatFormatting.RED));
	}

	private static void sendPandoricalRequiredMessage(ServerPlayer player) {
		player.sendSystemMessage(Component.translatable("village-mail.error.pandorical_required").withStyle(ChatFormatting.RED));
	}
}
