package justfatlard.village_mail.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import justfatlard.village_mail.network.MailPayloads.*;
import justfatlard.village_mail.screen.ComposeMessageScreen;
import justfatlard.village_mail.screen.MessageDetailScreen;
import justfatlard.village_mail.screen.MessageListScreen;
import justfatlard.village_mail.screen.PublicMailboxScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Client-side network handlers for receiving mail data from server.
 */
public class MailClientNetworking {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");

	// Tracks the latest unread count from the server
	private static int lastUnreadCount = 0;

	// Tracks the latest recipient list from the server
	private static List<RecipientInfo> lastRecipientList = List.of();

	public static int getUnreadCount() {
		return lastUnreadCount;
	}

	public static List<RecipientInfo> getRecipientList() {
		return lastRecipientList;
	}

	/**
	 * Register all client-side packet handlers.
	 */
	public static void register() {
		// Handle message list updates
		ClientPlayNetworking.registerGlobalReceiver(MessageListS2C.ID, (payload, context) -> {
			context.client().execute(() -> {
				Screen screen = MinecraftClient.getInstance().currentScreen;
				if (screen instanceof MessageListScreen messageListScreen) {
					messageListScreen.updateMessageList(
						payload.messages(),
						payload.showingRead(),
						payload.unreadCount()
					);
				}
			});
		});

		// Handle message detail
		ClientPlayNetworking.registerGlobalReceiver(MessageDetailS2C.ID, (payload, context) -> {
			context.client().execute(() -> {
				Screen currentScreen = MinecraftClient.getInstance().currentScreen;
				MinecraftClient.getInstance().setScreen(new MessageDetailScreen(payload, currentScreen));
			});
		});

		// Handle unread count updates
		ClientPlayNetworking.registerGlobalReceiver(UnreadCountS2C.ID, (payload, context) -> {
			context.client().execute(() -> {
				lastUnreadCount = payload.count();
			});
		});

		// Handle recipients list
		ClientPlayNetworking.registerGlobalReceiver(RecipientsListS2C.ID, (payload, context) -> {
			context.client().execute(() -> {
				lastRecipientList = payload.recipients();
				// Notify any open compose screen
				Screen screen = MinecraftClient.getInstance().currentScreen;
				if (screen instanceof ComposeMessageScreen composeScreen) {
					composeScreen.updateRecipients(payload.recipients());
				} else if (screen instanceof PublicMailboxScreen publicScreen) {
					publicScreen.updateRecipients(payload.recipients());
				}
			});
		});

		// Handle action results — surface failures to the player
		ClientPlayNetworking.registerGlobalReceiver(ActionResultS2C.ID, (payload, context) -> {
			context.client().execute(() -> {
				if (!payload.success()) {
					MinecraftClient client = MinecraftClient.getInstance();
					if (client.player != null) {
						client.player.sendMessage(
							Text.literal(payload.message()).formatted(Formatting.RED),
							true
						);
					}
				}
			});
		});

		LOGGER.info("Client networking registered");
	}
}
