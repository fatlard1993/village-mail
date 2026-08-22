package justfatlard.village_mail;

import justfatlard.pandorical.api.BlockRegistration;
import justfatlard.pandorical.api.ItemRegistration;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.entity.npc.villager.Villager;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PoiHelper;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import java.util.Set;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import com.google.common.collect.ImmutableSet;

import justfatlard.village_mail.block.MailboxBlock;
import justfatlard.village_mail.block.MailboxBlockEntity;
import justfatlard.village_mail.block.PublicMailboxBlock;
import justfatlard.village_mail.block.PublicMailboxBlockEntity;
import justfatlard.village_mail.mail.MailDeliveryManager;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.pandorical.MailHud;
import justfatlard.village_mail.pandorical.MailScreens;
import justfatlard.village_mail.integration.VillageBuilderIntegration;
import justfatlard.village_mail.integration.VillageQuestsIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("village-mail");
	public static final String MOD_ID = "village-mail";

	public static final Identifier MAILBOX_ID = Identifier.fromNamespaceAndPath(MOD_ID, "mailbox");
	public static final Identifier PUBLIC_MAILBOX_ID = Identifier.fromNamespaceAndPath(MOD_ID, "public_mailbox");
	public static final Identifier MAIL_PERSON_ID = Identifier.fromNamespaceAndPath(MOD_ID, "mail_person");

	public static final ResourceKey<Block> MAILBOX_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, MAILBOX_ID);
	public static final ResourceKey<Item> MAILBOX_ITEM_KEY = ResourceKey.create(Registries.ITEM, MAILBOX_ID);
	public static final ResourceKey<Block> PUBLIC_MAILBOX_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, PUBLIC_MAILBOX_ID);
	public static final ResourceKey<Item> PUBLIC_MAILBOX_ITEM_KEY = ResourceKey.create(Registries.ITEM, PUBLIC_MAILBOX_ID);
	public static final ResourceKey<PoiType> PUBLIC_MAILBOX_POI_KEY = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, PUBLIC_MAILBOX_ID);
	public static final ResourceKey<VillagerProfession> MAIL_PERSON_KEY = ResourceKey.create(Registries.VILLAGER_PROFESSION, MAIL_PERSON_ID);
	public static final ResourceKey<CreativeModeTab> ITEM_GROUP_KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(MOD_ID, "village_mail"));

	// Personal mailbox: player-owned, not a job site
	public static final MailboxBlock MAILBOX_BLOCK = new MailboxBlock(
		BlockBehaviour.Properties.of()
			.setId(MAILBOX_BLOCK_KEY)
			.strength(2.5f)
			.noOcclusion()
	);

	// Public mailbox: primary job site for the mail person
	public static final PublicMailboxBlock PUBLIC_MAILBOX_BLOCK = new PublicMailboxBlock(
		BlockBehaviour.Properties.of()
			.setId(PUBLIC_MAILBOX_BLOCK_KEY)
			.strength(3.0f)
			.noOcclusion()
	);

	public static BlockEntityType<MailboxBlockEntity> MAILBOX_BLOCK_ENTITY;
	public static BlockEntityType<PublicMailboxBlockEntity> PUBLIC_MAILBOX_BLOCK_ENTITY;

	public static final BlockItem MAILBOX_ITEM = new BlockItem(
		MAILBOX_BLOCK,
		new Item.Properties().setId(MAILBOX_ITEM_KEY)
	);
	public static final BlockItem PUBLIC_MAILBOX_ITEM = new BlockItem(
		PUBLIC_MAILBOX_BLOCK,
		new Item.Properties().setId(PUBLIC_MAILBOX_ITEM_KEY)
	);

	public static PoiType PUBLIC_MAILBOX_POI;

	public static VillagerProfession MAIL_PERSON;

	public static final MailDeliveryManager MAIL_MANAGER = new MailDeliveryManager();

	@Override
	public void onInitialize() {
		// Mirror the mailboxes into Pandorical's content registry. Both use the default
		// stone-sounding Properties.of(), so the client copies stone to stay in step.
		PandoricalApi.content().registerBlock(MOD_ID + ":mailbox", new BlockRegistration()
			.baseBlock("minecraft:stone")
			.property("facing")
			.interactive()
			.model(MOD_ID + ":block/mailbox"));
		PandoricalApi.content().registerBlock(MOD_ID + ":public_mailbox", new BlockRegistration()
			.baseBlock("minecraft:stone")
			.property("facing")
			.interactive()
			.model(MOD_ID + ":block/public_mailbox"));
		PandoricalApi.content().registerItem(MOD_ID + ":mailbox", new ItemRegistration()
			.model(MOD_ID + ":block/mailbox"));
		PandoricalApi.content().registerItem(MOD_ID + ":public_mailbox", new ItemRegistration()
			.model(MOD_ID + ":block/public_mailbox"));
		PandoricalApi.content().registerModAssets(MOD_ID);

		Registry.register(BuiltInRegistries.BLOCK, MAILBOX_ID, MAILBOX_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, PUBLIC_MAILBOX_ID, PUBLIC_MAILBOX_BLOCK);

		MAILBOX_BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			MAILBOX_ID,
			FabricBlockEntityTypeBuilder.create(MailboxBlockEntity::new, MAILBOX_BLOCK).build()
		);
		PUBLIC_MAILBOX_BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			PUBLIC_MAILBOX_ID,
			FabricBlockEntityTypeBuilder.create(PublicMailboxBlockEntity::new, PUBLIC_MAILBOX_BLOCK).build()
		);

		Registry.register(BuiltInRegistries.ITEM, MAILBOX_ID, MAILBOX_ITEM);
		Registry.register(BuiltInRegistries.ITEM, PUBLIC_MAILBOX_ID, PUBLIC_MAILBOX_ITEM);

		PUBLIC_MAILBOX_POI = PoiHelper.register(
			PUBLIC_MAILBOX_ID,
			1,  // ticket count (villagers that can use it)
			48, // search distance (same as vanilla workstations)
			PUBLIC_MAILBOX_BLOCK
		);
		LOGGER.info("Registered POI: " + PUBLIC_MAILBOX_ID);

		MAIL_PERSON = Registry.register(
			BuiltInRegistries.VILLAGER_PROFESSION,
			MAIL_PERSON_ID,
			new VillagerProfession(
				Component.translatable("entity.minecraft.villager." + MOD_ID + ".mail_person"),
				entry -> entry.is(PUBLIC_MAILBOX_POI_KEY),
				entry -> entry.is(PUBLIC_MAILBOX_POI_KEY),
				ImmutableSet.of(),
				ImmutableSet.of(),
				SoundEvents.VILLAGER_WORK_LIBRARIAN,
				buildTradeTable()
			)
		);
		LOGGER.info("Registered profession: " + MAIL_PERSON_ID + " with POI: " + PUBLIC_MAILBOX_POI);

		ServerTickEvents.END_SERVER_TICK.register(MAIL_MANAGER::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> MAIL_MANAGER.reset());

		// Zombification runs after the death event, so the obituary has to be told
		// the "dead" villager is coming back. See MailDeliveryManager.convertedNotDead.
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, params) -> {
			if (previous instanceof Villager villager) {
				MAIL_MANAGER.noteConverted(villager.getUUID());
			}
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof Villager villager && !villager.level().isClientSide()) {
				var server = villager.level().getServer();
				if (server != null) {
					MAIL_MANAGER.sendObituaries(villager, damageSource, server);
				}
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// Pandorical's capability handshake (HelloC2S) completes shortly after JOIN,
			// not before; a short delay avoids a no-op HUD push racing the handshake.
			var joiningPlayer = handler.getPlayer();
			MAIL_MANAGER.scheduleDelayed(server, 20, () -> MailHud.updateUnreadCount(joiningPlayer));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			MailScreens.onPlayerDisconnect(handler.getPlayer().getUUID());
			MailHud.onPlayerDisconnect(handler.getPlayer().getUUID());
		});

		// Registration order matters:
		// 1. PlayerMailStorage first: screen action handlers call PlayerMailStorage.get()
		// 2. MailScreens handlers before any screen is opened
		// 3. VillageStructureInjector before world gen
		// 4. VQ init after MailScreens: VQ's MailSystemIntegration may send mail back
		PlayerMailStorage.init();
		justfatlard.village_mail.mail.VillageBulletin.init();

		MailScreens.registerHandlers();

		VillageStructureInjector.register();

		VillageBuilderIntegration.registerStructures();

		VillageQuestsIntegration.init();

		CreativeModeTab mailGroup = FabricCreativeModeTab.builder()
			.icon(() -> new ItemStack(PUBLIC_MAILBOX_ITEM))
			.title(Component.translatable("itemGroup.village-mail.village_mail"))
			.displayItems((context, entries) -> {
				entries.accept(new ItemStack(PUBLIC_MAILBOX_ITEM));
				entries.accept(new ItemStack(MAILBOX_ITEM));
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ITEM_GROUP_KEY, mailGroup);

		LOGGER.info("Loaded Village Mail mod");
	}

	// Trades are data-driven: each level's ResourceKey<TradeSet> resolves from
	// data/village-mail/trade_set/mail_person/level_N.json, which pulls
	// villager_trade/mail_person/N/*.json entries via the matching tag file.
	private static Int2ObjectMap<ResourceKey<TradeSet>> buildTradeTable() {
		Int2ObjectMap<ResourceKey<TradeSet>> map = new Int2ObjectOpenHashMap<>();
		for (int level = 1; level <= 5; level++) {
			map.put(level, ResourceKey.create(
				Registries.TRADE_SET,
				Identifier.fromNamespaceAndPath(MOD_ID, "mail_person/level_" + level)
			));
		}
		return map;
	}
}
