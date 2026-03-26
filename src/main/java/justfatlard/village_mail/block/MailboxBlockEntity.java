package justfatlard.village_mail.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.mail.PlayerMailStorage;
import justfatlard.village_mail.screen.MessageListScreenHandler;

public class MailboxBlockEntity extends BlockEntity implements NamedScreenHandlerFactory {
	private UUID ownerUuid = null;
	private String ownerName = "Village";

	public MailboxBlockEntity(BlockPos pos, BlockState state) {
		super(Main.MAILBOX_BLOCK_ENTITY, pos, state);
	}

	@Override
	protected void writeData(WriteView view) {
		super.writeData(view);
		if (ownerUuid != null) {
			view.putString("OwnerUuid", ownerUuid.toString());
			view.putString("OwnerName", ownerName);
		}
	}

	@Override
	protected void readData(ReadView view) {
		super.readData(view);
		String ownerUuidStr = view.getString("OwnerUuid", null);
		if (ownerUuidStr != null) {
			try {
				ownerUuid = UUID.fromString(ownerUuidStr);
				ownerName = view.getString("OwnerName", "Unknown");
			} catch (IllegalArgumentException e) {
				ownerUuid = null;
				ownerName = "Village";
			}
		}
	}

	public UUID getOwnerUuid() { return ownerUuid; }
	public String getOwnerName() { return ownerName; }

	public void setOwner(ServerPlayerEntity player) {
		this.ownerUuid = player.getUuid();
		this.ownerName = player.getName().getString();
		markDirty();
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("village-mail.screen.mailbox_title", ownerName);
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new MessageListScreenHandler(syncId, playerInventory, this.pos);
	}

	public void openGui(ServerPlayerEntity player) {
		// Only the owner can access their personal mailbox
		if (ownerUuid != null && !ownerUuid.equals(player.getUuid())) {
			player.sendMessage(Text.translatable("village-mail.screen.mailbox_belongs_to", ownerName), true);
			return;
		}
		// Register this mailbox as the player's active mailbox (updates location + delivers pending)
		if (ownerUuid != null) {
			PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
			String dimension = player.getEntityWorld().getRegistryKey().getValue().toString();
			storage.registerMailbox(player.getUuid(), dimension, this.pos);
		}
		player.openHandledScreen(this);
	}

}
