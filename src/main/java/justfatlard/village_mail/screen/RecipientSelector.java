package justfatlard.village_mail.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;

import justfatlard.village_mail.network.MailPayloads.RecipientInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shared recipient selection logic for compose screens.
 * Manages the player list, selection index, and merging of online/server-provided recipients.
 */
public class RecipientSelector {
	public record PlayerInfo(UUID uuid, String name) {}

	private final List<PlayerInfo> players = new ArrayList<>();
	private int selectedIndex = 0;

	public List<PlayerInfo> getPlayers() {
		return players;
	}

	public int getSelectedIndex() {
		return selectedIndex;
	}

	public boolean isEmpty() {
		return players.isEmpty();
	}

	public PlayerInfo getSelected() {
		if (players.isEmpty()) return null;
		return players.get(selectedIndex);
	}

	public void selectPrevious() {
		if (selectedIndex > 0) selectedIndex--;
	}

	public void selectNext() {
		if (selectedIndex < players.size() - 1) selectedIndex++;
	}

	public boolean hasPrevious() {
		return selectedIndex > 0;
	}

	public boolean hasNext() {
		return selectedIndex < players.size() - 1;
	}

	/**
	 * Load online players from the client's player list.
	 * Optionally ensures a prefill recipient is included (for reply/forward).
	 */
	public void loadOnlinePlayers(String prefillUuid, String prefillName) {
		players.clear();
		selectedIndex = 0;

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() != null) {
			Collection<PlayerListEntry> entries = client.getNetworkHandler().getPlayerList();
			for (PlayerListEntry entry : entries) {
				if (client.player != null && entry.getProfile().id().equals(client.player.getUuid())) {
					continue;
				}
				players.add(new PlayerInfo(entry.getProfile().id(), entry.getProfile().name()));
			}
		}

		if (prefillUuid != null && prefillName != null) {
			boolean found = players.stream()
				.anyMatch(p -> p.uuid().toString().equals(prefillUuid));
			if (!found) {
				try {
					players.add(0, new PlayerInfo(UUID.fromString(prefillUuid), prefillName));
				} catch (IllegalArgumentException e) {
					// Invalid UUID
				}
			}
		}
	}

	/**
	 * Update from server-provided recipient list.
	 * Merges with any prefill recipient, preserves current selection.
	 */
	public void updateFromServer(List<RecipientInfo> recipients, String prefillUuid, String prefillName) {
		UUID selectedUuid = !players.isEmpty() ? players.get(selectedIndex).uuid() : null;

		players.clear();
		Set<String> seen = new HashSet<>();

		// For reply/forward, ensure prefill recipient is first
		if (prefillUuid != null && prefillName != null) {
			try {
				players.add(new PlayerInfo(UUID.fromString(prefillUuid), prefillName));
				seen.add(prefillUuid);
			} catch (IllegalArgumentException e) {
				// Invalid UUID
			}
		}

		for (RecipientInfo info : recipients) {
			if (seen.add(info.uuid())) {
				try {
					String displayName = info.online() ? info.name() : info.name() + " " + Text.translatable("village-mail.screen.offline").getString();
					players.add(new PlayerInfo(UUID.fromString(info.uuid()), displayName));
				} catch (IllegalArgumentException e) {
					// Invalid UUID from server
				}
			}
		}

		// Restore selection
		if (selectedUuid != null) {
			selectByUuid(selectedUuid.toString());
		}
	}

	/**
	 * Select a recipient by UUID string.
	 */
	public void selectByUuid(String uuid) {
		for (int i = 0; i < players.size(); i++) {
			if (players.get(i).uuid().toString().equals(uuid)) {
				selectedIndex = i;
				return;
			}
		}
	}
}
