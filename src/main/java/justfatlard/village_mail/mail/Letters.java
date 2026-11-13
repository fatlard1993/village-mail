package justfatlard.village_mail.mail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Somewhere a player's letters are kept: the server's own post ({@link PlayerMailStorage}), or the
 * stand-alone post of a PvP Dimensions arena ({@link ArenaPost}). The screens read and change a
 * player's letters through this, so the same mailbox shows whichever post the player is standing in.
 */
public interface Letters {
	Optional<MailMessage> getMessage(UUID playerUuid, UUID messageId);

	List<MailMessage> getUnreadMessages(UUID playerUuid);

	List<MailMessage> getReadMessages(UUID playerUuid);

	int getUnreadCount(UUID playerUuid);

	void markAsRead(UUID playerUuid, UUID messageId);

	void markItemsCollected(UUID playerUuid, UUID messageId);

	void deleteMessage(UUID playerUuid, UUID messageId);

	/** Letters held at the post for a player with no mailbox. */
	int getPendingCount(UUID playerUuid);

	/** Hand over what the post holds; returns how many letters changed hands. */
	int collectPending(UUID playerUuid);
}
