package justfatlard.village_mail.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.network.MailPayloads.*;

import java.util.List;

/**
 * Client screen for the public mailbox — a compose-and-send station with one attachment slot.
 */
public class PublicMailboxScreen extends HandledScreen<PublicMailboxScreenHandler> {
	private static final Identifier TEXTURE = Identifier.of(Main.MOD_ID, "textures/gui/public_mailbox.png");
	private static final int MAX_BODY_LENGTH = MailMessage.MAX_BODY_LENGTH;

	private ButtonWidget prevPlayerButton;
	private ButtonWidget nextPlayerButton;
	private ButtonWidget sendButton;
	private ButtonWidget donateButton;
	private TextFieldWidget bodyField;

	private final RecipientSelector recipients = new RecipientSelector();

	public PublicMailboxScreen(PublicMailboxScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		this.backgroundWidth = 176;
		this.backgroundHeight = 166;
	}

	@Override
	protected void init() {
		super.init();
		titleX = (backgroundWidth - textRenderer.getWidth(title)) / 2;

		// Start with online players, then request full list from server
		recipients.loadOnlinePlayers(null, null);
		ClientPlayNetworking.send(new RequestRecipientsC2S());

		int x = (width - backgroundWidth) / 2;
		int y = (height - backgroundHeight) / 2;

		// Recipient navigation
		prevPlayerButton = ButtonWidget.builder(Text.literal("<"), button -> {
			recipients.selectPrevious();
		}).dimensions(x + 7, y + 16, 16, 18).build();

		nextPlayerButton = ButtonWidget.builder(Text.literal(">"), button -> {
			recipients.selectNext();
		}).dimensions(x + backgroundWidth - 23, y + 16, 16, 18).build();

		addDrawableChild(prevPlayerButton);
		addDrawableChild(nextPlayerButton);

		// Message body field
		bodyField = new TextFieldWidget(
			textRenderer, x + 8, y + 37, backgroundWidth - 16, 14, Text.translatable("village-mail.screen.message")
		);
		bodyField.setMaxLength(MAX_BODY_LENGTH);
		bodyField.setEditable(true);
		addDrawableChild(bodyField);

		// Donate button
		donateButton = ButtonWidget.builder(Text.translatable("village-mail.screen.donate"), button -> {
			ClientPlayNetworking.send(new DonateC2S());
			close();
		}).dimensions(x + 8, y + 56, 42, 16).build();
		addDrawableChild(donateButton);

		// Send button
		sendButton = ButtonWidget.builder(Text.translatable("village-mail.screen.send"), button -> {
			sendMessage();
		}).dimensions(x + backgroundWidth - 50, y + 56, 42, 16).build();
		addDrawableChild(sendButton);
	}

	/**
	 * Called by MailClientNetworking when the server sends the full recipient list.
	 */
	public void updateRecipients(List<RecipientInfo> serverRecipients) {
		recipients.updateFromServer(serverRecipients, null, null);
	}

	private void sendMessage() {
		RecipientSelector.PlayerInfo recipient = recipients.getSelected();
		if (recipient == null) return;
		String body = bodyField.getText().trim();
		if (body.isEmpty()) return;

		ClientPlayNetworking.send(new SendMessageC2S(recipient.uuid().toString(), body));
		close();
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int x = (width - backgroundWidth) / 2;
		int y = (height - backgroundHeight) / 2;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);
	}

	@Override
	protected void handledScreenTick() {
		super.handledScreenTick();
		prevPlayerButton.active = recipients.hasPrevious();
		nextPlayerButton.active = recipients.hasNext();
		sendButton.active = !recipients.isEmpty() && !bodyField.getText().trim().isEmpty();
		donateButton.active = !handler.getSlot(0).getStack().isEmpty();
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, title, titleX, 5, 0x404040, false);

		// Recipient display
		RecipientSelector.PlayerInfo selected = recipients.getSelected();
		Text recipientDisplay = selected == null
			? Text.translatable("village-mail.screen.no_players")
			: Text.literal(selected.name());
		int recipientX = (backgroundWidth - textRenderer.getWidth(recipientDisplay)) / 2;
		context.drawText(textRenderer, recipientDisplay, recipientX, 20, 0x000000, false);

		// "Attach:" label
		Text attachLabel = Text.translatable("village-mail.screen.attach");
		context.drawText(textRenderer, attachLabel, 8, 62, 0x606060, false);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);
		drawMouseoverTooltip(context, mouseX, mouseY);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
