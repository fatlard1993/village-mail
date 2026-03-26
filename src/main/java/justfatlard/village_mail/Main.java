package justfatlard.village_mail;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.passive.VillagerEntity;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestType;

import com.google.common.collect.ImmutableSet;

import justfatlard.village_mail.block.MailboxBlock;
import justfatlard.village_mail.block.MailboxBlockEntity;
import justfatlard.village_mail.block.PublicMailboxBlock;
import justfatlard.village_mail.block.PublicMailboxBlockEntity;
import justfatlard.village_mail.mail.MailDeliveryManager;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.network.MailNetworking;
import justfatlard.village_mail.screen.MessageListScreenHandler;
import justfatlard.village_mail.screen.PublicMailboxScreenHandler;
import justfatlard.village_mail.integration.VillageBuilderIntegration;
import justfatlard.village_mail.integration.VillageQuestsIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	public static final String MOD_ID = "village-mail";

	// Identifiers
	public static final Identifier MAILBOX_ID = Identifier.of(MOD_ID, "mailbox");
	public static final Identifier PUBLIC_MAILBOX_ID = Identifier.of(MOD_ID, "public_mailbox");
	public static final Identifier MAIL_PERSON_ID = Identifier.of(MOD_ID, "mail_person");

	// Registry Keys
	public static final RegistryKey<Block> MAILBOX_BLOCK_KEY = RegistryKey.of(RegistryKeys.BLOCK, MAILBOX_ID);
	public static final RegistryKey<Item> MAILBOX_ITEM_KEY = RegistryKey.of(RegistryKeys.ITEM, MAILBOX_ID);
	public static final RegistryKey<Block> PUBLIC_MAILBOX_BLOCK_KEY = RegistryKey.of(RegistryKeys.BLOCK, PUBLIC_MAILBOX_ID);
	public static final RegistryKey<Item> PUBLIC_MAILBOX_ITEM_KEY = RegistryKey.of(RegistryKeys.ITEM, PUBLIC_MAILBOX_ID);
	public static final RegistryKey<PointOfInterestType> PUBLIC_MAILBOX_POI_KEY = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, PUBLIC_MAILBOX_ID);
	public static final RegistryKey<VillagerProfession> MAIL_PERSON_KEY = RegistryKey.of(RegistryKeys.VILLAGER_PROFESSION, MAIL_PERSON_ID);
	public static final RegistryKey<ItemGroup> ITEM_GROUP_KEY = RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(MOD_ID, "village_mail"));

	// Block - Personal Mailbox (player-owned, not a job site)
	public static final MailboxBlock MAILBOX_BLOCK = new MailboxBlock(
		AbstractBlock.Settings.create()
			.registryKey(MAILBOX_BLOCK_KEY)
			.strength(2.5f)
			.nonOpaque()
	);

	// Block - Public Mailbox (primary job site for Mail Person)
	public static final PublicMailboxBlock PUBLIC_MAILBOX_BLOCK = new PublicMailboxBlock(
		AbstractBlock.Settings.create()
			.registryKey(PUBLIC_MAILBOX_BLOCK_KEY)
			.strength(3.0f)
			.nonOpaque()
	);

	// Block Entity Types
	public static BlockEntityType<MailboxBlockEntity> MAILBOX_BLOCK_ENTITY;
	public static BlockEntityType<PublicMailboxBlockEntity> PUBLIC_MAILBOX_BLOCK_ENTITY;

	public static final BlockItem MAILBOX_ITEM = new BlockItem(
		MAILBOX_BLOCK,
		new Item.Settings().registryKey(MAILBOX_ITEM_KEY)
	);
	public static final BlockItem PUBLIC_MAILBOX_ITEM = new BlockItem(
		PUBLIC_MAILBOX_BLOCK,
		new Item.Settings().registryKey(PUBLIC_MAILBOX_ITEM_KEY)
	);

	// POI - Public Mailbox is the primary workstation
	public static PointOfInterestType PUBLIC_MAILBOX_POI;

	// Profession
	public static VillagerProfession MAIL_PERSON;

	// Mail system
	public static final MailDeliveryManager MAIL_MANAGER = new MailDeliveryManager();

	// Screen handlers
	public static ScreenHandlerType<MessageListScreenHandler> MAILBOX_SCREEN_HANDLER;
	public static ScreenHandlerType<PublicMailboxScreenHandler> PUBLIC_MAILBOX_SCREEN_HANDLER;

	@Override
	public void onInitialize() {
		Registry.register(Registries.BLOCK, MAILBOX_ID, MAILBOX_BLOCK);
		Registry.register(Registries.BLOCK, PUBLIC_MAILBOX_ID, PUBLIC_MAILBOX_BLOCK);

		MAILBOX_BLOCK_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			MAILBOX_ID,
			FabricBlockEntityTypeBuilder.create(MailboxBlockEntity::new, MAILBOX_BLOCK).build()
		);
		PUBLIC_MAILBOX_BLOCK_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			PUBLIC_MAILBOX_ID,
			FabricBlockEntityTypeBuilder.create(PublicMailboxBlockEntity::new, PUBLIC_MAILBOX_BLOCK).build()
		);

		Registry.register(Registries.ITEM, MAILBOX_ID, MAILBOX_ITEM);
		Registry.register(Registries.ITEM, PUBLIC_MAILBOX_ID, PUBLIC_MAILBOX_ITEM);

		MAILBOX_SCREEN_HANDLER = Registry.register(
			Registries.SCREEN_HANDLER,
			Identifier.of(MOD_ID, "mailbox"),
			new ScreenHandlerType<>(MessageListScreenHandler::new, FeatureFlags.VANILLA_FEATURES)
		);
		PUBLIC_MAILBOX_SCREEN_HANDLER = Registry.register(
			Registries.SCREEN_HANDLER,
			Identifier.of(MOD_ID, "public_mailbox"),
			new ScreenHandlerType<>(PublicMailboxScreenHandler::new, FeatureFlags.VANILLA_FEATURES)
		);

		PUBLIC_MAILBOX_POI = PointOfInterestHelper.register(
			PUBLIC_MAILBOX_ID,
			1,  // ticket count (villagers that can use it)
			48, // search distance (same as vanilla workstations)
			PUBLIC_MAILBOX_BLOCK
		);
		LOGGER.info("Registered POI: " + PUBLIC_MAILBOX_ID);

		MAIL_PERSON = Registry.register(
			Registries.VILLAGER_PROFESSION,
			MAIL_PERSON_ID,
			new VillagerProfession(
				Text.translatable("entity.minecraft.villager." + MOD_ID + ".mail_person"),
				entry -> entry.matchesKey(PUBLIC_MAILBOX_POI_KEY),
				entry -> entry.matchesKey(PUBLIC_MAILBOX_POI_KEY),
				ImmutableSet.of(),
				ImmutableSet.of(),
				SoundEvents.ENTITY_VILLAGER_WORK_LIBRARIAN
			)
		);
		LOGGER.info("Registered profession: " + MAIL_PERSON_ID + " with POI: " + PUBLIC_MAILBOX_POI);

		registerTrades();

		ServerTickEvents.END_SERVER_TICK.register(MAIL_MANAGER::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> MAIL_MANAGER.reset());

		// Villager obituaries — when a villager dies, notify nearby mailbox owners
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof VillagerEntity villager && !villager.getEntityWorld().isClient()) {
				var server = villager.getEntityWorld().getServer();
				if (server != null) {
					MAIL_MANAGER.sendObituaries(villager, damageSource, server);
				}
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			MailNetworking.sendUnreadCount(handler.getPlayer());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			MailNetworking.onPlayerDisconnect(handler.getPlayer().getUuid());
		});

		// Registration order matters:
		// 1. PlayerMailStorage first — packet handlers call PlayerMailStorage.get()
		// 2. MailNetworking payloads before handlers — registration before use
		// 3. VillageStructureInjector before world gen
		// 4. VQ init after networking — VQ's MailSystemIntegration may send mail back
		PlayerMailStorage.init();

		MailNetworking.registerPayloads();
		MailNetworking.registerServerHandlers();

		VillageStructureInjector.register();

		VillageBuilderIntegration.registerStructures();

		VillageQuestsIntegration.init();

		ItemGroup mailGroup = FabricItemGroup.builder()
			.icon(() -> new ItemStack(PUBLIC_MAILBOX_ITEM))
			.displayName(Text.translatable("itemGroup.village-mail.village_mail"))
			.entries((context, entries) -> {
				entries.add(new ItemStack(PUBLIC_MAILBOX_ITEM));
				entries.add(new ItemStack(MAILBOX_ITEM));
			})
			.build();
		Registry.register(Registries.ITEM_GROUP, ITEM_GROUP_KEY, mailGroup);

		LOGGER.info("Loaded Village Mail mod");
	}

	private void registerTrades() {
		LOGGER.info("Registering trades for profession: " + MAIL_PERSON_KEY);

		// Level 1 trades
		TradeOfferHelper.registerVillagerOffers(MAIL_PERSON_KEY, 1, factories -> {
			// Buy paper for emeralds
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.PAPER, 24),
				new ItemStack(Items.EMERALD),
				16, 2, 0.05f
			));
			// Sell mailbox for emeralds
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.EMERALD, 5),
				new ItemStack(MAILBOX_ITEM),
				12, 2, 0.05f
			));
		});

		// Level 2 trades
		TradeOfferHelper.registerVillagerOffers(MAIL_PERSON_KEY, 2, factories -> {
			// Buy books
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.BOOK, 4),
				new ItemStack(Items.EMERALD),
				12, 10, 0.05f
			));
			// Sell map
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.EMERALD, 7),
				new ItemStack(Items.MAP),
				12, 5, 0.05f
			));
		});

		// Level 3 trades
		TradeOfferHelper.registerVillagerOffers(MAIL_PERSON_KEY, 3, factories -> {
			// Buy ink sacs
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.INK_SAC, 5),
				new ItemStack(Items.EMERALD),
				12, 20, 0.05f
			));
			// Sell name tags
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.EMERALD, 15),
				new ItemStack(Items.NAME_TAG),
				6, 15, 0.05f
			));
		});

		// Level 4 trades
		TradeOfferHelper.registerVillagerOffers(MAIL_PERSON_KEY, 4, factories -> {
			// Buy feathers
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.FEATHER, 24),
				new ItemStack(Items.EMERALD),
				16, 30, 0.05f
			));
			// Sell ender pearl (express delivery!)
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.EMERALD, 12),
				new ItemStack(Items.ENDER_PEARL),
				8, 20, 0.05f
			));
		});

		// Level 5 trades (Master)
		TradeOfferHelper.registerVillagerOffers(MAIL_PERSON_KEY, 5, factories -> {
			// Sell recovery compass
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.EMERALD, 24),
				new ItemStack(Items.RECOVERY_COMPASS),
				3, 30, 0.05f
			));
			// Buy echo shards
			factories.add((world, entity, random) -> new TradeOffer(
				new TradedItem(Items.ECHO_SHARD, 1),
				new ItemStack(Items.EMERALD, 8),
				8, 30, 0.05f
			));
		});
	}
}
