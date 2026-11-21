package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Client-side stand-in for one piece of furniture.
 *
 * <p>The block itself stays real on the server. The client is sent an invisible vanilla state whose
 * collision matches the furniture's own shape - a thin panel for a wall cabinet, a floor plate for a
 * plate - while a display element attached to the block draws the mod's model. The stand-in states
 * are chosen per block state by {@link FurnitureShapes}.
 */
public final class FurnitureBlockOverlay implements PolymerBlock, BlockWithElementHolder {
    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return FurnitureShapes.standIn(state);
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState initialState) {
        return new FurnitureBlockModel(initialState);
    }

    /**
     * The holder polls its neighbours so a fridge connects the tick it is stacked, a cabinet forms
     * its corner, and a lamp picks up the wood stored on its block entity. Polymer only ticks a
     * block holder when this is true, and the default is false.
     */
    @Override
    public boolean tickElementHolder(ServerLevel world, BlockPos pos, BlockState initialState) {
        return true;
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        // Used for both the breaking particles and the break sound, so it has to match the
        // furniture's material rather than always being oak.
        return FurnitureShapes.breakState(state);
    }
}
