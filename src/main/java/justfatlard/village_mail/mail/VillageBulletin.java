package justfatlard.village_mail.mail;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The notices pinned to a village's public mailbox.
 *
 * <p>Separate from player mail on purpose: a letter is addressed to someone and
 * a notice is addressed to whoever walks past. An obituary is posted once for
 * the village rather than mailed to every owner within range, so a player who
 * arrives a day later still reads it and a player with three mailboxes does not
 * read it three times.
 *
 * <p>Notices carry the position they were posted from and are read back by
 * proximity, which is what makes them village-scoped without needing village
 * identity: the next town's deaths are simply too far away to show.
 *
 * <p>Manual file persistence, matching {@link PlayerMailStorage}, so the two
 * stores load and save on the same server lifecycle.
 */
public class VillageBulletin {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	private static final String FILE_NAME = "village-mail-bulletin.dat";

	/** How far a notice carries. Matches the obituary radius, so both agree on what "this village" means. */
	public static final int NOTICE_RADIUS = 128;

	/** A notice ages out after this long, so a board reads as current rather than as a memorial wall. */
	private static final long NOTICE_LIFETIME_TICKS = 24000L * 12;

	/** Hard cap so a raid cannot fill the file with a hundred deaths. */
	private static final int MAX_NOTICES = 200;

	private static VillageBulletin INSTANCE;

	private MinecraftServer server;
	private final List<Notice> notices = new ArrayList<>();

	/** One posting: what happened, where, and when. */
	public record Notice(String dimension, BlockPos pos, long postedAt, String text) {}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (INSTANCE == null) {
				INSTANCE = new VillageBulletin();
				INSTANCE.server = server;
				INSTANCE.load();
			} else {
				INSTANCE.server = server;
			}
			LOGGER.info("Loaded village bulletin ({} notices)", INSTANCE.notices.size());
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (INSTANCE != null) {
				INSTANCE.save();
				INSTANCE = null;
			}
		});
	}

	public static VillageBulletin get(MinecraftServer server) {
		if (INSTANCE == null) {
			INSTANCE = new VillageBulletin();
			INSTANCE.server = server;
			INSTANCE.load();
		}
		return INSTANCE;
	}

	/** Pin a notice to every board within {@link #NOTICE_RADIUS} of where it happened. */
	public void post(ServerLevel level, BlockPos pos, String text) {
		notices.add(new Notice(
			level.dimension().identifier().toString(), pos.immutable(),
			level.getGameTime(), text));
		prune(level.getGameTime());
		save();
	}

	/**
	 * The notices a board at {@code pos} should show, newest first.
	 *
	 * @param limit most a board displays at once; older ones stay stored until they age out
	 */
	public List<Notice> noticesNear(ServerLevel level, BlockPos pos, int limit) {
		String dimension = level.dimension().identifier().toString();
		long now = level.getGameTime();

		return notices.stream()
			.filter(n -> n.dimension().equals(dimension))
			.filter(n -> now - n.postedAt() < NOTICE_LIFETIME_TICKS)
			.filter(n -> n.pos().closerThan(pos, NOTICE_RADIUS))
			.sorted(Comparator.comparingLong(Notice::postedAt).reversed())
			.limit(limit)
			.toList();
	}

	private void prune(long now) {
		notices.removeIf(n -> now - n.postedAt() >= NOTICE_LIFETIME_TICKS);
		while (notices.size() > MAX_NOTICES) {
			notices.remove(0);
		}
	}

	private File getDataFile() {
		Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
		dataDir.toFile().mkdirs();
		return dataDir.resolve(FILE_NAME).toFile();
	}

	public void save() {
		if (server == null) return;
		try {
			CompoundTag root = new CompoundTag();
			ListTag list = new ListTag();
			for (Notice notice : notices) {
				CompoundTag tag = new CompoundTag();
				tag.putString("Dimension", notice.dimension());
				tag.putInt("X", notice.pos().getX());
				tag.putInt("Y", notice.pos().getY());
				tag.putInt("Z", notice.pos().getZ());
				tag.putLong("PostedAt", notice.postedAt());
				tag.putString("Text", notice.text());
				list.add(tag);
			}
			root.put("Notices", list);
			NbtIo.writeCompressed(root, getDataFile().toPath());
		} catch (Exception e) {
			LOGGER.error("Failed to save village bulletin: {}", e.getMessage());
		}
	}

	private void load() {
		notices.clear();
		try {
			File file = getDataFile();
			if (!file.exists()) return;

			CompoundTag root = NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
			ListTag list = root.getList("Notices").orElse(new ListTag());
			for (int i = 0; i < list.size(); i++) {
				CompoundTag tag = list.getCompound(i).orElse(new CompoundTag());
				notices.add(new Notice(
					tag.getStringOr("Dimension", "minecraft:overworld"),
					new BlockPos(tag.getIntOr("X", 0), tag.getIntOr("Y", 0), tag.getIntOr("Z", 0)),
					tag.getLongOr("PostedAt", 0L),
					tag.getStringOr("Text", "")));
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load village bulletin: {}", e.getMessage());
		}
	}
}
