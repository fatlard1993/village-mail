package justfatlard.village_mail.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import justfatlard.village_mail.mail.PlayerMailStorage;

public class MailboxBlock extends BlockWithEntity {
	public static final MapCodec<MailboxBlock> CODEC = createCodec(MailboxBlock::new);
	public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

	// Post (4x8 center) + mailbox on top (8x6x6)
	private static final VoxelShape POST = Block.createCuboidShape(6, 0, 6, 10, 8, 10);
	private static final VoxelShape BOX = Block.createCuboidShape(4, 8, 4, 12, 14, 12);
	private static final VoxelShape SHAPE = VoxelShapes.union(POST, BOX);

	public MailboxBlock(Settings settings) {
		super(settings);
		setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, BlockRotation rotation) {
		return state.with(FACING, rotation.rotate(state.get(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, BlockMirror mirror) {
		return state.rotate(mirror.getRotation(state.get(FACING)));
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new MailboxBlockEntity(pos, state);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		// When placed by a player, set ownership and register location
		if (!world.isClient() && placer instanceof ServerPlayerEntity player) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof MailboxBlockEntity mailbox) {
				mailbox.setOwner(player);
				// Register mailbox location for delivery
				String dimension = world.getRegistryKey().getValue().toString();
				PlayerMailStorage storage = PlayerMailStorage.get(player.getEntityWorld().getServer());
				storage.registerMailbox(player.getUuid(), dimension, pos);
			}
		}
	}

	@Override
	protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
		// Unregister the mailbox location when broken
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity instanceof MailboxBlockEntity mailbox && mailbox.getOwnerUuid() != null) {
			PlayerMailStorage storage = PlayerMailStorage.get(world.getServer());
			storage.unregisterMailbox(mailbox.getOwnerUuid(), pos);
		}
		super.onStateReplaced(state, world, pos, moved);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof MailboxBlockEntity mailbox) {
				mailbox.openGui(serverPlayer);
			}
		}
		return ActionResult.SUCCESS;
	}
}
