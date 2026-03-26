package justfatlard.village_mail.screen;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import justfatlard.village_mail.network.MailClientNetworking;

/**
 * Renders a subtle unread mail indicator on the HUD.
 */
public class MailHudOverlay implements HudRenderCallback {

	public static void register() {
		HudRenderCallback.EVENT.register(new MailHudOverlay());
	}

	@Override
	public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
		int unread = MailClientNetworking.getUnreadCount();
		if (unread <= 0) return;

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		if (client.currentScreen != null) return;

		var textRenderer = client.textRenderer;
		Text text = Text.translatable("village-mail.hud.unread", unread);
		int textWidth = textRenderer.getWidth(text);

		// Position: top-right, below the hotbar area
		int x = context.getScaledWindowWidth() - textWidth - 6;
		int y = 6;

		// Subtle background
		context.fill(x - 3, y - 2, x + textWidth + 3, y + 10, 0x60000000);
		// Text in warm yellow
		context.drawText(textRenderer, text, x, y, 0xFFD700, false);
	}
}
