package justfatlard.village_mail.mail;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The post inside a PvP Dimensions arena: a stand-alone one, kept in memory, that never touches the
 * server's own mail.
 *
 * <p>An arena is a plot in one of that mod's dimensions, with the player's own pack set aside on the
 * way in, a kit handed out, and everything thrown away on the way out, plot and all. The server's post
 * crosses all of that: a letter posted in an arena carried its attachment out of it, a mailbox put down
 * there moved the player's real mailbox into a plot about to be deleted, and an item collected from real
 * mail went into an inventory about to be thrown away. So in an arena the mail is the arena's own:
 * a mailbox there is registered here and not in {@link PlayerMailStorage}, letters go only between
 * players in the same arena (or to yourself, which is the one letter a solo arena can post), the mail
 * person's visits leave their gifts here, and all of it is forgotten when the player leaves. Nothing
 * here is saved, because nothing here is meant to outlast the arena.
 *
 * <p>Arenas are told apart by their dimension's namespace alone, so this mod needs nothing from PvP
 * Dimensions to know one.
 */
public final class ArenaPost implements Letters {
	/** The namespace of every PvP Dimensions arena dimension. */
	public static final String ARENA_NAMESPACE = "pvp-dimensions-justfatlard";

	/**
	 * How close two players in one arena dimension are to be in the same arena. Arenas share a
	 * dimension a couple of thousand blocks apart, and one is at most a few hundred blocks across,
	 * so this is comfortably inside one and well short of the next.
	 */
	private static final double SAME_ARENA = 1024;

	/** A player's letters in an arena are a handful at most; this only stops one being flooded. */
	private static final int MAX_LETTERS = 50;

	private static final ArenaPost INSTANCE = new ArenaPost();

	/** One player's time in one arena: where it is, their letters, their mailbox, the mail person's last call. */
	private static final class Stay {
		final String dimension;
		final BlockPos anchor;
		final List<MailMessage> letters = new ArrayList<>();
		BlockPos mailbox;
		Long lastVisit;

		Stay(String dimension, BlockPos anchor) {
			this.dimension = dimension;
			this.anchor = anchor;
		}

		boolean holds(ServerPlayer player) {
			return dimension.equals(dimensionOf(player.level())) && near(anchor, player.blockPosition());
		}
	}

	private final Map<UUID, Stay> stays = new ConcurrentHashMap<>();

	private ArenaPost() {}

	public static ArenaPost get() {
		return INSTANCE;
	}

	// ========== Telling an arena ==========

	public static boolean isArena(Level level) {
		return level != null && ARENA_NAMESPACE.equals(level.dimension().identifier().getNamespace());
	}

	/** For a dimension written down as text, the way {@link PlayerMailStorage} keeps one. */
	public static boolean isArena(String dimension) {
		return dimension != null && dimension.startsWith(ARENA_NAMESPACE + ":");
	}

	/**
	 * Where a player's mail is, as a word to compare later: empty outside any arena, the arena's
	 * dimension inside one. A screen opened in one place and acted on in another is how an item
	 * would slip across, so the screens note this when they open and check it when they act.
	 */
	public static String place(ServerPlayer player) {
		return isArena(player.level()) ? dimensionOf(player.level()) : "";
	}

	/** The post a player's letters are in where they stand: this one in an arena, the server's anywhere else. */
	public static Letters letters(ServerPlayer player) {
		if (!isArena(player.level())) return PlayerMailStorage.get(player.level().getServer());
		INSTANCE.check(player);
		return INSTANCE;
	}

	/** Whether these two players are in the same arena, and so can post to each other there. */
	public static boolean sameArena(ServerPlayer a, ServerPlayer b) {
		return isArena(a.level()) && a.level() == b.level() && near(a.blockPosition(), b.blockPosition());
	}

	private static boolean near(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return dx * dx + dz * dz < SAME_ARENA * SAME_ARENA;
	}

	private static String dimensionOf(Level level) {
		return level.dimension().identifier().toString();
	}

	// ========== A player's stay ==========

	/** Forget a stay that belongs to another arena than the one the player is in now. */
	private void check(ServerPlayer player) {
		Stay stay = stays.get(player.getUUID());
		if (stay != null && !stay.holds(player)) stays.remove(player.getUUID());
	}

	private Stay stay(ServerPlayer player) {
		check(player);
		return stays.computeIfAbsent(player.getUUID(),
			k -> new Stay(dimensionOf(player.level()), player.blockPosition().immutable()));
	}

	/** Everything this player had in an arena's post, gone: they have left it. */
	public void leave(UUID playerUuid) {
		stays.remove(playerUuid);
	}

	/** Called when the server stops. */
	public void clear() {
		stays.clear();
	}

	// ========== Mailboxes ==========

	/** A mailbox put down or opened in an arena: the one the arena's mail person calls at. */
	public void placeMailbox(ServerPlayer player, BlockPos pos) {
		stay(player).mailbox = pos.immutable();
	}

	/** A mailbox taken away in an arena; only forgets it if it is the one on record. */
	public void removeMailbox(UUID owner, BlockPos pos) {
		Stay stay = stays.get(owner);
		if (stay != null && pos.equals(stay.mailbox)) stay.mailbox = null;
	}

	/** This player's mailbox in the arena they are in, if they have put one down. */
	public Optional<BlockPos> mailbox(ServerPlayer player) {
		check(player);
		Stay stay = stays.get(player.getUUID());
		return stay == null ? Optional.empty() : Optional.ofNullable(stay.mailbox);
	}

	/** When the mail person last called at this player's arena mailbox, in game time; empty for never. */
	public Optional<Long> lastVisit(UUID playerUuid) {
		Stay stay = stays.get(playerUuid);
		return stay == null ? Optional.empty() : Optional.ofNullable(stay.lastVisit);
	}

	public void setLastVisit(UUID playerUuid, long gameTime) {
		Stay stay = stays.get(playerUuid);
		if (stay != null) stay.lastVisit = gameTime;
	}

	// ========== Letters ==========

	/** Put a letter in this player's arena mailbox. The player is standing in the arena it is for. */
	public void deliver(ServerPlayer recipient, MailMessage message) {
		List<MailMessage> letters = stay(recipient).letters;
		while (letters.size() >= MAX_LETTERS) letters.remove(letters.size() - 1);
		letters.add(0, message);
	}

	private List<MailMessage> lettersOf(UUID playerUuid) {
		Stay stay = stays.get(playerUuid);
		return stay == null ? List.of() : stay.letters;
	}

	@Override
	public Optional<MailMessage> getMessage(UUID playerUuid, UUID messageId) {
		return lettersOf(playerUuid).stream().filter(m -> m.getId().equals(messageId)).findFirst();
	}

	@Override
	public List<MailMessage> getUnreadMessages(UUID playerUuid) {
		return lettersOf(playerUuid).stream().filter(m -> !m.isRead()).collect(Collectors.toList());
	}

	@Override
	public List<MailMessage> getReadMessages(UUID playerUuid) {
		return lettersOf(playerUuid).stream().filter(MailMessage::isRead).collect(Collectors.toList());
	}

	@Override
	public int getUnreadCount(UUID playerUuid) {
		return (int) lettersOf(playerUuid).stream().filter(m -> !m.isRead()).count();
	}

	@Override
	public void markAsRead(UUID playerUuid, UUID messageId) {
		getMessage(playerUuid, messageId).ifPresent(m -> m.setRead(true));
	}

	@Override
	public void markItemsCollected(UUID playerUuid, UUID messageId) {
		getMessage(playerUuid, messageId).ifPresent(m -> m.setItemsCollected(true));
	}

	@Override
	public void deleteMessage(UUID playerUuid, UUID messageId) {
		Stay stay = stays.get(playerUuid);
		if (stay != null) stay.letters.removeIf(m -> m.getId().equals(messageId));
	}

	/** Nothing is ever held at an arena's post: a letter there only goes to someone standing in it. */
	@Override
	public int getPendingCount(UUID playerUuid) {
		return 0;
	}

	@Override
	public int collectPending(UUID playerUuid) {
		return 0;
	}
}
