package justfatlard.village_mail.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

import justfatlard.village_mail.Main;
import justfatlard.village_mail.network.MailNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Screen handler for the message list screen.
 * Unlike traditional inventory screens, this doesn't use slots.
 * Message data flows through network packets defined in MailPayloads.
 */
public class MessageListScreenHandler extends ScreenHandler {

	// Client constructor
	public MessageListScreenHandler(int syncId, PlayerInventory playerInventory) {
		this(syncId, playerInventory, null);
	}

	// Server constructor
	public MessageListScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos mailboxPos) {
		super(Main.MAILBOX_SCREEN_HANDLER, syncId);

		// Send initial message list to client
		if (playerInventory.player instanceof ServerPlayerEntity serverPlayer) {
			MailNetworking.sendMessageList(serverPlayer, false);
		}
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		// Messages are per-player, not tied to block proximity.
		// The mailbox is just the entry point — reading mail doesn't require standing next to it.
		return true;
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) {
		// No slots to transfer
		return ItemStack.EMPTY;
	}
}
