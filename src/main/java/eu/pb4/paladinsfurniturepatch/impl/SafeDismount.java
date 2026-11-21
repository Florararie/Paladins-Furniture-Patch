package eu.pb4.paladinsfurniturepatch.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The seat dismount position used by PolyDecorations.
 *
 * <p>Vanilla's dismount helper searches a broad set of offsets and can pick a spot on the far side
 * of a wall when the seat is against one. This only tries the space directly in front of the seat,
 * then the space above the seat, and finally the seat block itself. Every candidate is checked with
 * the standing player's full bounding box, so a seat next to a wall cannot put the player through
 * it.
 */
public final class SafeDismount {
    private SafeDismount() {
    }

    public static Vec3 find(Level level, BlockPos pos, Direction direction, LivingEntity passenger) {
        var box = passenger.getLocalBoundsForPose(Pose.STANDING);
        var base = Vec3.atBottomCenterOf(pos);

        var candidate = base.relative(direction, 1);
        if (level.noCollision(box.move(candidate))) {
            return candidate;
        }

        candidate = base.relative(Direction.UP, 1);
        if (level.noCollision(box.move(candidate))) {
            return candidate;
        }

        return base;
    }
}
