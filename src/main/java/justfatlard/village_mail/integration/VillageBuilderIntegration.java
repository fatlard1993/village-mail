package justfatlard.village_mail.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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

	// Cached reflection references — loaded once, reused forever
	private static boolean reflectionInitialized = false;
	private static boolean available = false;

	// VillageBuilderAPI methods
	private static Method cachedRegisterTemplatePersistentMethod;  // (Identifier, String, Set<VillageNeed>, List<MaterialRequirement>, Set<String>, int)
	private static Method cachedProcessDonatedMaterialsMethod;     // (ServerWorld, BlockPos, List<ItemStack>) -> DonationResult
	private static Method cachedGetConstructionStatusMethod;       // (ServerWorld, BlockPos) -> Text

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

			// registerTemplatePersistent(Identifier, String, Set<VillageNeed>, List<MaterialRequirement>, Set<String>, int)
			cachedRegisterTemplatePersistentMethod = apiClass.getMethod("registerTemplatePersistent",
				Identifier.class, String.class, Set.class, List.class, Set.class, int.class);

			// processDonatedMaterials(ServerWorld, BlockPos, List<ItemStack>) -> DonationResult
			cachedProcessDonatedMaterialsMethod = apiClass.getMethod("processDonatedMaterials",
				ServerWorld.class, BlockPos.class, List.class);

			// getConstructionStatus(ServerWorld, BlockPos) -> Text
			cachedGetConstructionStatusMethod = apiClass.getMethod("getConstructionStatus",
				ServerWorld.class, BlockPos.class);

			available = true;
			LOGGER.info("Village Builder integration available");
		} catch (ClassNotFoundException e) {
			LOGGER.error("Village Builder API not found (wrong version?): {}", e.getMessage());
		} catch (Exception e) {
			LOGGER.error("Failed to initialize Village Builder reflection: {}", e.getMessage(), e);
		}
	}

	/**
	 * Register village-mail structures with Village Builder.
	 *
	 * Uses registerTemplatePersistent() — material requirements are auto-derived
	 * from the NBT block composition at world load.  Fallback requirements are
	 * provided in case template analysis fails.
	 *
	 * Each biome variant is registered with its biome preference so the builder
	 * picks the right style for the village.
	 */
	public static void registerStructures() {
		initReflection();
		if (!available) return;

		LOGGER.info("Village Builder found, registering post office structures...");

		try {
			// Fallback materials for post offices (used if NBT analysis fails)
			List<Object> postOfficeFallback = List.of(
				cachedMaterialReqConstructor.newInstance(Items.STONE, 160),
				cachedMaterialReqConstructor.newInstance(Items.OAK_LOG, 80),
				cachedMaterialReqConstructor.newInstance(Items.OAK_PLANKS, 64),
				cachedMaterialReqConstructor.newInstance(Items.GLASS, 32),
				cachedMaterialReqConstructor.newInstance(Items.IRON_INGOT, 16)
			);

			// Register biome-specific post offices
			// PROFESSION (provides mail person workstation) + PROSPERITY (community building)
			Set<Object> postOfficeNeeds = Set.of(cachedNeedProfession, cachedNeedProsperity);

			String[] biomes = {"plains", "desert", "savanna", "taiga", "snowy"};
			for (String biome : biomes) {
				cachedRegisterTemplatePersistentMethod.invoke(null,
					Identifier.of("village-mail", "post_office_" + biome),
					formatName(biome) + " Post Office",
					postOfficeNeeds,
					postOfficeFallback,
					Set.of(biome),
					5  // clearance size
				);
				LOGGER.info("Registered {} post office (biome: {})", biome, biome);
			}

			// Fallback materials for public mailbox
			List<Object> mailboxFallback = List.of(
				cachedMaterialReqConstructor.newInstance(Items.OAK_LOG, 12),
				cachedMaterialReqConstructor.newInstance(Items.IRON_INGOT, 4)
			);

			// Public mailbox — PROSPERITY need (village atmosphere), any biome
			cachedRegisterTemplatePersistentMethod.invoke(null,
				Identifier.of("village-mail", "public_mailbox"),
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
	 * Process donated items through the Village Builder system.
	 * Items needed for construction are accepted; the rest are returned.
	 *
	 * The API returns a DonationResult record with accepted/rejected lists.
	 * We extract the rejected list as "remaining items."
	 */
	public static List<ItemStack> processDonation(ServerWorld world, BlockPos donationPos, List<ItemStack> donatedItems) {
		initReflection();
		if (!available) {
			return donatedItems;
		}

		try {
			// processDonatedMaterials(ServerWorld, BlockPos, List<ItemStack>) -> DonationResult
			Object donationResult = cachedProcessDonatedMaterialsMethod.invoke(null,
				world, donationPos, donatedItems);

			if (donationResult == null) {
				return donatedItems;
			}

			// DonationResult has: accepted(), rejected(), overflowLost()
			Method acceptedMethod = donationResult.getClass().getMethod("accepted");
			Method rejectedMethod = donationResult.getClass().getMethod("rejected");

			@SuppressWarnings("unchecked")
			List<ItemStack> accepted = (List<ItemStack>) acceptedMethod.invoke(donationResult);
			@SuppressWarnings("unchecked")
			List<ItemStack> rejected = (List<ItemStack>) rejectedMethod.invoke(donationResult);

			if (accepted != null && !accepted.isEmpty()) {
				int totalAccepted = accepted.stream().mapToInt(ItemStack::getCount).sum();
				LOGGER.info("Village Builder accepted {} items for construction", totalAccepted);
			}

			return rejected != null ? rejected : new ArrayList<>();

		} catch (Exception e) {
			LOGGER.error("Failed to process donation through Village Builder: {}", e.getMessage());
			return donatedItems;
		}
	}

	/**
	 * Get construction status text from Village Builder.
	 *
	 * @return Text describing current construction, or null if none
	 */
	public static Text getConstructionStatus(ServerWorld world, BlockPos villagePos) {
		initReflection();
		if (!available) {
			return null;
		}

		try {
			return (Text) cachedGetConstructionStatusMethod.invoke(null, world, villagePos);
		} catch (Exception e) {
			return null;
		}
	}
}
