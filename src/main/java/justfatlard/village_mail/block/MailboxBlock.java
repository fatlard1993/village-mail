package justfatlard.village_mail.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import justfatlard.village_mail.mail.PlayerMailStorage;

public class MailboxBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	// Post (4x8 center) + mailbox on top (8x6x6)
	private static final VoxelShape POST = Block.box(6, 0, 6, 10, 8, 10);
	private static final VoxelShape BOX = Block.box(4, 8, 4, 12, 14, 12);
	private static final VoxelShape SHAPE = Shapes.or(POST, BOX);

	public MailboxBlock(Properties settings) {
		super(settings);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MailboxBlockEntity(pos, state);
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
		super.setPlacedBy(world, pos, state, placer, itemStack);
		if (!world.isClientSide() && placer instanceof ServerPlayer player) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof MailboxBlockEntity mailbox) {
				mailbox.setOwner(player);
				String dimension = world.dimension().identifier().toString();
				PlayerMailStorage storage = PlayerMailStorage.get(player.level().getServer());
				storage.registerMailbox(player.getUUID(), dimension, pos);
			}
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel world, BlockPos pos, boolean moved) {
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity instanceof MailboxBlockEntity mailbox && mailbox.getOwnerUuid() != null) {
			PlayerMailStorage storage = PlayerMailStorage.get(world.getServer());
			storage.unregisterMailbox(mailbox.getOwnerUuid(), pos);
		}
		super.affectNeighborsAfterRemoval(state, world, pos, moved);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof MailboxBlockEntity mailbox) {
				mailbox.openGui(serverPlayer);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
