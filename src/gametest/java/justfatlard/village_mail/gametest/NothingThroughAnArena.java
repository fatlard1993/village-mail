package justfatlard.village_mail.gametest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import justfatlard.pandorical.gametest.Smoke;
import justfatlard.village_mail.Main;
import justfatlard.village_mail.api.MailApi;
import justfatlard.village_mail.mail.ArenaPost;
import justfatlard.village_mail.mail.Letters;
import justfatlard.village_mail.mail.MailMessage;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.pandorical.MailScreens;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mail carries nothing into or out of a PvP Dimensions arena, and the player's real mailbox stays
 * where it is, while the overworld behaves as it always has.
 *
 * <p>The arena is a stand-in: a dimension of that mod's namespace the test mod's own datapack adds,
 * since the dimension's namespace is all village-mail asks to know an arena by. The arena mailbox is
 * put exactly where the real one stands in the overworld, because the real registry matches a
 * mailbox by position alone, and that is the case that would have unregistered the real one.
 */
public final class NothingThroughAnArena implements FabricClientGameTest {
	private static final ResourceKey<Level> ARENA = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath("pvp-dimensions-justfatlard", "arena_standin"));

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Smoke.full()) return;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");

			// Outside an arena: a mailbox registers where it is put, and a letter to yourself is refused.
			BlockPos home = server.computeOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				BlockPos pos = player.blockPosition().east(2);
				place(s.overworld(), pos, player);
				check(realHome(PlayerMailStorage.get(s), player, pos),
					"a mailbox put down outside an arena was not registered: " + PlayerMailStorage.get(s).getMailboxLocation(player.getUUID()));
				check(!MailScreens.trySendMail(player, player.getUUID(), "To me", new ItemStack(Items.DIAMOND)),
					"a letter to yourself was posted outside an arena");
				return pos;
			});
			UUID realLetter = server.computeOnServer(s -> MailApi.sendMessageWithItems(s,
				connection.getServerPlayer().getUUID(), "Tester", "Something from outside", new ItemStack(Items.EMERALD, 3)));

			server.runOnServer(s -> {
				ServerLevel arena = s.getLevel(ARENA);
				check(arena != null, "the stand-in arena dimension is not loaded");
				ServerPlayer player = connection.getServerPlayer();
				player.getInventory().clearContent();
				player.teleportTo(arena, home.getX() + 0.5, arena.getMinY() + 4, home.getZ() + 1.5, Set.of(), 0, 0, true);
			});
			// Long enough for the send cooldown from the refused letter above to run out as well.
			context.waitTicks(40);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				ServerLevel arena = player.level();
				check(arena.dimension().equals(ARENA), "the player did not reach the stand-in arena");
				PlayerMailStorage storage = PlayerMailStorage.get(s);
				int realCount = storage.getMessageCount(player.getUUID());
				int heldCount = storage.getPendingCount(player.getUUID());

				// A mailbox in the arena goes on the arena's post and leaves the real one alone.
				place(arena, home, player);
				check(realHome(storage, player, home),
					"putting a mailbox down in an arena moved the real one: " + storage.getMailboxLocation(player.getUUID()));
				check(ArenaPost.get().mailbox(player).equals(Optional.of(home)), "the arena mailbox was not put on the arena's post");
				storage.registerMailbox(player.getUUID(), ARENA.identifier().toString(), home.above());
				check(realHome(storage, player, home), "the real post took an arena mailbox when asked outright");

				// The real letter is neither shown nor collected here.
				Letters here = ArenaPost.letters(player);
				check(here instanceof ArenaPost, "an arena's mailbox reads the real post");
				check(here.getMessage(player.getUUID(), realLetter).isEmpty(), "the arena's post shows a real letter");
				MailMessage real = storage.getMessage(player.getUUID(), realLetter).orElseThrow();
				MailScreens.collect(player, real);
				check(!real.isItemsCollected() && count(player, Items.EMERALD) == 0,
					"a real letter's attachment was collected inside an arena");

				// A letter to yourself is the one a solo arena can post, and it stays on the arena's post.
				check(MailScreens.trySendMail(player, player.getUUID(), "Practice letter", new ItemStack(Items.DIAMOND)),
					"a letter to yourself was refused in an arena");
				check(storage.getMessageCount(player.getUUID()) == realCount && storage.getPendingCount(player.getUUID()) == heldCount,
					"a letter posted in an arena reached the real post");
				List<MailMessage> letters = here.getUnreadMessages(player.getUUID());
				check(letters.size() == 1 && letters.get(0).hasUncollectedItems(), "the arena letter did not arrive: " + letters.size());
				MailScreens.collect(player, letters.get(0));
				check(count(player, Items.DIAMOND) == 1, "the arena letter's attachment could not be collected in the arena");
			});
			context.waitTicks(40);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(MailScreens.trySendMail(player, player.getUUID(), "Left behind", new ItemStack(Items.GOLD_INGOT)),
					"a second letter was refused in the arena");
				// Taking the arena mailbox away leaves the real one, at the same position, registered.
				player.level().setBlockAndUpdate(home, Blocks.AIR.defaultBlockState());
				check(realHome(PlayerMailStorage.get(s), player, home),
					"taking an arena mailbox away unregistered the real one: " + PlayerMailStorage.get(s).getMailboxLocation(player.getUUID()));
				player.getInventory().clearContent();
				player.teleportTo(s.overworld(), home.getX() + 0.5, home.getY(), home.getZ() + 1.5, Set.of(), 0, 0, true);
			});
			context.waitTicks(20);

			server.runOnServer(s -> {
				ServerPlayer player = connection.getServerPlayer();
				check(player.level() == s.overworld(), "the player did not get back to the overworld");
				PlayerMailStorage storage = PlayerMailStorage.get(s);
				check(ArenaPost.letters(player) == storage, "outside an arena the mailbox does not read the real post");
				check(ArenaPost.get().getUnreadCount(player.getUUID()) == 0, "the arena's letters followed the player out");
				for (MailMessage letter : storage.getMessages(player.getUUID())) {
					check(letter.getAttachments().stream().noneMatch(stack -> stack.is(Items.GOLD_INGOT) || stack.is(Items.DIAMOND)),
						"an arena letter's attachment reached the real post");
				}
				check(realHome(storage, player, home), "the real mailbox is not where it was put: " + storage.getMailboxLocation(player.getUUID()));

				// Outside, the real letter collects as it always has.
				MailMessage real = storage.getMessage(player.getUUID(), realLetter).orElseThrow();
				MailScreens.collect(player, real);
				check(real.isItemsCollected() && count(player, Items.EMERALD) == 3,
					"a real letter's attachment could not be collected outside the arena");
			});
		}
	}

	/** A mailbox put down by this player, the way the game does it for a player placing the item. */
	private static void place(ServerLevel level, BlockPos pos, ServerPlayer player) {
		BlockState state = Main.MAILBOX_BLOCK.defaultBlockState();
		level.setBlockAndUpdate(pos, state);
		Main.MAILBOX_BLOCK.setPlacedBy(level, pos, state, player, new ItemStack(Main.MAILBOX_ITEM));
	}

	private static boolean realHome(PlayerMailStorage storage, ServerPlayer player, BlockPos home) {
		return storage.getMailboxLocation(player.getUUID())
			.map(location -> location.dimension().equals("minecraft:overworld") && location.pos().equals(home))
			.orElse(false);
	}

	private static int count(ServerPlayer player, Item item) {
		return player.getInventory().countItem(item);
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError(what);
	}
}
