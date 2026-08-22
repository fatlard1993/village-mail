package justfatlard.village_mail.pandorical;

import net.minecraft.util.Prediction;
import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.ScreenApi;
import justfatlard.pandorical.api.ScreenBuilder;
import justfatlard.pandorical.protocol.ComponentUpdate;
import justfatlard.pandorical.protocol.OpenScreenS2C;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.integration.VillageBuilderIntegration;
import justfatlard.village_mail.api.MailApi;
import justfatlard.village_mail.api.MailApiImpl;
import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.mail.MessageButton;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.mail.VillageBulletin;

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
	public static final String SCREEN_BULLETIN = "village-mail:bulletin";

	/** Notices a board shows at once. More than this and it stops being scannable. */
	private static final int BULLETIN_MAX_NOTICES = 6;

	private static final int VISIBLE_MESSAGES = 5;
	private static final long SEND_COOLDOWN_MS = 1000;

	// Vanilla container metrics. A mailbox is a thing you open next to a chest, so it
	// is built on the same grid: 176 wide, an 8px margin, labels in #404040 ink on the
	// default panel - Pandorical's panel with no props is exactly the vanilla one, and
	// its slot rendering assumes that light background. The dark translucent panels
	// this used to carry read as an overlay laid over the game rather than part of it.
	private static final int SCREEN_W = 176;
	private static final int MARGIN = 8;
	private static final int CONTENT_W = SCREEN_W - MARGIN * 2;
	private static final int RIGHT = SCREEN_W - MARGIN;
	private static final int TITLE_Y = 6;
	/** Vanilla's own label colour, used for titles and anything read as a label. */
	private static final String INK = "#404040";
	private static final String INK_BODY = "#555555";
	private static final String INK_MUTED = "#7F7F7F";
	private static final String INK_GOOD = "#2D5A1F";
	/** Vanilla screen furniture: no props at all is the inventory's own panel. */
	private static final Map<String, String> PANEL = Map.of();

	// Message list geometry: five rows between the tab strip and the compose button.
	private static final int MAILBOX_H = 184;
	private static final int ROW_TOP = 42;
	private static final int ROW_PITCH = 22;
	private static final int ROW_W = CONTENT_W - 20;
	private static final int DETAIL_H = 184;
	private static final int BULLETIN_H = 178;
	// The player's inventory keeps vanilla's own spacing: 3x9 grid, hotbar 58 below it,
	// 8px of panel under that. Compose and the public mailbox are the same kind of
	// screen - address, message, attachment, inventory - so they are the same height.
	private static final int PLAYER_INV_Y = 96;
	private static final int CONTAINER_H = PLAYER_INV_Y + 58 + 18 + MARGIN;
	private static final int COMPOSE_H = CONTAINER_H;
	private static final int PUBLIC_H = CONTAINER_H;

	/** Right-aligned x for a line of text, on the 6px-per-character grid the font uses. */
	private static int rightAlign(String text) {
		return Math.max(MARGIN, RIGHT - text.length() * 6);
	}

	private static final DateTimeFormatter DATE_FORMAT =
		DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

	public enum ComposeMode { NEW, REPLY, FORWARD }

	private record RecipientEntry(UUID uuid, String name, boolean online) {}

	private record MailboxSession(String ownerName, boolean showingRead, int scrollOffset, List<MailMessage> visibleSlice) {}

	private static final class ComposeSession {
		ComposeMode mode;
		SimpleContainer container;
		List<RecipientEntry> recipients;
		int selectedIndex;
		String body;
		boolean consumed;
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
		// Teardown belongs to the container callback alone, not to onClose as well:
		// it is the one that runs on every way out (escape, disconnect, another
		// screen taking over) and the only one that can still reach the attachment.
		screens.onContainerRemoved(SCREEN_COMPOSE, MailScreens::returnUnconsumedCompose);

		// --- Public mailbox ---
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "prev_btn", (p, d) -> navigatePublicMailbox(p, -1));
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "next_btn", (p, d) -> navigatePublicMailbox(p, 1));
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "body_input", (p, d) -> {
			PublicMailboxSession session = publicMailboxSessions.get(p.getUUID());
			if (session != null) session.body = d.getOrDefault("text", "");
		});
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "send_btn", (p, d) -> handlePublicSend(p));
		screens.onAction(SCREEN_PUBLIC_MAILBOX, "bulletin_btn", (p, d) -> openBulletin(p));
		// Back re-opens the mailbox rather than closing: the board is a page of the
		// mailbox, not a separate errand.
		screens.onAction(SCREEN_BULLETIN, "bulletin_back", (p, d) -> {
			PublicMailboxSession session = publicMailboxSessions.get(p.getUUID());
			if (session != null) openPublicMailbox(p, session.pos);
		});
		screens.onSlotChange(SCREEN_PUBLIC_MAILBOX, (p, slot, stack) -> updatePublicSendEnabled(p));
		screens.onContainerRemoved(SCREEN_PUBLIC_MAILBOX, MailScreens::returnUnconsumedAttachment);
	}

	/**
	 * Drop the state that is only a view, and leave the two sessions that hold an item
	 * alone: quitting closes the player's container, and that teardown is what puts an
	 * unsent attachment back in their inventory before it is saved. Clearing those here
	 * would race it, and winning the race destroys the item. Whichever entry the
	 * teardown does not reach is replaced the next time the player opens a mailbox.
	 */
	public static void onPlayerDisconnect(UUID uuid) {
		mailboxSessions.remove(uuid);
		detailMessage.remove(uuid);
		detailParentShowingRead.remove(uuid);
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
			.size(SCREEN_W, MAILBOX_H)
			.title(title)
			.panel("bg", 0, 0, SCREEN_W, MAILBOX_H, PANEL)
			.text("title", MARGIN, TITLE_Y, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_COLOR, INK));

		// The count rides on the tab it counts rather than competing with the title
		// for the top row, where a long owner name used to run into it.
		String newTabLabel = Component.translatable("village-mail.screen.tab_new").getString();
		if (unreadCount > 0) newTabLabel = newTabLabel + " (" + unreadCount + ")";

		int tabWidth = (CONTENT_W - 4) / 2;
		b.button("tab_new", MARGIN, 20, tabWidth, 16, Map.of(
			ComponentType.PROP_LABEL, newTabLabel,
			ComponentType.PROP_ENABLED, String.valueOf(showingRead)));
		b.button("tab_read", MARGIN + tabWidth + 4, 20, tabWidth, 16, Map.of(
			ComponentType.PROP_LABEL_KEY, "village-mail.screen.tab_read",
			ComponentType.PROP_ENABLED, String.valueOf(!showingRead)));
		b.button("compose_btn", RIGHT - 76, MAILBOX_H - 26, 76, 18, Map.of(
			ComponentType.PROP_LABEL_KEY, "village-mail.screen.compose"));

		int rowY = ROW_TOP;
		for (int i = 0; i < visibleSlice.size(); i++) {
			MailMessage msg = visibleSlice.get(i);
			String label = msg.getSenderName() != null ? msg.getSenderName() : "Unknown";
			if (label.length() > 16) label = label.substring(0, 13) + "...";
			if (msg.hasAttachments()) label = label + " ✉";
			b.button("msg_slot_" + i, MARGIN, rowY, ROW_W, 20, Map.of(ComponentType.PROP_LABEL, label));
			rowY += ROW_PITCH;
		}

		int scrollX = MARGIN + ROW_W + 4;
		b.button("scroll_up", scrollX, ROW_TOP, 16, 14, Map.of(
			ComponentType.PROP_LABEL, "^", ComponentType.PROP_ENABLED, String.valueOf(clampedScroll > 0)));
		b.button("scroll_down", scrollX, ROW_TOP + VISIBLE_MESSAGES * ROW_PITCH - 16, 16, 14, Map.of(
			ComponentType.PROP_LABEL, "v", ComponentType.PROP_ENABLED, String.valueOf(clampedScroll < maxScroll)));

		if (messages.isEmpty()) {
			String emptyKey = showingRead ? "village-mail.screen.no_read_messages" : "village-mail.screen.no_new_messages";
			String emptyText = Component.translatable(emptyKey).getString();
			b.text("empty", Math.max(MARGIN, (SCREEN_W - emptyText.length() * 6) / 2), 80, Map.of(
				ComponentType.PROP_TEXT, emptyText,
				ComponentType.PROP_COLOR, INK_MUTED));
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
			.size(SCREEN_W, DETAIL_H)
			.title(Component.translatable("village-mail.screen.message_title").getString())
			.panel("bg", 0, 0, SCREEN_W, DETAIL_H, PANEL)
			.button("back", MARGIN, TITLE_Y - 2, 46, 16, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.back"))
			.button("delete", RIGHT - 46, TITLE_Y - 2, 46, 16, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.delete"));

		String senderName = message.getSenderName() != null ? message.getSenderName() : "Unknown";
		String senderLabel = Component.translatable("village-mail.screen.from", senderName).getString();
		b.text("sender_label", MARGIN, 28, Map.of(ComponentType.PROP_TEXT, senderLabel, ComponentType.PROP_COLOR, INK));
		b.text("time_label", MARGIN, 40, Map.of(ComponentType.PROP_TEXT, formatTimestamp(message.getTimestamp()), ComponentType.PROP_COLOR, INK_MUTED));

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
			b.text("type_badge", rightAlign(badgeText), 28, Map.of(ComponentType.PROP_TEXT, badgeText, ComponentType.PROP_COLOR, INK_MUTED));
		}

		if (message.hasAttachments()) {
			String attachText = message.isItemsCollected()
				? Component.translatable("village-mail.screen.items_collected").getString()
				: Component.translatable("village-mail.screen.attachment_count", message.getAttachments().size()).getString();
			String attachColor = message.isItemsCollected() ? INK_MUTED : INK_GOOD;
			b.text("attach_info", MARGIN, 52, Map.of(ComponentType.PROP_TEXT, attachText, ComponentType.PROP_COLOR, attachColor));
		}

		b.text("body", MARGIN, 66, Map.of(
			ComponentType.PROP_TEXT, message.getBody(),
			ComponentType.PROP_COLOR, INK_BODY,
			ComponentType.PROP_WRAP_WIDTH, String.valueOf(CONTENT_W),
			ComponentType.PROP_MAX_LINES, "7"
		));

		int buttonX = MARGIN;
		int buttonY = DETAIL_H - 24;
		int buttonWidth = 76;
		for (MessageButton button : message.getButtons()) {
			if (button.getType() == MessageButton.ButtonType.COLLECT_ITEMS && message.isItemsCollected()) continue;
			if (button.getType() == MessageButton.ButtonType.REPLY && message.getSenderUuid() == null) continue;

			String label = button.getType() == MessageButton.ButtonType.CUSTOM
				? button.getLabel()
				: Component.translatable(button.getLabel()).getString();

			b.button("msgbtn:" + button.getId(), buttonX, buttonY, buttonWidth, 18, Map.of(ComponentType.PROP_LABEL, label));

			buttonX += buttonWidth + 4;
			if (buttonX + buttonWidth > RIGHT) {
				buttonX = MARGIN;
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
		session.container = new SimpleContainer(1);
		session.recipients = recipients;
		session.selectedIndex = 0;
		session.body = "";
		session.consumed = false;
		if (mode == ComposeMode.FORWARD && prefillBody != null) {
			session.body = Component.translatable("village-mail.screen.forwarded_prefix").getString() + "\n" + prefillBody;
		}

		// Open first, register second. openContainer tears down whatever menu the
		// player had, and that teardown looks this player's session up by UUID: with
		// the new session already in the map, the outgoing screen's handler cleans up
		// the one just built and leaves a live screen with no state behind it.
		PandoricalApi.screens().openContainer(player, buildComposeScreen(session), session.container, Set.of());
		composeSessions.put(player.getUUID(), session);
	}

	private static OpenScreenS2C buildComposeScreen(ComposeSession session) {
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

		// Same shape as the public mailbox: address, message, an attachment slot, and
		// the player's own inventory to drag from. The text field is a vanilla EditBox
		// and holds one line however tall it is drawn, so it is drawn one line tall.
		return new ScreenBuilder(SCREEN_COMPOSE)
			.size(SCREEN_W, COMPOSE_H)
			.title(titleText)
			.container(1, true)
			.panel("bg", 0, 0, SCREEN_W, COMPOSE_H, PANEL)
			.text("title", MARGIN, TITLE_Y, Map.of(ComponentType.PROP_TEXT, titleText, ComponentType.PROP_COLOR, INK))
			.button("prev_btn", MARGIN, 22, 16, 18, Map.of(
				ComponentType.PROP_LABEL, "<", ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex > 0)))
			.button("next_btn", RIGHT - 16, 22, 16, 18, Map.of(
				ComponentType.PROP_LABEL, ">", ComponentType.PROP_ENABLED, String.valueOf(session.selectedIndex < session.recipients.size() - 1)))
			.text("recipient_name", 30, 24, Map.of(ComponentType.PROP_TEXT, recipientDisplay, ComponentType.PROP_COLOR, INK))
			.text("recipient_count", 30, 34, Map.of(ComponentType.PROP_TEXT, countText, ComponentType.PROP_COLOR, INK_MUTED))
			.component(new ComponentBuilder("body_input", ComponentType.TEXT_INPUT)
				.bounds(MARGIN, 46, CONTENT_W, 14)
				.prop(ComponentType.PROP_VALUE, session.body)
				.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(MailMessage.MAX_BODY_LENGTH))
				.prop(ComponentType.PROP_PLACEHOLDER_KEY, "village-mail.screen.message")
				.prop(ComponentType.PROP_EDITABLE, "true"))
			.text("attach_label", MARGIN, 68, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.screen.attach").getString(),
				ComponentType.PROP_COLOR, INK))
			.inventoryGrid("attachment_slot", 50, 64, 1, 1, 0)
			.button("cancel_btn", 76, 64, 44, 18, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.cancel"))
			.button("send_btn", RIGHT - 52, 64, 52, 18, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.send"))
			.text("inventory_label", MARGIN, PLAYER_INV_Y - 10, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("container.inventory").getString(),
				ComponentType.PROP_COLOR, INK))
			.inventoryGrid("player_inv", MARGIN, PLAYER_INV_Y, 3, 9, 1)
			.inventoryGrid("hotbar", MARGIN, PLAYER_INV_Y + 58, 1, 9, 28)
			.build();
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
		closeCompose(player);
		returnToMailbox(player);
	}

	private static void sendComposeMessage(ServerPlayer player) {
		ComposeSession session = composeSessions.get(player.getUUID());
		if (session == null || session.recipients.isEmpty()) return;
		RecipientEntry recipient = session.recipients.get(session.selectedIndex);
		ItemStack attachment = session.container.getItem(0).copy();
		boolean sent = trySendMail(player, recipient.uuid(), session.body, attachment);
		if (sent) {
			session.container.setItem(0, ItemStack.EMPTY);
			session.consumed = true;
			closeCompose(player);
			returnToMailbox(player);
		}
	}

	/**
	 * Hand the compose menu back before opening the list over it.
	 *
	 * <p>The mailbox list is a plain screen, and opening one does not close a container
	 * menu, so walking away from compose without this leaves the menu live on the server
	 * with the attachment stranded inside it. Closing runs the removed-handler, which is
	 * what returns the item and clears the session.
	 */
	private static void closeCompose(ServerPlayer player) {
		String screenId = PandoricalApi.getOpenScreenId(player.getUUID());
		if (screenId != null) PandoricalApi.screens().close(player, screenId);
		composeSessions.remove(player.getUUID());
	}

	private static void returnUnconsumedCompose(ServerPlayer player) {
		ComposeSession session = composeSessions.remove(player.getUUID());
		if (session == null || session.consumed) return;
		giveBack(player, session.container.getItem(0));
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

		String title = Component.translatable("block.village-mail.public_mailbox").getString();
		RecipientEntry selected = session.recipients.isEmpty() ? null : session.recipients.get(0);
		String recipientDisplay = selected == null
			? Component.translatable("village-mail.screen.no_players").getString()
			: displayName(selected);

		// Vanilla container layout: the player's own inventory in its usual place, the
		// mailbox's business stacked above it, "Inventory" labelled the way every
		// vanilla container labels it.
		ScreenBuilder b = new ScreenBuilder(SCREEN_PUBLIC_MAILBOX)
			.size(SCREEN_W, PUBLIC_H)
			.title(title)
			.container(1, true)
			.panel("bg", 0, 0, SCREEN_W, PUBLIC_H, PANEL)
			.text("title", MARGIN, TITLE_Y, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_COLOR, INK))
			// The board is the mailbox's other page, so it sits in the corner as a tab
			// rather than taking a share of the row the send button needs.
			.button("bulletin_btn", RIGHT - 76, 4, 76, 16, Map.of(
				ComponentType.PROP_LABEL_KEY, "village-mail.screen.bulletin"))
			.button("prev_btn", MARGIN, 24, 16, 18, Map.of(ComponentType.PROP_LABEL, "<", ComponentType.PROP_ENABLED, "false"))
			.button("next_btn", RIGHT - 16, 24, 16, 18, Map.of(
				ComponentType.PROP_LABEL, ">", ComponentType.PROP_ENABLED, String.valueOf(session.recipients.size() > 1)))
			.text("recipient_name", 30, 28, Map.of(ComponentType.PROP_TEXT, recipientDisplay, ComponentType.PROP_COLOR, INK))
			.component(new ComponentBuilder("body_input", ComponentType.TEXT_INPUT)
				.bounds(MARGIN, 46, CONTENT_W, 14)
				.prop(ComponentType.PROP_VALUE, "")
				.prop(ComponentType.PROP_MAX_LENGTH, String.valueOf(MailMessage.MAX_BODY_LENGTH))
				.prop(ComponentType.PROP_PLACEHOLDER_KEY, "village-mail.screen.message"))
			.text("attach_label", MARGIN, 68, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.screen.attach").getString(),
				ComponentType.PROP_COLOR, INK))
			.inventoryGrid("attachment_slot", 50, 64, 1, 1, 0)
			.button("send_btn", RIGHT - 52, 64, 52, 18, Map.of(
				ComponentType.PROP_LABEL_KEY, "village-mail.screen.send",
				ComponentType.PROP_ENABLED, String.valueOf(publicSendEnabled(session))))
			.text("inventory_label", MARGIN, PLAYER_INV_Y - 10, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("container.inventory").getString(),
				ComponentType.PROP_COLOR, INK))
			.inventoryGrid("player_inv", MARGIN, PLAYER_INV_Y, 3, 9, 1)
			.inventoryGrid("hotbar", MARGIN, PLAYER_INV_Y + 58, 1, 9, 28);

		// Open first, register second: see openCompose. The teardown of whatever the
		// player had open finds its session by UUID, so a session registered ahead of
		// the call is the one it cleans up.
		PandoricalApi.screens().openContainer(player, b.build(), session.container, Set.of());
		publicMailboxSessions.put(player.getUUID(), session);
	}

	/**
	 * The board half of the public mailbox: what the village would pin up rather
	 * than post. Read-only, so it needs no session of its own; the mailbox
	 * position comes from the session that opened it.
	 */
	public static void openBulletin(ServerPlayer player) {
		PublicMailboxSession session = publicMailboxSessions.get(player.getUUID());
		if (session == null) return;

		ServerLevel level = player.level();
		String title = Component.translatable("village-mail.screen.bulletin_title").getString();

		ScreenBuilder b = new ScreenBuilder(SCREEN_BULLETIN)
			.size(SCREEN_W, BULLETIN_H)
			.title(title)
			.panel("bg", 0, 0, SCREEN_W, BULLETIN_H, PANEL)
			.text("title", MARGIN, TITLE_Y, Map.of(ComponentType.PROP_TEXT, title, ComponentType.PROP_COLOR, INK))
			.text("weather_head", MARGIN, 24, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.bulletin.weather").getString(),
				ComponentType.PROP_COLOR, INK))
			.text("weather_body", MARGIN, 36, Map.of(
				ComponentType.PROP_TEXT, weatherReport(level),
				ComponentType.PROP_COLOR, INK_BODY,
				ComponentType.PROP_WRAP_WIDTH, String.valueOf(CONTENT_W)))
			.text("notices_head", MARGIN, 58, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.bulletin.notices").getString(),
				ComponentType.PROP_COLOR, INK));

		List<VillageBulletin.Notice> notices =
			VillageBulletin.get(level.getServer()).noticesNear(level, session.pos, BULLETIN_MAX_NOTICES);

		if (notices.isEmpty()) {
			b.text("notice_none", MARGIN, 70, Map.of(
				ComponentType.PROP_TEXT, Component.translatable("village-mail.bulletin.quiet").getString(),
				ComponentType.PROP_COLOR, INK_MUTED));
		} else {
			int y = 70;
			for (int i = 0; i < notices.size(); i++) {
				Map<String, String> noticeProps = Map.of(
					ComponentType.PROP_TEXT, "- " + notices.get(i).text(),
					ComponentType.PROP_WRAP_WIDTH, String.valueOf(CONTENT_W),
					ComponentType.PROP_COLOR, INK_BODY);
				b.text("notice_" + i, MARGIN, y, noticeProps);
				y += 14;
			}
		}

		b.button("bulletin_back", MARGIN, BULLETIN_H - 26, 76, 18, Map.of(ComponentType.PROP_LABEL_KEY, "village-mail.screen.back"));

		PandoricalApi.screens().open(player, b.build());
	}

	/**
	 * Plain language, not numbers: a notice board says what the sky is doing, the
	 * way a villager would tell you on the way past.
	 */
	private static String weatherReport(ServerLevel level) {
		String key;
		if (level.isThundering()) {
			key = "village-mail.bulletin.weather.storm";
		} else if (level.isRaining()) {
			key = "village-mail.bulletin.weather.rain";
		} else {
			key = "village-mail.bulletin.weather.clear";
		}
		return Component.translatable(key).getString();
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
		giveBack(player, session.container.getItem(0));
	}

	/** Into the player's inventory, or at their feet when there is no room for it. */
	private static void giveBack(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) return;
		if (!player.getInventory().add(stack.copy())) {
			player.drop(stack.copy(), false, Prediction.SERVER_ONLY);
		}
	}

	// ========================================================================
	// Shared helpers
	// ========================================================================

	private static java.nio.file.Path playerDataDir(MinecraftServer server) {
		return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
	}

	/**
	 * Whether mail can be addressed to this UUID: the same rule the recipient roster
	 * is built from, so anything the compose screen offers can actually be sent.
	 *
	 * <p>A recipient without a mailbox is not a missing recipient - storage parks their
	 * mail in the pending queue and hands it over when they place one. The send path
	 * used to demand a mailbox or an online player while the roster offered everyone
	 * who had ever played, so choosing an offline player off that list reported
	 * "recipient not found".
	 */
	private static boolean isAddressable(MinecraftServer server, UUID uuid) {
		if (PlayerMailStorage.get(server).hasMailbox(uuid)) return true;
		if (server.getPlayerList().getPlayer(uuid) != null) return true;
		return java.nio.file.Files.exists(playerDataDir(server).resolve(uuid + ".dat"));
	}

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
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(playerDataDir(server))) {
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

		if (!isAddressable(server, recipientUuid)) {
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
