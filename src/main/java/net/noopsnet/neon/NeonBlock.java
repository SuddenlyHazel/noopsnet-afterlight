// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

/** Chunk-baked tinted geometry. Colour storage has no ticker or custom renderer. */
public class NeonBlock extends HorizontalDirectionalBlock implements SimpleWaterloggedBlock, EntityBlock {
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty LIGHT_LEVEL=net.minecraft.world.level.block.state.properties.IntegerProperty.create("light_level",0,15);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    protected final String form;
    public boolean editableSign() { return form.equals("sign") || form.equals("blade"); }
    public boolean bladeSign() { return form.equals("blade"); }
    public final int defaultColor;
    private final VoxelShape[] shapes = new VoxelShape[4];

    public NeonBlock(String form, int defaultColor, Properties properties) {
        super(properties);
        this.form = form;
        this.defaultColor = defaultColor;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
            .setValue(LIT, true).setValue(WATERLOGGED, false).setValue(LIGHT_LEVEL,NeonCatalog.light(form)));
        shapes[0] = NeonCatalog.shape(form);
        for (int i = 1; i < 4; i++) {
            final VoxelShape[] next = {Shapes.empty()};
            shapes[i - 1].forAllBoxes((x1, y1, z1, x2, y2, z2) -> next[0] = Shapes.or(next[0],
                Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
            shapes[i] = next[0];
        }
    }

    // Forms are registered by explicit factories; decoding with a default form would lose geometry.
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(properties -> new NeonBlock(form, defaultColor, properties));
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NeonBlockEntity(pos, state);
    }

    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (!editableSign() || !player.getMainHandItem().isEmpty() || !player.mayBuild() || player.isSpectator())
            return net.minecraft.world.InteractionResult.PASS;
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof NeonBlockEntity sign) {
            serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id,inventory,viewer) -> new SignArtMenu(id,inventory,pos,sign.art(),sign.color(),bladeSign()),
                net.minecraft.network.chat.Component.translatable("noopsnet_neon.art.title")), buf -> {
                    buf.writeBlockPos(pos);SignArtPayload.writeArt(buf,sign.art());buf.writeInt(sign.color());buf.writeBoolean(bladeSign());
                });
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof NeonBlockEntity light) {
            if(LightSettings.present(stack))light.applySettings(LightSettings.get(stack));
            else if(placer instanceof Player player&&player.getOffhandItem().is(NoopsnetNeon.PALETTE.get())&&PaletteItem.mode(player.getOffhandItem())!=1)
                light.applySettings(LightSettings.get(player.getOffhandItem()));
        }
        // Explicit colours on recovered items take priority over the offhand palette.
        if (!level.isClientSide && stack.get(DataComponents.DYED_COLOR) == null && placer instanceof Player player
                && player.getOffhandItem().is(NoopsnetNeon.PALETTE.get())
                && PaletteItem.mode(player.getOffhandItem())!=2
                && level.getBlockEntity(pos) instanceof NeonBlockEntity light) {
            light.setColor(PaletteItem.color(player.getOffhandItem()));
        }
    }

    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof NeonBlockEntity light) {
            stack.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(light.color(), false));
            light.copyArtTo(stack);
            light.settings().put(stack);
        }
        return stack;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT, WATERLOGGED,LIGHT_LEVEL);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing)
            .setValue(LIT, !context.getLevel().hasNeighborSignal(context.getClickedPos()))
            .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[switch (state.getValue(FACING)) {
            case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3;
            default -> throw new IllegalStateException("Horizontal facing required");
        }];
    }

    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos from, boolean moving) {
        boolean lit = !level.hasNeighborSignal(pos);
        if (!level.isClientSide && lit != state.getValue(LIT)) level.setBlock(pos, state.setValue(LIT, lit), Block.UPDATE_ALL);
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
}
