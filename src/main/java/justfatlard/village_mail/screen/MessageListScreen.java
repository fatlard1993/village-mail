package justfatlard.village_mail.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.network.MailPayloads.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Client screen for displaying the message list (inbox).
 */
public class MessageListScreen extends HandledScreen<MessageListScreenHandler> {
	private static final Identifier TEXTURE = Identifier.of(Main.MOD_ID, "textures/gui/message_list.png");

	private List<MessageSummary> messages = new ArrayList<>();
	private boolean showingRead = false;
	private int unreadCount = 0;

	private ButtonWidget newTabButton;
	private ButtonWidget readTabButton;
	private ButtonWidget composeButton;
	private ButtonWidget scrollUpButton;
	private ButtonWidget scrollDownButton;
	private List<ButtonWidget> messageButtons = new ArrayList<>();

	private int scrollOffset = 0;
	private static final int VISIBLE_MESSAGES = 5;
	private static final int MESSAGE_HEIGHT = 22;

	public MessageListScreen(MessageListScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = 200;
		this.backgroundHeight = 180;
	}

	@Override
	protected void init() {
		super.init();
		titleX = (backgroundWidth - textRenderer.getWidth(title)) / 2;

		int x = (width - backgroundWidth) / 2;
		int y = (height - backgroundHeight) / 2;

		// Tab buttons
		newTabButton = ButtonWidget.builder(Text.translatable("village-mail.screen.tab_new"), button -> {
			showingRead = false;
			scrollOffset = 0;
			ClientPlayNetworking.send(new SwitchTabC2S(false));
		}).dimensions(x + 8, y + 18, 50, 16).build();

		readTabButton = ButtonWidget.builder(Text.translatable("village-mail.screen.tab_read"), button -> {
			showingRead = true;
			scrollOffset = 0;
			ClientPlayNetworking.send(new SwitchTabC2S(true));
		}).dimensions(x + 62, y + 18, 50, 16).build();

		// Compose button
		composeButton = ButtonWidget.builder(Text.translatable("village-mail.screen.compose"), button -> {
			MinecraftClient.getInstance().setScreen(new ComposeMessageScreen(this));
		}).dimensions(x + backgroundWidth - 68, y + backgroundHeight - 24, 60, 16).build();

		// Scroll buttons
		scrollUpButton = ButtonWidget.builder(Text.literal("\u25B2"), button -> {
			if (scrollOffset > 0) {
				scrollOffset--;
				rebuildMessageButtons();
			}
		}).dimensions(x + backgroundWidth - 22, y + 40, 16, 14).build();

		scrollDownButton = ButtonWidget.builder(Text.literal("\u25BC"), button -> {
			if (scrollOffset < messages.size() - VISIBLE_MESSAGES) {
				scrollOffset++;
				rebuildMessageButtons();
			}
		}).dimensions(x + backgroundWidth - 22, y + 40 + VISIBLE_MESSAGES * MESSAGE_HEIGHT - 14, 16, 14).build();

		addDrawableChild(newTabButton);
		addDrawableChild(readTabButton);
		addDrawableChild(composeButton);
		addDrawableChild(scrollUpButton);
		addDrawableChild(scrollDownButton);

		updateTabButtons();
		rebuildMessageButtons();
	}

	private void updateTabButtons() {
		newTabButton.active = showingRead;
		readTabButton.active = !showingRead;
	}

	private void rebuildMessageButtons() {
		for (ButtonWidget button : messageButtons) {
			remove(button);
		}
		messageButtons.clear();

		int x = (width - backgroundWidth) / 2;
		int y = (height - backgroundHeight) / 2;
		int listY = y + 40;

		int visibleStart = Math.min(scrollOffset, Math.max(0, messages.size() - VISIBLE_MESSAGES));
		int visibleEnd = Math.min(visibleStart + VISIBLE_MESSAGES, messages.size());

		for (int i = visibleStart; i < visibleEnd; i++) {
			final MessageSummary msg = messages.get(i);
			int msgY = listY + (i - visibleStart) * MESSAGE_HEIGHT;

			String label = msg.senderName();
			if (label.length() > 14) {
				label = label.substring(0, 11) + "...";
			}

			// Show attachment indicator
			String suffix = msg.hasAttachments() ? " \u2709" : "";
			final String buttonText = label + suffix;

			ButtonWidget msgButton = ButtonWidget.builder(Text.literal(buttonText), button -> {
				ClientPlayNetworking.send(new OpenMessageC2S(msg.id()));
			}).dimensions(x + 9, msgY, backgroundWidth - 34, MESSAGE_HEIGHT - 2).build();

			messageButtons.add(msgButton);
			addDrawableChild(msgButton);
		}

		scrollUpButton.active = scrollOffset > 0;
		scrollDownButton.active = scrollOffset < messages.size() - VISIBLE_MESSAGES;
	}

	public boolean isShowingRead() {
		return showingRead;
	}

	public void updateMessageList(List<MessageSummary> messages, boolean showingRead, int unreadCount) {
		this.messages = messages;
		this.showingRead = showingRead;
		this.unreadCount = unreadCount;
		this.scrollOffset = 0;
		updateTabButtons();
		rebuildMessageButtons();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (verticalAmount > 0 && scrollOffset > 0) {
			scrollOffset--;
			rebuildMessageButtons();
			return true;
		} else if (verticalAmount < 0 && scrollOffset < messages.size() - VISIBLE_MESSAGES) {
			scrollOffset++;
			rebuildMessageButtons();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int x = (width - backgroundWidth) / 2;
		int y = (height - backgroundHeight) / 2;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, title, titleX, 6, 0x404040, false);

		if (unreadCount > 0) {
			Text countText = Text.translatable("village-mail.screen.unread_count", unreadCount);
			context.drawText(textRenderer, countText, backgroundWidth - textRenderer.getWidth(countText) - 10, 6, 0xCC0000, false);
		}

		if (messages.isEmpty()) {
			Text emptyText = showingRead
				? Text.translatable("village-mail.screen.no_read_messages")
				: Text.translatable("village-mail.screen.no_new_messages");
			int textWidth = textRenderer.getWidth(emptyText);
			context.drawText(textRenderer, emptyText, (backgroundWidth - textWidth) / 2, 90, 0x808080, false);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);
	}
}
