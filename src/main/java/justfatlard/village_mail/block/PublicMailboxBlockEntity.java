package justfatlard.village_mail.block;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.pandorical.MailScreens;

public class PublicMailboxBlockEntity extends BlockEntity {

	public PublicMailboxBlockEntity(BlockPos pos, BlockState state) {
		super(Main.PUBLIC_MAILBOX_BLOCK_ENTITY, pos, state);
	}

	public Component getDisplayName() {
		return Component.translatable("block.village-mail.public_mailbox");
	}

	public void openGui(ServerPlayer player) {
		MailScreens.openPublicMailbox(player, this.worldPosition);
	}
}
