package justfatlard.village_mail.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.network.MailPayloads.*;

import java.util.List;

/**
 * Client screen for composing and sending text messages.
 * Text-only — for sending items, use the public mailbox.
 */
public class ComposeMessageScreen extends Screen {
	private static final int SCREEN_WIDTH = 240;
	private static final int SCREEN_HEIGHT = 200;
	private static final int MAX_BODY_LENGTH = MailMessage.MAX_BODY_LENGTH;
	private static final Identifier TEXTURE = Identifier.of(Main.MOD_ID, "textures/gui/compose_message.png");

	private final Screen parent;
	private final ComposeMode mode;
	private final String prefillRecipientUuid;
	private final String prefillRecipientName;
	private final String prefillBody;

	private ButtonWidget cancelButton;
	private ButtonWidget sendButton;
	private ButtonWidget prevPlayerButton;
	private ButtonWidget nextPlayerButton;
	private TextFieldWidget bodyField;

	private final RecipientSelector recipients = new RecipientSelector();

	public enum ComposeMode {
		NEW, REPLY, FORWARD
	}

	public ComposeMessageScreen(Screen parent) {
		this(parent, ComposeMode.NEW, null, null, null);
	}

	public ComposeMessageScreen(Screen parent, ComposeMode mode, String recipientUuid, String recipientName, String prefillBody) {
		super(Text.translatable(mode == ComposeMode.REPLY ? "village-mail.screen.title_reply"
			: mode == ComposeMode.FORWARD ? "village-mail.screen.title_forward"
			: "village-mail.screen.title_new_message"));
		this.parent = parent;
		this.mode = mode;
		this.prefillRecipientUuid = recipientUuid;
		this.prefillRecipientName = recipientName;
		this.prefillBody = prefillBody;
	}

	@Override
	protected void init() {
		super.init();

		int x = (width - SCREEN_WIDTH) / 2;
		int y = (height - SCREEN_HEIGHT) / 2;

		// Start with online players, then request full list from server
		recipients.loadOnlinePlayers(prefillRecipientUuid, prefillRecipientName);
		ClientPlayNetworking.send(new RequestRecipientsC2S());

		if (prefillRecipientUuid != null) {
			recipients.selectByUuid(prefillRecipientUuid);
		}

		// Cancel button
		cancelButton = ButtonWidget.builder(Text.translatable("village-mail.screen.cancel"), button -> {
			MinecraftClient.getInstance().setScreen(parent);
		}).dimensions(x + 8, y + 6, 50, 16).build();
		addDrawableChild(cancelButton);

		// Recipient navigation
		prevPlayerButton = ButtonWidget.builder(Text.literal("<"), button -> {
			recipients.selectPrevious();
			updateButtonStates();
		}).dimensions(x + 8, y + 18, 20, 20).build();

		nextPlayerButton = ButtonWidget.builder(Text.literal(">"), button -> {
			recipients.selectNext();
			updateButtonStates();
		}).dimensions(x + SCREEN_WIDTH - 28, y + 18, 20, 20).build();

		addDrawableChild(prevPlayerButton);
		addDrawableChild(nextPlayerButton);

		// Message body
		bodyField = new TextFieldWidget(
			textRenderer, x + 10, y + 60, SCREEN_WIDTH - 20, 106, Text.translatable("village-mail.screen.message")
		);
		bodyField.setMaxLength(MAX_BODY_LENGTH);
		bodyField.setEditable(true);
		bodyField.setChangedListener(text -> updateButtonStates());

		if (mode == ComposeMode.FORWARD && prefillBody != null) {
			bodyField.setText(Text.translatable("village-mail.screen.forwarded_prefix").getString() + "\n" + prefillBody);
		}

		addDrawableChild(bodyField);

		// Send button
		sendButton = ButtonWidget.builder(Text.translatable("village-mail.screen.send"), button -> {
			sendMessage();
		}).dimensions(x + SCREEN_WIDTH - 68, y + SCREEN_HEIGHT - 28, 60, 18).build();
		addDrawableChild(sendButton);

		updateButtonStates();
	}

	/**
	 * Called by MailClientNetworking when the server sends the full recipient list.
	 */
	public void updateRecipients(List<RecipientInfo> serverRecipients) {
		recipients.updateFromServer(serverRecipients, prefillRecipientUuid, prefillRecipientName);
		updateButtonStates();
	}

	private void updateButtonStates() {
		prevPlayerButton.active = recipients.hasPrevious();
		nextPlayerButton.active = recipients.hasNext();
		sendButton.active = !recipients.isEmpty() && !bodyField.getText().trim().isEmpty();
	}

	private void sendMessage() {
		RecipientSelector.PlayerInfo recipient = recipients.getSelected();
		if (recipient == null) return;
		String body = bodyField.getText().trim();
		if (body.isEmpty()) return;

		ClientPlayNetworking.send(new SendMessageC2S(recipient.uuid().toString(), body));
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);

		int x = (width - SCREEN_WIDTH) / 2;
		int y = (height - SCREEN_HEIGHT) / 2;

		// Draw texture background on top of the blur/dim
		context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, 256, 256);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		int x = (width - SCREEN_WIDTH) / 2;
		int y = (height - SCREEN_HEIGHT) / 2;

		// Title
		Text titleText = Text.translatable(mode == ComposeMode.REPLY ? "village-mail.screen.title_reply"
			: mode == ComposeMode.FORWARD ? "village-mail.screen.title_forward"
			: "village-mail.screen.title_new_message");
		context.drawText(textRenderer, titleText, x + SCREEN_WIDTH / 2 - textRenderer.getWidth(titleText) / 2, y + 8, 0x404040, false);

		// "To:" label
		Text toLabel = Text.translatable("village-mail.screen.to");
		context.drawText(textRenderer, toLabel, x + 10, y + 42, 0x404040, false);

		// Recipient display
		RecipientSelector.PlayerInfo selected = recipients.getSelected();
		Text recipientDisplay = selected == null
			? Text.translatable("village-mail.screen.no_players")
			: Text.literal(selected.name());
		int recipientTextX = x + SCREEN_WIDTH / 2 - textRenderer.getWidth(recipientDisplay) / 2;
		context.drawText(textRenderer, recipientDisplay, recipientTextX, y + 24, 0x000000, false);

		// Player count
		if (!recipients.isEmpty()) {
			String countText = (recipients.getSelectedIndex() + 1) + "/" + recipients.getPlayers().size();
			context.drawText(textRenderer, countText, x + SCREEN_WIDTH / 2 - textRenderer.getWidth(countText) / 2, y + 42, 0x808080, false);
		}

		// "Message:" label
		Text messageLabel = Text.translatable("village-mail.screen.message");
		context.drawText(textRenderer, messageLabel, x + 10, y + 50, 0x404040, false);

		// Character count
		String charCount = bodyField.getText().length() + "/" + MAX_BODY_LENGTH;
		int charCountColor = bodyField.getText().length() > MAX_BODY_LENGTH - 20 ? 0xCC0000 : 0x808080;
		context.drawText(textRenderer, charCount, x + SCREEN_WIDTH - 10 - textRenderer.getWidth(charCount), y + SCREEN_HEIGHT - 10, charCountColor, false);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
