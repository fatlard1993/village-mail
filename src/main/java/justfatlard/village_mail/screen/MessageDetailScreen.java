package justfatlard.village_mail.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.network.MailPayloads.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Client screen for displaying full message details — rendered on parchment.
 */
public class MessageDetailScreen extends Screen {
	private static final int SCREEN_WIDTH = 220;
	private static final int SCREEN_HEIGHT = 200;
	private static final Identifier TEXTURE = Identifier.of(Main.MOD_ID, "textures/gui/message_detail.png");
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

	private final MessageDetailS2C message;
	private final Screen parent;
	private final boolean parentShowingRead;

	private ButtonWidget backButton;
	private List<ButtonWidget> actionButtons = new ArrayList<>();

	public MessageDetailScreen(MessageDetailS2C message, Screen parent) {
		super(Text.translatable("village-mail.screen.message_title"));
		this.message = message;
		this.parent = parent;
		// Preserve the parent's tab state so back returns to the same tab
		this.parentShowingRead = (parent instanceof MessageListScreen mls) && mls.isShowingRead();
	}

	@Override
	protected void init() {
		super.init();

		int x = (width - SCREEN_WIDTH) / 2;
		int y = (height - SCREEN_HEIGHT) / 2;

		// Back button — refresh the parent inbox, preserving current tab
		backButton = ButtonWidget.builder(Text.translatable("village-mail.screen.back"), button -> {
			ClientPlayNetworking.send(new RefreshMessagesC2S(parentShowingRead));
			MinecraftClient.getInstance().setScreen(parent);
		}).dimensions(x + 8, y + 6, 50, 16).build();
		addDrawableChild(backButton);

		// Delete button
		ButtonWidget deleteButton = ButtonWidget.builder(Text.translatable("village-mail.screen.delete"), button -> {
			ClientPlayNetworking.send(new DeleteMessageC2S(message.id()));
			MinecraftClient.getInstance().setScreen(parent);
		}).dimensions(x + SCREEN_WIDTH - 58, y + 6, 50, 16).build();
		addDrawableChild(deleteButton);

		// Action buttons at bottom
		int buttonY = y + SCREEN_HEIGHT - 26;
		int buttonX = x + 8;
		int buttonWidth = 60;
		int buttonSpacing = 4;

		for (ButtonData buttonData : message.buttons()) {
			final String buttonId = buttonData.id();
			final String buttonType = buttonData.type();

			if (buttonType.equals("COLLECT_ITEMS") && message.itemsCollected()) {
				continue;
			}

			// Hide Reply on non-player messages (senderUuid is empty for villager/system)
			if (buttonType.equals("REPLY") && message.senderUuid().isEmpty()) {
				continue;
			}

			Text buttonLabel = buttonType.equals("CUSTOM")
				? Text.literal(buttonData.label())
				: Text.translatable(buttonData.label());
			ButtonWidget actionButton = ButtonWidget.builder(
				buttonLabel,
				button -> handleButtonClick(buttonId, buttonType)
			).dimensions(buttonX, buttonY, buttonWidth, 18).build();

			actionButtons.add(actionButton);
			addDrawableChild(actionButton);
			buttonX += buttonWidth + buttonSpacing;

			if (buttonX + buttonWidth > x + SCREEN_WIDTH - 8) {
				buttonX = x + 8;
				buttonY -= 22;
			}
		}
	}

	private void handleButtonClick(String buttonId, String buttonType) {
		switch (buttonType) {
			case "REPLY" -> {
				// Handled entirely client-side — no server round-trip needed
				MinecraftClient.getInstance().setScreen(new ComposeMessageScreen(
					parent,
					ComposeMessageScreen.ComposeMode.REPLY,
					message.senderUuid(),
					message.senderName(),
					null
				));
			}
			case "FORWARD" -> {
				MinecraftClient.getInstance().setScreen(new ComposeMessageScreen(
					parent,
					ComposeMessageScreen.ComposeMode.FORWARD,
					null, null,
					message.body()
				));
			}
			default -> {
				// All other button types need server-side handling
				ClientPlayNetworking.send(new ButtonClickC2S(message.id(), buttonId));
				if ("COLLECT_ITEMS".equals(buttonType)) {
					ClientPlayNetworking.send(new RefreshMessagesC2S(false));
					MinecraftClient.getInstance().setScreen(parent);
				}
			}
		}
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);

		int x = (width - SCREEN_WIDTH) / 2;
		int y = (height - SCREEN_HEIGHT) / 2;

		// Draw parchment background on top of the blur/dim
		context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, 256, 256);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		int x = (width - SCREEN_WIDTH) / 2;
		int y = (height - SCREEN_HEIGHT) / 2;

		// Header area
		int headerY = y + 32;
		Text senderLabel = Text.translatable("village-mail.screen.from", message.senderName());
		context.drawText(textRenderer, senderLabel, x + 12, headerY + 4, 0x3B2D1F, false);

		Text timeLabel = formatTimestamp(message.timestamp());
		context.drawText(textRenderer, timeLabel, x + 12, headerY + 16, 0x6B5B4F, false);

		// Message type (subtle)
		if (!message.messageType().equals("PLAYER")) {
			Text typeBadge = switch (message.messageType()) {
				case "QUEST" -> Text.translatable("village-mail.screen.badge_quest");
				case "MOD" -> Text.translatable("village-mail.screen.badge_mod");
				case "SYSTEM" -> Text.translatable("village-mail.screen.badge_system");
				default -> Text.literal("[" + message.messageType() + "]");
			};
			int badgeWidth = textRenderer.getWidth(typeBadge);
			context.drawText(textRenderer, typeBadge, x + SCREEN_WIDTH - badgeWidth - 12, headerY + 4, 0x8B7B6F, false);
		}

		// Attachment indicator
		if (message.attachmentCount() > 0) {
			Text attachText = message.itemsCollected()
				? Text.translatable("village-mail.screen.items_collected")
				: Text.translatable("village-mail.screen.attachment_count", message.attachmentCount());
			int attachColor = message.itemsCollected() ? 0x6B5B4F : 0x2D5A1F;
			context.drawText(textRenderer, attachText, x + 12, headerY + 28, attachColor, false);
		}

		// Message body
		int bodyY = y + 82;
		renderWrappedText(context, message.body(), x + 12, bodyY, SCREEN_WIDTH - 28, 0x3B2D1F);
	}

	private void renderWrappedText(DrawContext context, String text, int x, int y, int maxWidth, int color) {
		List<String> lines = wrapText(text, maxWidth);
		int lineHeight = 10;
		int maxLines = 8;

		for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
			context.drawText(textRenderer, lines.get(i), x, y + i * lineHeight, color, false);
		}

		if (lines.size() > maxLines) {
			context.drawText(textRenderer, "...", x, y + maxLines * lineHeight, 0x8B7B6F, false);
		}
	}

	private List<String> wrapText(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();

		for (String paragraph : text.split("\n")) {
			if (paragraph.isEmpty()) {
				lines.add("");
				continue;
			}

			String[] words = paragraph.split(" ");
			StringBuilder currentLine = new StringBuilder();

			for (String word : words) {
				String testLine = currentLine.length() > 0
					? currentLine + " " + word
					: word;

				if (textRenderer.getWidth(testLine) <= maxWidth) {
					if (currentLine.length() > 0) {
						currentLine.append(" ");
					}
					currentLine.append(word);
				} else {
					if (currentLine.length() > 0) {
						lines.add(currentLine.toString());
						currentLine = new StringBuilder(word);
					} else {
						lines.add(word);
					}
				}
			}

			if (currentLine.length() > 0) {
				lines.add(currentLine.toString());
			}
		}

		return lines;
	}

	private Text formatTimestamp(long timestamp) {
		long diff = System.currentTimeMillis() - timestamp;

		if (diff < 60_000) return Text.translatable("village-mail.screen.time_just_now");
		if (diff < 3_600_000) {
			long minutes = diff / 60_000;
			return Text.translatable("village-mail.screen.time_minutes_ago", minutes);
		}
		if (diff < 86_400_000) {
			long hours = diff / 3_600_000;
			return Text.translatable("village-mail.screen.time_hours_ago", hours);
		}
		return Text.literal(DATE_FORMAT.format(Instant.ofEpochMilli(timestamp)));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
