package justfatlard.village_mail.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

import justfatlard.village_mail.Main;

/**
 * Screen handler for the public mailbox compose screen.
 * Has a single attachment slot for sending one item with a message.
 */
public class PublicMailboxScreenHandler extends ScreenHandler {
	private final Inventory attachmentInventory;
	private final ScreenHandlerContext context;
	private boolean attachmentSent = false;

	// Client constructor
	public PublicMailboxScreenHandler(int syncId, PlayerInventory playerInventory) {
		this(syncId, playerInventory, ScreenHandlerContext.EMPTY);
	}

	// Server constructor
	public PublicMailboxScreenHandler(int syncId, PlayerInventory playerInventory, ScreenHandlerContext context) {
		super(Main.PUBLIC_MAILBOX_SCREEN_HANDLER, syncId);
		this.attachmentInventory = new SimpleInventory(1);
		this.context = context;

		// Single attachment slot — centered
		this.addSlot(new Slot(attachmentInventory, 0, 80, 58));

		// Player inventory
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
			}
		}

		// Player hotbar
		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
		}
	}

	/**
	 * Get the block position of the public mailbox from the screen handler context.
	 */
	public BlockPos getBlockPos() {
		return context.get((world, pos) -> pos).orElse(null);
	}

	/**
	 * Mark that the attachment was consumed by a SendMessageC2S or DonateC2S packet.
	 * Prevents onClosed() from returning the item to the player.
	 */
	public void markAttachmentSent() {
		this.attachmentSent = true;
	}

	/**
	 * Atomically take the attachment from slot 0 if present.
	 * Returns the item that was in the slot, or EMPTY if already taken.
	 * Prevents race between SendMessageC2S and DonateC2S on the same slot.
	 */
	public synchronized ItemStack takeAttachment() {
		ItemStack slotItem = getSlot(0).getStack();
		if (slotItem.isEmpty()) return ItemStack.EMPTY;
		ItemStack taken = slotItem.copy();
		getSlot(0).setStack(ItemStack.EMPTY);
		attachmentSent = true;
		return taken;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return canUse(this.context, player, Main.PUBLIC_MAILBOX_BLOCK);
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int slotIndex) {
		ItemStack newStack = ItemStack.EMPTY;
		Slot slot = this.slots.get(slotIndex);

		if (slot != null && slot.hasStack()) {
			ItemStack originalStack = slot.getStack();
			newStack = originalStack.copy();

			if (slotIndex == 0) {
				// Moving from attachment slot to player inventory
				if (!this.insertItem(originalStack, 1, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else {
				// Moving from player inventory to attachment slot
				if (!this.insertItem(originalStack, 0, 1, false)) {
					return ItemStack.EMPTY;
				}
			}

			if (originalStack.isEmpty()) {
				slot.setStack(ItemStack.EMPTY);
			} else {
				slot.markDirty();
			}
		}

		return newStack;
	}

	@Override
	public void onClosed(PlayerEntity player) {
		super.onClosed(player);

		// Return attachment item to player if not consumed by send
		if (!attachmentSent) {
			ItemStack attachment = attachmentInventory.getStack(0);
			if (!attachment.isEmpty()) {
				if (!player.getInventory().insertStack(attachment.copy())) {
					player.dropItem(attachment.copy(), false);
				}
				attachmentInventory.setStack(0, ItemStack.EMPTY);
			}
		}
	}
}
