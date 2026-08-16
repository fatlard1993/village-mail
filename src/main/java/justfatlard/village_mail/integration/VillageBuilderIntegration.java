package justfatlard.village_mail.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration with the Village Builder mod.
 *
 * Registers biome-specific post office templates as buildable village structures.
 * Uses registerTemplatePersistent() so material requirements are auto-derived
 * from the NBT block composition, and registrations survive world reloads.
 *
 * Actual API surface targeted:
 *   justfatlard.village_builder.api.VillageBuilderAPI
 *   justfatlard.village_builder.village.VillageNeedsAnalyzer$VillageNeed
 *   justfatlard.village_builder.building.StructureType$MaterialRequirement
 */
public class VillageBuilderIntegration {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");

	private static boolean reflectionInitialized = false;
	private static boolean available = false;

	// VillageBuilderAPI methods
	private static Method cachedRegisterTemplatePersistentMethod;  // (Identifier, String, Set<VillageNeed>, List<MaterialRequirement>, Set<String>, int)
	private static Method cachedProcessDonatedMaterialsMethod;     // (ServerLevel, BlockPos, List<ItemStack>) -> DonationResult
	private static Method cachedGetConstructionStatusMethod;       // (ServerLevel, BlockPos) -> Component

	// VillageNeed enum
	private static Object cachedNeedProfession;  // VillageNeed.PROFESSION
	private static Object cachedNeedProsperity;  // VillageNeed.PROSPERITY

	// MaterialRequirement constructor
	private static Constructor<?> cachedMaterialReqConstructor;  // (Item, int)

	/**
	 * Initialize reflection caches. Safe to call multiple times; only runs once.
	 */
	private static void initReflection() {
		if (reflectionInitialized) return;
		reflectionInitialized = true;

		if (!FabricLoader.getInstance().isModLoaded("village-builder")) {
			LOGGER.info("Village Builder not found, integration unavailable");
			return;
		}

		try {
			Class<?> apiClass = Class.forName("justfatlard.village_builder.api.VillageBuilderAPI");

			// VillageNeed enum
			@SuppressWarnings("unchecked")
			Class<Enum<?>> needEnum = (Class<Enum<?>>) Class.forName(
				"justfatlard.village_builder.village.VillageNeedsAnalyzer$VillageNeed");
			cachedNeedProfession = Enum.valueOf((Class) needEnum, "PROFESSION");
			cachedNeedProsperity = Enum.valueOf((Class) needEnum, "PROSPERITY");

			// MaterialRequirement record
			Class<?> matReqClass = Class.forName(
				"justfatlard.village_builder.building.StructureType$MaterialRequirement");
			cachedMaterialReqConstructor = matReqClass.getDeclaredConstructor(Item.class, int.class);

			cachedRegisterTemplatePersistentMethod = apiClass.getMethod("registerTemplatePersistent",
				Identifier.class, String.class, Set.class, List.class, Set.class, int.class);

			cachedProcessDonatedMaterialsMethod = apiClass.getMethod("processDonatedMaterials",
				ServerLevel.class, BlockPos.class, List.class);

			cachedGetConstructionStatusMethod = apiClass.getMethod("getConstructionStatus",
				ServerLevel.class, BlockPos.class);

			available = true;
			LOGGER.info("Village Builder integration available");
		} catch (ClassNotFoundException e) {
			LOGGER.error("Village Builder API not found (wrong version?): {}", e.getMessage());
		} catch (Exception e) {
			LOGGER.error("Failed to initialize Village Builder reflection: {}", e.getMessage(), e);
		}
	}

	/**
	 * Whether the Village Builder donation path is usable (mod loaded and its API
	 * resolved via reflection). UI entry points that only make sense with a live
	 * donation target must check this, not just isModLoaded.
	 */
	public static boolean isAvailable() {
		initReflection();
		return available;
	}

	/**
	 * Register village-mail structures with Village Builder.
	 * Each biome variant carries its biome preference so the builder picks the
	 * right style for the village; fallback materials cover NBT analysis failure.
	 */
	public static void registerStructures() {
		initReflection();
		if (!available) return;

		LOGGER.info("Village Builder found, registering post office structures...");

		try {
			List<Object> postOfficeFallback = List.of(
				cachedMaterialReqConstructor.newInstance(Items.STONE, 160),
				cachedMaterialReqConstructor.newInstance(Items.OAK_LOG, 80),
				cachedMaterialReqConstructor.newInstance(Items.OAK_PLANKS, 64),
				cachedMaterialReqConstructor.newInstance(Items.GLASS, 32),
				cachedMaterialReqConstructor.newInstance(Items.IRON_INGOT, 16)
			);

			// PROFESSION (mail person workstation) + PROSPERITY (community building)
			Set<Object> postOfficeNeeds = Set.of(cachedNeedProfession, cachedNeedProsperity);

			String[] biomes = {"plains", "desert", "savanna", "taiga", "snowy"};
			for (String biome : biomes) {
				cachedRegisterTemplatePersistentMethod.invoke(null,
					Identifier.fromNamespaceAndPath("village-mail", "post_office_" + biome),
					formatName(biome) + " Post Office",
					postOfficeNeeds,
					postOfficeFallback,
					Set.of(biome),
					5  // clearance size
				);
				LOGGER.info("Registered {} post office (biome: {})", biome, biome);
			}

			List<Object> mailboxFallback = List.of(
				cachedMaterialReqConstructor.newInstance(Items.OAK_LOG, 12),
				cachedMaterialReqConstructor.newInstance(Items.IRON_INGOT, 4)
			);

			// Public mailbox: PROSPERITY need (village atmosphere), any biome
			cachedRegisterTemplatePersistentMethod.invoke(null,
				Identifier.fromNamespaceAndPath("village-mail", "public_mailbox"),
				"Public Mailbox",
				Set.of(cachedNeedProsperity),
				mailboxFallback,
				Set.of(),  // empty = all biomes
				2  // clearance size
			);
			LOGGER.info("Registered public mailbox (all biomes)");

			LOGGER.info("Successfully registered {} structures with Village Builder", biomes.length + 1);

		} catch (Exception e) {
			LOGGER.error("Failed to register with Village Builder: {}", e.getMessage(), e);
		}
	}

	private static String formatName(String type) {
		return type.substring(0, 1).toUpperCase() + type.substring(1);
	}

	/**
	 * Outcome of routing a donation through Village Builder.
	 *
	 * @param rejected     non-building-material items the village won't accept; the
	 *                     recoverable ItemStacks the caller must return to the sender.
	 * @param overflowLost count of building-material items that were accepted in
	 *                     principle but did not fit the village's 27-slot inventory.
	 *                     Village Builder's API path destroys these silently (only a
	 *                     count survives, not the item objects), so the caller can only
	 *                     surface the count to the sender, not the items themselves.
	 */
	public record DonationOutcome(List<ItemStack> rejected, int overflowLost) {}

	/**
	 * Process donated items through the Village Builder system.
	 * Items needed for construction are accepted; the rest are returned as
	 * {@link DonationOutcome#rejected()}. Building materials that don't fit the
	 * village inventory are reported as {@link DonationOutcome#overflowLost()} — the
	 * caller must surface that count so the sender is told their materials were lost
	 * (see village-builder INTEGRATION_EXAMPLE.md: API-path overflow is silently
	 * destroyed, not dropped as entities).
	 */
	public static DonationOutcome processDonation(ServerLevel world, BlockPos donationPos, List<ItemStack> donatedItems) {
		initReflection();
		if (!available) {
			return new DonationOutcome(donatedItems, 0);
		}

		try {
			Object donationResult = cachedProcessDonatedMaterialsMethod.invoke(null,
				world, donationPos, donatedItems);

			if (donationResult == null) {
				return new DonationOutcome(donatedItems, 0);
			}

			// DonationResult record: accepted(), rejected() -> List<ItemStack>; overflowLost() -> int
			Method acceptedMethod = donationResult.getClass().getMethod("accepted");
			Method rejectedMethod = donationResult.getClass().getMethod("rejected");
			Method overflowLostMethod = donationResult.getClass().getMethod("overflowLost");

			@SuppressWarnings("unchecked")
			List<ItemStack> accepted = (List<ItemStack>) acceptedMethod.invoke(donationResult);
			@SuppressWarnings("unchecked")
			List<ItemStack> rejected = (List<ItemStack>) rejectedMethod.invoke(donationResult);
			int overflowLost = (int) overflowLostMethod.invoke(donationResult);

			if (accepted != null && !accepted.isEmpty()) {
				int totalAccepted = accepted.stream().mapToInt(ItemStack::getCount).sum();
				LOGGER.info("Village Builder accepted {} items for construction", totalAccepted);
			}

			if (overflowLost > 0) {
				LOGGER.warn("Village Builder reported {} donated building-material items lost to inventory overflow", overflowLost);
			}

			return new DonationOutcome(rejected != null ? rejected : new ArrayList<>(), overflowLost);

		} catch (Exception e) {
			LOGGER.error("Failed to process donation through Village Builder: {}", e.getMessage());
			return new DonationOutcome(donatedItems, 0);
		}
	}

	/**
	 * Get construction status text from Village Builder.
	 *
	 * @return Component describing current construction, or null if none
	 */
	public static Component getConstructionStatus(ServerLevel world, BlockPos villagePos) {
		initReflection();
		if (!available) {
			return null;
		}

		try {
			return (Component) cachedGetConstructionStatusMethod.invoke(null, world, villagePos);
		} catch (Exception e) {
			return null;
		}
	}
}
