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

			// Weight 5 for good chance of spawning
			for (int i = 0; i < BIOMES.length; i++) {
				String biome = BIOMES[i];
				Identifier poolId = HOUSES_POOLS[i];
				Identifier postOfficeId = Identifier.fromNamespaceAndPath(Main.MOD_ID, "post_office_" + biome);

				StructurePoolElement postOfficeElement = StructurePoolElement.legacy(
					postOfficeId.toString()
				).apply(StructureTemplatePool.Projection.TERRAIN_MATCHING);

				StructureTemplatePool pool = poolRegistry.getValue(poolId);
				if (pool != null) {
					if (addElementToPool(pool, postOfficeElement, 5)) {
						LOGGER.info("Added " + biome + " post office to: " + poolId);
					}
				} else {
					LOGGER.error("Pool not found: " + poolId);
				}
			}

			// Weight 1 for 1-2 per village
			for (Identifier poolId : DECOR_POOLS) {
				StructureTemplatePool pool = poolRegistry.getValue(poolId);
				if (pool != null) {
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
