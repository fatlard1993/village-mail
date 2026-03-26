package justfatlard.village_mail.api;

import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.mail.MessageButton;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal implementation for the Mail API.
 * Handles button handler registry and event firing.
 */
public class MailApiImpl {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	// Button handler registry: handler ID -> handler function
	private static final Map<Identifier, MailApi.ButtonHandler> buttonHandlers = new ConcurrentHashMap<>();

	// Event callbacks
	private static final List<BiConsumer<UUID, MailMessage>> messageReadCallbacks = new ArrayList<>();
	private static final List<BiConsumer<UUID, MailMessage>> itemsCollectedCallbacks = new ArrayList<>();
	private static final List<BiConsumer<UUID, MailMessage>> messageSentCallbacks = new ArrayList<>();

	// ========== Handler Registry ==========

	/**
	 * Register a button handler.
	 */
	static void registerHandler(Identifier id, MailApi.ButtonHandler handler) {
		buttonHandlers.put(id, handler);
		LOGGER.info("Registered button handler: " + id);
	}

	/**
	 * Invoke a registered button handler.
	 *
	 * @return true if the message should be deleted, false otherwise
	 */
	public static boolean invokeButtonHandler(
			Identifier handlerId,
			MinecraftServer server,
			ServerPlayerEntity player,
			MailMessage message,
			MessageButton button) {

		MailApi.ButtonHandler handler = buttonHandlers.get(handlerId);
		if (handler != null) {
			try {
				return handler.handle(server, player, message, button);
			} catch (Exception e) {
				LOGGER.error("Error invoking button handler {}: {}", handlerId, e.getMessage(), e);
			}
		} else {
			LOGGER.info("No handler found for: " + handlerId);
		}
		return false;
	}

	/**
	 * Check if a handler is registered.
	 */
	public static boolean hasHandler(Identifier handlerId) {
		return buttonHandlers.containsKey(handlerId);
	}

	// ========== Event Callbacks ==========

	static void addMessageReadCallback(BiConsumer<UUID, MailMessage> callback) {
		synchronized (messageReadCallbacks) {
			messageReadCallbacks.add(callback);
		}
	}

	static void addItemsCollectedCallback(BiConsumer<UUID, MailMessage> callback) {
		synchronized (itemsCollectedCallbacks) {
			itemsCollectedCallbacks.add(callback);
		}
	}

	static void addMessageSentCallback(BiConsumer<UUID, MailMessage> callback) {
		synchronized (messageSentCallbacks) {
			messageSentCallbacks.add(callback);
		}
	}

	// ========== Event Firing ==========

	/**
	 * Fire the message read event.
	 */
	public static void fireMessageRead(UUID playerUuid, MailMessage message) {
		List<BiConsumer<UUID, MailMessage>> callbacks;
		synchronized (messageReadCallbacks) {
			callbacks = new ArrayList<>(messageReadCallbacks);
		}
		for (BiConsumer<UUID, MailMessage> callback : callbacks) {
			try {
				callback.accept(playerUuid, message);
			} catch (Exception e) {
				LOGGER.error("Error in message read callback: " + e.getMessage());
			}
		}
	}

	/**
	 * Fire the items collected event.
	 */
	public static void fireItemsCollected(UUID playerUuid, MailMessage message) {
		List<BiConsumer<UUID, MailMessage>> callbacks;
		synchronized (itemsCollectedCallbacks) {
			callbacks = new ArrayList<>(itemsCollectedCallbacks);
		}
		for (BiConsumer<UUID, MailMessage> callback : callbacks) {
			try {
				callback.accept(playerUuid, message);
			} catch (Exception e) {
				LOGGER.error("Error in items collected callback: " + e.getMessage());
			}
		}
	}

	/**
	 * Fire the message sent event.
	 */
	public static void fireMessageSent(UUID recipientUuid, MailMessage message) {
		List<BiConsumer<UUID, MailMessage>> callbacks;
		synchronized (messageSentCallbacks) {
			callbacks = new ArrayList<>(messageSentCallbacks);
		}
		for (BiConsumer<UUID, MailMessage> callback : callbacks) {
			try {
				callback.accept(recipientUuid, message);
			} catch (Exception e) {
				LOGGER.error("Error in message sent callback: " + e.getMessage());
			}
		}
	}
}
