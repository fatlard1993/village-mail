package justfatlard.village_mail.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.screen.PublicMailboxScreenHandler;

public class PublicMailboxBlockEntity extends BlockEntity implements NamedScreenHandlerFactory {

	public PublicMailboxBlockEntity(BlockPos pos, BlockState state) {
		super(Main.PUBLIC_MAILBOX_BLOCK_ENTITY, pos, state);
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("block.village-mail.public_mailbox");
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		return new PublicMailboxScreenHandler(syncId, playerInventory, ScreenHandlerContext.create(this.world, this.pos));
	}

	public void openGui(ServerPlayerEntity player) {
		player.openHandledScreen(this);
	}
}
