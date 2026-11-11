// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.neon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

/** Four local wall-plane ports: left/right/up/down. Old saved states default to fixed geometry. */
public final class ConnectedNeonBlock extends NeonBlock {
    @Override protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.HorizontalDirectionalBlock> codec() {
        return simpleCodec(properties->new ConnectedNeonBlock(form,defaultColor,properties));
    }
    public static final BooleanProperty JOIN=BooleanProperty.create("joining");
    public static final int FIXED=16;
    public static boolean isJoining(BlockState state){return state.getValue(JOIN);}
    public static BlockState withJoining(BlockState state,boolean joining){return state.setValue(JOIN,joining);}
    public static final int LEFT=1,RIGHT=2,UP=4,DOWN=8;
    private final boolean inlay;
    private final int fixedPorts;
    private final VoxelShape[][] connectedShapes=new VoxelShape[16][4];
    public ConnectedNeonBlock(String form,int color,Properties properties) {
        super(form,color,properties);inlay=form.equals("channel");fixedPorts=form.equals("elbow")?LEFT|UP:LEFT|RIGHT;
        registerDefaultState(defaultBlockState().setValue(JOIN,false));
        for(int mask=0;mask<16;mask++) {
            int effective=mask==0?LEFT|RIGHT:mask;
            VoxelShape shape=inlay?Shapes.block():Block.box(5,5,13,11,11,16);
            if(!inlay) {
                if((effective&LEFT)!=0)shape=Shapes.or(shape,Block.box(0,5,13,5,11,16));
                if((effective&RIGHT)!=0)shape=Shapes.or(shape,Block.box(11,5,13,16,11,16));
                if((effective&UP)!=0)shape=Shapes.or(shape,Block.box(5,11,13,11,16,16));
                if((effective&DOWN)!=0)shape=Shapes.or(shape,Block.box(5,0,13,11,5,16));
                // Include the proud tube and brass shoulder in selection/collision.
                final VoxelShape[] front={shape};shape.forAllBoxes((x,y,z,X,Y,Z)->front[0]=Shapes.or(front[0],Shapes.box(x,y,11.5/16,X,Y,Z)));shape=front[0];
            }
            connectedShapes[mask][0]=shape;
            for(int rotation=1;rotation<4;rotation++) {
                final VoxelShape[] next={Shapes.empty()};connectedShapes[mask][rotation-1].forAllBoxes((x,y,z,X,Y,Z)->next[0]=Shapes.or(next[0],Shapes.box(1-Z,y,x,1-z,Y,X)));
                connectedShapes[mask][rotation]=next[0];
            }
        }
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {super.createBlockStateDefinition(builder);builder.add(JOIN);}
    private static Direction direction(Direction facing,int port) {return switch(port){case LEFT->facing.getCounterClockWise();case RIGHT->facing.getClockWise();case UP->Direction.UP;default->Direction.DOWN;};}
    private static int opposite(int port) {return switch(port){case LEFT->RIGHT;case RIGHT->LEFT;case UP->DOWN;default->UP;};}
    public int connections(BlockState state,BlockGetter world,BlockPos pos) {
        if(!isJoining(state))return FIXED;
        int mask=0;Direction facing=state.getValue(FACING);
        for(int port:new int[]{LEFT,RIGHT,UP,DOWN}) {
            BlockPos neighborPos=pos.relative(direction(facing,port));
            if(world instanceof LevelReader reader&&!reader.hasChunkAt(neighborPos))continue;
            BlockState neighbor=world.getBlockState(neighborPos);
            if(neighbor.getBlock() instanceof ConnectedNeonBlock other&&other.inlay==inlay&&neighbor.getValue(FACING)==facing
                    &&(isJoining(neighbor)||(other.fixedPorts&opposite(port))!=0))mask|=port;
        }
        return mask;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {return super.getStateForPlacement(context).setValue(JOIN,true);}
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbor,LevelAccessor world,BlockPos pos,BlockPos neighborPos) {
        return super.updateShape(state,direction,neighbor,world,pos,neighborPos);
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) {
        if(!isJoining(state))return super.getShape(state,world,pos,context);
        int rotation=switch(state.getValue(FACING)){case EAST->1;case SOUTH->2;case WEST->3;default->0;};
        return connectedShapes[connections(state,world,pos)][rotation];
    }
    @Override public BlockState mirror(BlockState state,Mirror mirror) {return state.setValue(FACING,mirror.mirror(state.getValue(FACING)));}
    @Override public BlockState rotate(BlockState state,Rotation rotation) {return state.setValue(FACING,rotation.rotate(state.getValue(FACING)));}
}
