package justfatlard.village_mail.pandorical;

import justfatlard.pandorical.api.ComponentBuilder;
import justfatlard.pandorical.api.ComponentType;
import justfatlard.pandorical.api.HudBuilder;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.protocol.ComponentUpdate;

import justfatlard.village_mail.mail.PlayerMailStorage;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the unread-mail HUD badge via Pandorical's HUD API.
 * Mirrors map-plus-plus's MapEquipHandler show/update/hide pattern.
 */
public final class MailHud {
	private MailHud() {}

	private static final String OVERLAY_ID = "village-mail:unread_mail";
	private static final String TEXT_COMPONENT_ID = "unread_text";

	// Players the badge is currently shown for, so we know whether to show() or update()/hide()
	private static final Set<UUID> shown = ConcurrentHashMap.newKeySet();

	/**
	 * Recompute the player's unread count and show/update/hide the HUD badge accordingly.
	 * No-op for players without the "hud" Pandorical capability.
	 */
	public static void updateUnreadCount(ServerPlayer player) {
		if (!PandoricalApi.isAvailable(player) || !PandoricalApi.hasCapability(player, "hud")) return;

		PlayerMailStorage storage = PlayerMailStorage.get(player.level().getServer());
		int count = storage.getUnreadCount(player.getUUID());
		UUID uuid = player.getUUID();

		if (count <= 0) {
			if (shown.remove(uuid)) {
				PandoricalApi.hud().hide(player, OVERLAY_ID);
			}
			return;
		}

		String text = Component.translatable("village-mail.hud.unread", count).getString();

		if (shown.add(uuid)) {
			ComponentBuilder textComponent = new ComponentBuilder(TEXT_COMPONENT_ID, ComponentType.TEXT)
				.pos(0, 0)
				.prop(ComponentType.PROP_TEXT, text)
				.prop(ComponentType.PROP_COLOR, "#FFD700")
				.prop(ComponentType.PROP_SHADOW, "true");

			HudBuilder hud = new HudBuilder(OVERLAY_ID)
				.anchor("top_right")
				.offset(6, 6)
				.component(textComponent);

			PandoricalApi.hud().show(player, hud.build());
		} else {
			PandoricalApi.hud().update(player, OVERLAY_ID, List.of(
				new ComponentUpdate(TEXT_COMPONENT_ID, Map.of(ComponentType.PROP_TEXT, text))
			));
		}
	}

	public static void onPlayerDisconnect(UUID uuid) {
		shown.remove(uuid);
	}
}
