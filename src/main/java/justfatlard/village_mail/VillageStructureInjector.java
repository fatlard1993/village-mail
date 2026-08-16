package justfatlard.village_mail;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VillageStructureInjector {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	// Vanilla's houses pools total ~87 weight, so 3 is ~3% per house slot:
	// most sizable villages get a post office, duplicates stay uncommon.
	// Weight 5 produced two in one village; weight 1 produced almost none.
	private static final int POST_OFFICE_WEIGHT = 3;

	/**
	 * How many times each existing decor entry is repeated before the public
	 * mailbox is added, so that one entry is a small share rather than an eighth.
	 * Raising this makes street mailboxes rarer; lowering it makes them common.
	 *
	 * <p>This mutates a shared vanilla pool, so a mod injecting into decor after
	 * us is diluted by the same factor. Nothing else in this suite touches decor.
	 */
	private static final int DECOR_DILUTION = 4;

	private static boolean injected = false;

	// Houses pools - buildings that spawn in villages
	// Post office spawns here as a proper building
	private static final Identifier[] HOUSES_POOLS = {
		Identifier.fromNamespaceAndPath("minecraft", "village/plains/houses"),
		Identifier.fromNamespaceAndPath("minecraft", "village/desert/houses"),
		Identifier.fromNamespaceAndPath("minecraft", "village/savanna/houses"),
		Identifier.fromNamespaceAndPath("minecraft", "village/snowy/houses"),
		Identifier.fromNamespaceAndPath("minecraft", "village/taiga/houses")
	};

	// Decor pools - decorative elements in villages
	// Public mailbox spawns here (may occasionally appear in corrals)
	private static final Identifier[] DECOR_POOLS = {
		Identifier.fromNamespaceAndPath("minecraft", "village/plains/decor"),
		Identifier.fromNamespaceAndPath("minecraft", "village/desert/decor"),
		Identifier.fromNamespaceAndPath("minecraft", "village/savanna/decor"),
		Identifier.fromNamespaceAndPath("minecraft", "village/snowy/decor"),
		Identifier.fromNamespaceAndPath("minecraft", "village/taiga/decor")
	};

	private static final String[] BIOMES = {"plains", "desert", "savanna", "snowy", "taiga"};
	private static final Identifier PUBLIC_MAILBOX_STRUCTURE = Identifier.fromNamespaceAndPath(Main.MOD_ID, "public_mailbox");

	public static void register() {
		// Reset on server stop so singleplayer world reloads re-inject
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			injected = false;
		});

		// Use SERVER_STARTING (not SERVER_STARTED) so injection happens before world generation
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			if (injected) {
				LOGGER.info("Structure injection already done, skipping");
				return;
			}
			injected = true;

			Registry<StructureTemplatePool> poolRegistry = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);

			// RIGID keeps it at exact position
			StructurePoolElement publicMailboxElement = StructurePoolElement.legacy(
				PUBLIC_MAILBOX_STRUCTURE.toString()
			).apply(StructureTemplatePool.Projection.RIGID);

			LOGGER.info("Injecting biome-specific post offices...");

			for (int i = 0; i < BIOMES.length; i++) {
				String biome = BIOMES[i];
				Identifier poolId = HOUSES_POOLS[i];
				Identifier postOfficeId = Identifier.fromNamespaceAndPath(Main.MOD_ID, "post_office_" + biome);

				// RIGID like every vanilla house: TERRAIN_MATCHING deforms the
				// piece column-by-column to hug the ground, which is right for
				// streets and farm plots and contorts a walled building
				StructurePoolElement postOfficeElement = StructurePoolElement.legacy(
					postOfficeId.toString()
				).apply(StructureTemplatePool.Projection.RIGID);

				StructureTemplatePool pool = poolRegistry.getValue(poolId);
				if (pool != null) {
					if (addElementToPool(pool, postOfficeElement, POST_OFFICE_WEIGHT)) {
						LOGGER.info("Added " + biome + " post office to: " + poolId);
					}
				} else {
					LOGGER.error("Pool not found: " + poolId);
				}
			}

			// Weight 1 is not "rare" in a decor pool: vanilla's plains decor totals
			// only 7 weight across 5 entries, so one more entry is an eighth of
			// every decor slot, and a village fills dozens of them. That is how a
			// town ended up with a mailbox on every corner.
			//
			// Integer weights cannot express a small enough share on their own, so
			// the pool is scaled up first: each existing entry is repeated
			// DECOR_DILUTION times, then the mailbox goes in once. Plains becomes
			// 7*4 + 1 = 29, or ~3.4% per slot, which lands about one per village.
			// Every vanilla entry keeps its share of the rest exactly.
			for (Identifier poolId : DECOR_POOLS) {
				StructureTemplatePool pool = poolRegistry.getValue(poolId);
				if (pool != null) {
					dilutePool(pool, DECOR_DILUTION);
					if (addElementToPool(pool, publicMailboxElement, 1)) {
						LOGGER.info("Added public mailbox to: " + poolId);
					}
				} else {
					LOGGER.error("Pool not found: " + poolId);
				}
			}

			LOGGER.info("Village mail structures configured");
		});
	}

	/**
	 * Repeat every entry already in a pool {@code factor} times, so a single new
	 * entry added afterwards lands at a smaller share than an integer weight of 1
	 * could otherwise express. Relative odds among the existing entries are
	 * unchanged; only the denominator grows.
	 *
	 * <p>Touches the flattened selection list only. {@code rawTemplates} keeps the
	 * original weights, so a datapack reload serializes the pool as it was rather
	 * than baking the scaling in permanently.
	 */
	@SuppressWarnings("unchecked")
	private static void dilutePool(StructureTemplatePool pool, int factor) {
		if (factor <= 1) return;
		try {
			Field templatesField = StructureTemplatePool.class.getDeclaredField("templates");
			templatesField.setAccessible(true);
			ObjectArrayList<StructurePoolElement> elements =
				(ObjectArrayList<StructurePoolElement>) templatesField.get(pool);

			List<StructurePoolElement> original = new ArrayList<>(elements);
			for (int i = 1; i < factor; i++) {
				elements.addAll(original);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to scale decor pool; public mailboxes will be common: " + e.getMessage());
		}
	}

	/**
	 * Inject an element into a StructureTemplatePool via reflection.
	 *
	 * WARNING: This depends on StructureTemplatePool's private fields "templates"
	 * (flattened ObjectArrayList<StructurePoolElement>, used for random selection) and
	 * "rawTemplates" (List<Pair<StructurePoolElement, Integer>>, used for data pack
	 * reload/serialization). This is the most version-fragile code in the mod.
	 */
	@SuppressWarnings("unchecked")
	private static boolean addElementToPool(StructureTemplatePool pool, StructurePoolElement element, int weight) {
		try {
			Field templatesField = StructureTemplatePool.class.getDeclaredField("templates");
			templatesField.setAccessible(true);
			ObjectArrayList<StructurePoolElement> elements = (ObjectArrayList<StructurePoolElement>) templatesField.get(pool);

			// Add the element N times to the flattened list (used for random selection)
			for (int i = 0; i < weight; i++) {
				elements.add(element);
			}

			// Also add to the weighted entries list if found (used for data pack reload/serialization)
			Field rawTemplatesField = StructureTemplatePool.class.getDeclaredField("rawTemplates");
			rawTemplatesField.setAccessible(true);
			List<Pair<StructurePoolElement, Integer>> rawElements =
				(List<Pair<StructurePoolElement, Integer>>) rawTemplatesField.get(pool);
			// The list may be immutable; wrap in a mutable copy if needed
			List<Pair<StructurePoolElement, Integer>> mutable = new ArrayList<>(rawElements);
			mutable.add(Pair.of(element, weight));
			rawTemplatesField.set(pool, mutable);

			return true;
		} catch (Exception e) {
			LOGGER.error("Failed to inject into structure pool (Minecraft version change may have altered StructureTemplatePool internals): " + e.getMessage());
			return false;
		}
	}

}
