package justfatlard.village_mail.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
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

public class PublicMailboxBlock extends BlockWithEntity {
	public static final MapCodec<PublicMailboxBlock> CODEC = createCodec(PublicMailboxBlock::new);
	public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

	// Larger post office mailbox: wider base + taller box
	private static final VoxelShape BASE = Block.createCuboidShape(4, 0, 4, 12, 6, 12);
	private static final VoxelShape BOX = Block.createCuboidShape(2, 6, 2, 14, 14, 14);
	private static final VoxelShape SHAPE = VoxelShapes.union(BASE, BOX);

	public PublicMailboxBlock(Settings settings) {
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
		return new PublicMailboxBlockEntity(pos, state);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof PublicMailboxBlockEntity publicMailbox) {
				publicMailbox.openGui(serverPlayer);
			}
		}
		return ActionResult.SUCCESS;
	}
}
