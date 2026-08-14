package justfatlard.village_mail.block;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.UUID;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.pandorical.MailScreens;

public class MailboxBlockEntity extends BlockEntity {
	private UUID ownerUuid = null;
	private String ownerName = "Village";

	public MailboxBlockEntity(BlockPos pos, BlockState state) {
		super(Main.MAILBOX_BLOCK_ENTITY, pos, state);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (ownerUuid != null) {
			output.putString("OwnerUuid", ownerUuid.toString());
			output.putString("OwnerName", ownerName);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		String ownerUuidStr = input.getString("OwnerUuid").orElse(null);
		if (ownerUuidStr != null) {
			try {
				ownerUuid = UUID.fromString(ownerUuidStr);
				ownerName = input.getStringOr("OwnerName", "Unknown");
			} catch (IllegalArgumentException e) {
				ownerUuid = null;
				ownerName = "Village";
			}
		}
	}

	public UUID getOwnerUuid() { return ownerUuid; }
	public String getOwnerName() { return ownerName; }

	public void setOwner(ServerPlayer player) {
		this.ownerUuid = player.getUUID();
		this.ownerName = player.getName().getString();
		setChanged();
	}

	public Component getDisplayName() {
		return Component.translatable("village-mail.screen.mailbox_title", ownerName);
	}

	public void openGui(ServerPlayer player) {
		// Only the owner can access their personal mailbox
		if (ownerUuid != null && !ownerUuid.equals(player.getUUID())) {
			player.sendSystemMessage(Component.translatable("village-mail.screen.mailbox_belongs_to", ownerName), true);
			return;
		}
		// Register this mailbox as the player's active mailbox (updates location + delivers pending)
		if (ownerUuid != null) {
			PlayerMailStorage storage = PlayerMailStorage.get(player.level().getServer());
			String dimension = player.level().dimension().identifier().toString();
			storage.registerMailbox(player.getUUID(), dimension, this.worldPosition);
		}
		MailScreens.openMailbox(player, ownerName);
	}

}
