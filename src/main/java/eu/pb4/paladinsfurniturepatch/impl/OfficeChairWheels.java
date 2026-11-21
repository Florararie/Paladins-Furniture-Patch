package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.IdentifiedUniqueEntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the four casters of an office chair and spins them as it rolls.
 *
 * <p>The chair itself is a single item display, which cannot animate one group of its model. The
 * casters are therefore drawn by four displays of their own, attached to the chair entity. Their
 * positions are the caster centres from the mod's full chair model: an item display subtracts half
 * a block from the model, so those centres are already in display space.
 *
 * <p>The mod's renderer adds {@code velocity.horizontalDistance() * 200} degrees to a wheel angle
 * every tick. The patch moves the chair with {@code setPos} and clears its velocity, so that field
 * stays still; the same angle is accumulated here from the chair's own movement instead. The sign
 * of the rotation cancels the half turn an item display applies to every model.
 */
final class OfficeChairWheels extends ElementHolder {
    private static final Identifier ATTACHMENT_ID = PaladinsFurniturePatch.id("office_chair_wheels");

    /**
     * Caster centres from {@code office_chair.json}, after the half-block an item display removes.
     * The four wheels are right, left, back and front in the model's own order.
     */
    private static final float[][] WHEELS = {
            {0.3078125f, 0.0015625f},
            {-0.3796875f, 0.0015625f},
            {-0.0359375f, 0.3609375f},
            {-0.0359375f, -0.3578125f}
    };

    /** The chair model's own translation of -0.25, plus a caster centre half a block down. */
    private static final double WHEEL_Y = -0.6875;

    private static final int TELEPORT_DURATION = 3;

    private final Entity chair;
    private final ItemDisplayElement[] wheels = new ItemDisplayElement[WHEELS.length];
    private double lastX;
    private double lastZ;
    private float spin;

    private OfficeChairWheels(Entity chair) {
        this.chair = chair;
        this.lastX = chair.getX();
        this.lastZ = chair.getZ();

        var stack = PolyFurnitureItem.officeChairWheelStack();
        for (int i = 0; i < WHEELS.length; i++) {
            var element = new ItemDisplayElement();
            element.setItem(stack);
            element.setItemDisplayContext(ItemDisplayContext.NONE);
            element.setViewRange(2f);
            element.setDisplaySize(0.5f, 0.5f);
            element.setInterpolationDuration(1);
            element.setTeleportDuration(TELEPORT_DURATION);
            this.addElement(element);
            this.wheels[i] = element;
        }
    }

    static void attach(Entity chair) {
        IdentifiedUniqueEntityAttachment.ofTicking(ATTACHMENT_ID, new OfficeChairWheels(chair), chair);
    }

    @Override
    protected void onTick() {
        double dx = this.chair.getX() - this.lastX;
        double dz = this.chair.getZ() - this.lastZ;
        this.lastX = this.chair.getX();
        this.lastZ = this.chair.getZ();

        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        if (distance > 1.0E-7f) {
            this.spin += distance * 200f;
        }

        float yaw = this.chair.getYRot();
        // The display renderer turns the entity by -yaw and the item renderer adds another half
        // turn, so the wheel centres have the same turn applied to them here.
        var facing = new Quaternionf().rotateY((float) Math.toRadians(180f - yaw));
        var rotation = new Quaternionf().rotateX((float) Math.toRadians(-this.spin));

        for (int i = 0; i < this.wheels.length; i++) {
            var element = this.wheels[i];
            var offset = new Vector3f(WHEELS[i][0], 0f, WHEELS[i][1]).rotate(facing);
            element.setOffset(new Vec3(offset.x, WHEEL_Y, offset.z));
            // Turning the display itself keeps each caster under the chair as the chair turns.
            element.setYaw(yaw);
            element.setLeftRotation(rotation);
            element.startInterpolationIfDirty();
        }

        super.onTick();
    }
}
