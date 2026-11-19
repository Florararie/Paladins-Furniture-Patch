package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Makes a furniture bed look like a bed to the client without giving it one.
 *
 * <p>The client works out how a sleeping player lies by looking up the block at the sleeping
 * position. Paladin's Furniture beds and bathtubs are real {@link BedBlock}s on the server, but the
 * client is shown an invisible stand-in, so it finds no bed there and the player sleeps standing in
 * the block. Sending a real bed is not an option: a bed state cannot be hidden (a bed is drawn by a
 * block entity renderer, not by its blockstate), so it would render inside the furniture.
 *
 * <p>The bed the client looks at is therefore moved out of the way. The real position is kept on the
 * server and the client is told the player is sleeping at the bottom (or top) of the world, where a
 * red bed of the right facing is placed for as long as the player sleeps. Nothing renders there and
 * the pose is the mod's own.
 *
 * <p>This is the same trick the Skniro's Furniture patch uses, and the same one PolyDecorations uses
 * for its sleeping bags.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    @Unique
    private BlockPos pfm$realSleepPos;

    public LivingEntityMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @ModifyVariable(method = "setSleepingPos", at = @At("HEAD"), argsOnly = true)
    private BlockPos pfm$fakeSleepPos(BlockPos pos) {
        var state = this.level().getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock bed)
                || !BuiltInRegistries.BLOCK.getKey(bed).getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
            this.pfm$realSleepPos = null;
            return pos;
        }

        this.pfm$realSleepPos = pos;
        var fakePos = this.pfm$fakePos(pos);

        for (var player : ((ServerLevel) this.level()).getChunkSource().chunkMap
                .getPlayersCloseForSpawning(this.chunkPosition())) {
            player.connection.send(new ClientboundBlockUpdatePacket(fakePos,
                    Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, state.getValue(BedBlock.FACING))));
        }

        return fakePos;
    }

    @Inject(method = "getSleepingPos", at = @At("HEAD"), cancellable = true)
    private void pfm$realSleepPos(CallbackInfoReturnable<Optional<BlockPos>> callback) {
        if (this.pfm$realSleepPos != null) {
            callback.setReturnValue(Optional.of(this.pfm$realSleepPos));
        }
    }

    @Inject(method = "clearSleepingPos", at = @At("HEAD"))
    private void pfm$clearFakeBed(CallbackInfo callback) {
        if (this.pfm$realSleepPos == null) {
            return;
        }

        var fakePos = this.pfm$fakePos(this.pfm$realSleepPos);
        for (var player : ((ServerLevel) this.level()).getChunkSource().chunkMap
                .getPlayersCloseForSpawning(this.chunkPosition())) {
            player.connection.send(new ClientboundBlockUpdatePacket(this.level(), fakePos));
        }

        this.pfm$realSleepPos = null;
    }

    @Unique
    private BlockPos pfm$fakePos(BlockPos pos) {
        return pos.atY(pos.getY() > 0 ? this.level().getMinY() : this.level().getMaxY() - 1);
    }
}
