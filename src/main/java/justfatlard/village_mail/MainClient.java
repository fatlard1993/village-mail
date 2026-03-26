package justfatlard.village_mail;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

import justfatlard.village_mail.screen.MailHudOverlay;
import justfatlard.village_mail.screen.MessageListScreen;
import justfatlard.village_mail.screen.PublicMailboxScreen;
import justfatlard.village_mail.network.MailClientNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	@Override
	public void onInitializeClient() {
		HandledScreens.register(Main.MAILBOX_SCREEN_HANDLER, MessageListScreen::new);
		HandledScreens.register(Main.PUBLIC_MAILBOX_SCREEN_HANDLER, PublicMailboxScreen::new);

		// Register client-side network handlers
		MailClientNetworking.register();

		// Register unread mail HUD indicator
		MailHudOverlay.register();

		LOGGER.info("Client initialized");
	}
}
