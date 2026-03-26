package justfatlard.village_mail;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.util.Identifier;

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
		Identifier.of("minecraft", "village/plains/houses"),
		Identifier.of("minecraft", "village/desert/houses"),
		Identifier.of("minecraft", "village/savanna/houses"),
		Identifier.of("minecraft", "village/snowy/houses"),
		Identifier.of("minecraft", "village/taiga/houses")
	};

	// Decor pools - decorative elements in villages
	// Public mailbox spawns here (may occasionally appear in corrals)
	private static final Identifier[] DECOR_POOLS = {
		Identifier.of("minecraft", "village/plains/decor"),
		Identifier.of("minecraft", "village/desert/decor"),
		Identifier.of("minecraft", "village/savanna/decor"),
		Identifier.of("minecraft", "village/snowy/decor"),
		Identifier.of("minecraft", "village/taiga/decor")
	};

	// Biome-specific post office structures
	private static final String[] BIOMES = {"plains", "desert", "savanna", "snowy", "taiga"};
	private static final Identifier PUBLIC_MAILBOX_STRUCTURE = Identifier.of(Main.MOD_ID, "public_mailbox");

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

			Registry<StructurePool> poolRegistry = server.getRegistryManager().getOrThrow(RegistryKeys.TEMPLATE_POOL);

			// Create public mailbox structure element
			// RIGID keeps it at exact position
			StructurePoolElement publicMailboxElement = StructurePoolElement.ofLegacySingle(
				PUBLIC_MAILBOX_STRUCTURE.toString()
			).apply(StructurePool.Projection.RIGID);

			LOGGER.info("Injecting biome-specific post offices...");

			// Inject biome-specific post offices into houses pools
			// Weight 5 for good chance of spawning
			for (int i = 0; i < BIOMES.length; i++) {
				String biome = BIOMES[i];
				Identifier poolId = HOUSES_POOLS[i];
				Identifier postOfficeId = Identifier.of(Main.MOD_ID, "post_office_" + biome);

				StructurePoolElement postOfficeElement = StructurePoolElement.ofLegacySingle(
					postOfficeId.toString()
				).apply(StructurePool.Projection.TERRAIN_MATCHING);

				StructurePool pool = poolRegistry.get(poolId);
				if (pool != null) {
					if (addElementToPool(pool, postOfficeElement, 5)) {
						LOGGER.info("Added " + biome + " post office to: " + poolId);
					}
				} else {
					LOGGER.error("Pool not found: " + poolId);
				}
			}

			// Inject public mailbox into decor pools
			// Weight 1 for 1-2 per village
			for (Identifier poolId : DECOR_POOLS) {
				StructurePool pool = poolRegistry.get(poolId);
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
	 * Inject an element into a StructurePool via reflection.
	 *
	 * WARNING: This matches fields by type, not name (names are obfuscated at runtime).
	 * If Mojang adds another ObjectArrayList field to StructurePool, this will inject
	 * into the wrong field. This is the most version-fragile code in the mod.
	 */
	@SuppressWarnings("unchecked")
	private static boolean addElementToPool(StructurePool pool, StructurePoolElement element, int weight) {
		try {
			// Find the flattened elements field (ObjectArrayList<StructurePoolElement>)
			// and the weighted entries field (List<Pair<StructurePoolElement, Integer>>).
			// Names are obfuscated at runtime, so match by type.
			Field elementsField = null;
			Field rawElementsField = null;

			for (Field field : StructurePool.class.getDeclaredFields()) {
				field.setAccessible(true);
				if (field.getType() == ObjectArrayList.class) {
					elementsField = field;
				} else if (field.getType() == List.class && rawElementsField == null) {
					// The raw weighted entries list (List<Pair<StructurePoolElement, Integer>>)
					Object value = field.get(pool);
					if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Pair) {
						rawElementsField = field;
					}
				}
			}

			if (elementsField == null) {
				LOGGER.error("Could not find elements field in StructurePool");
				return false;
			}

			ObjectArrayList<StructurePoolElement> elements = (ObjectArrayList<StructurePoolElement>) elementsField.get(pool);

			// Add the element N times to the flattened list (used for random selection)
			for (int i = 0; i < weight; i++) {
				elements.add(element);
			}

			// Also add to the weighted entries list if found (used for data pack reload/serialization)
			if (rawElementsField != null) {
				List<Pair<StructurePoolElement, Integer>> rawElements =
					(List<Pair<StructurePoolElement, Integer>>) rawElementsField.get(pool);
				// The list may be immutable; wrap in a mutable copy if needed
				List<Pair<StructurePoolElement, Integer>> mutable = new ArrayList<>(rawElements);
				mutable.add(Pair.of(element, weight));
				rawElementsField.set(pool, mutable);
			}

			return true;
		} catch (Exception e) {
			LOGGER.error("Failed to inject into structure pool (Minecraft version change may have altered StructurePool internals): " + e.getMessage());
			return false;
		}
	}

}
