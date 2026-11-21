package eu.pb4.paladinsfurniturepatch.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives toilets a vanilla-client way to be used and flushed.
 *
 * <p>PFM marks a clean toilet dirty from a client keybind, so a client without the mod can never
 * reach the dirty state and therefore never reach the flush branch. Right-clicking while seated now
 * does the same thing, and right-clicking a dirty toilet flushes it.
 */
@Mixin(targets = "com.unlikepaladin.pfm.blocks.BasicToiletBlock")
public class ToiletBlockMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void paladinsFurniturePatch$useToiletWithEmptyHand(ItemStack stack, BlockState state, Level world,
                                                              BlockPos pos, Player player, InteractionHand hand,
                                                              BlockHitResult hit,
                                                              CallbackInfoReturnable<InteractionResult> callback) {
        // A water bucket or bottle is the fill path; only an empty hand should dirty or flush.
        if (!stack.isEmpty()) {
            return;
        }

        var result = paladinsFurniturePatch$use(state, world, pos, player);
        if (result != null) {
            callback.setReturnValue(result);
        }
    }

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void paladinsFurniturePatch$useToilet(BlockState state, Level world, BlockPos pos,
                                                 Player player, BlockHitResult hit,
                                                 CallbackInfoReturnable<InteractionResult> callback) {
        var result = paladinsFurniturePatch$use(state, world, pos, player);
        if (result != null) {
            callback.setReturnValue(result);
        }
    }

    private static InteractionResult paladinsFurniturePatch$use(BlockState state, Level world,
                                                                BlockPos pos, Player player) {
        if (world.isClientSide()) {
            return null;
        }

        var toilet = toiletProperty(state);
        if (toilet == null) {
            return null;
        }

        var current = state.getValue(toilet);
        var name = toilet.getName(current);
        if ("clean".equals(name)) {
            // The keybind path is client-only. Sitting on the toilet and right-clicking it is the
            // closest thing a vanilla client has to that key, and it does not eject the player.
            if (!isSeatedOn(player, pos)) {
                return null;
            }

            var dirty = toilet.getValue("dirty").orElse(null);
            if (dirty == null) {
                return null;
            }

            world.setBlockAndUpdate(pos, with(state, toilet, dirty));
            world.playSound(null, pos, sound("toilet_used"), SoundSource.BLOCKS, 0.6f, 1.0f);
            return InteractionResult.SUCCESS_SERVER;
        } else if ("dirty".equals(name)) {
            var flushing = toilet.getValue("flushing").orElse(null);
            if (flushing == null) {
                return null;
            }

            world.setBlockAndUpdate(pos, with(state, toilet, flushing));
            resetFlushTimer(world, pos);
            world.playSound(null, pos, sound("toilet_flushing"), SoundSource.BLOCKS, 0.3f, 1.0f);
            return InteractionResult.SUCCESS_SERVER;
        }

        return null;
    }

    @Unique
    private static boolean isSeatedOn(Player player, BlockPos pos) {
        var vehicle = player.getVehicle();
        if (vehicle == null) {
            return false;
        }
        // Chair entities sit at the block centre, but a small radius tolerates a seat that has
        // drifted by a fraction of a block instead of silently ignoring the click.
        return vehicle.blockPosition().equals(pos)
                || vehicle.distanceToSqr(Vec3.atCenterOf(pos)) <= 2.25;
    }

    @SuppressWarnings("rawtypes")
    @Unique
    private static Property toiletProperty(BlockState state) {
        for (var property : state.getProperties()) {
            if (property.getName().equals("toilet")) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Unique
    private static BlockState with(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }

    @Unique
    private static SoundEvent sound(String path) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("pfm", path));
    }

    @Unique
    private static void resetFlushTimer(Level world, BlockPos pos) {
        var blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        try {
            blockEntity.getClass().getMethod("setFlushTimer", int.class).invoke(blockEntity, 0);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A different PFM version may not expose the timer; its own tick still handles it.
        }
    }
}
