package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Gives the office chair something to click.
 *
 * <p>The chair is drawn with an item display, which a vanilla client can see but cannot hit: a
 * display has no size, so a click passes straight through it and the mod's own interact and damage
 * logic never runs. Each chair therefore gets a vanilla interaction entity on the same spot. That
 * entity has a real box, so the client reports clicks and punches for it, and those are forwarded to
 * the chair - riding it, rolling around on it, or breaking it for its item, all of which the mod
 * already implements.
 *
 * <p>The box is not ticked by the chair itself, so it is moved along with it here and removed when
 * the chair dies, is dismounted from, or leaves the world.
 */
public final class OfficeChairHitboxes {
    /** How big a clickable chair is: a little smaller than the block it stands on. */
    private static final float WIDTH = 0.7f;
    private static final float HEIGHT = 1.1f;

    /** How far the entity is raised so a rider is drawn at the seat instead of on the floor. */
    private static final float SEAT_HEIGHT = 0.75f;

    /** How far the model is lowered to stay where it was once the entity moved up to the seat. */
    private static final float MODEL_DROP = 0.20f;

    /** A chair's clickable box, and the level it lives in so a tick only touches its own. */
    private record Hitbox(UUID id, ResourceKey<Level> level) {
    }

    private static final Map<UUID, Hitbox> CHAIR_TO_HITBOX = new HashMap<>();
    private static final Map<UUID, UUID> HITBOX_TO_CHAIR = new HashMap<>();

    /**
     * Chairs waiting for their hitbox, by chair id and level. The load event cannot create the hitbox
     * itself: it fires from {@code ServerLevel.EntityCallbacks.onTrackingStart}, which vanilla calls
     * while streaming over the chunk's entities in {@code PersistentEntitySectionManager.updateChunkStatus}.
     * Adding an entity there changes the list under the stream and throws a
     * {@code ConcurrentModificationException}, so the spawn waits for the level tick instead.
     */
    private static final Map<UUID, ResourceKey<Level>> PENDING_SPAWNS = new HashMap<>();

    private static boolean resolved;
    private static @Nullable EntityDataAccessor<Float> widthAccessor;
    private static @Nullable EntityDataAccessor<Float> heightAccessor;

    private OfficeChairHitboxes() {
    }

    public static void setup() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (isChair(entity)) {
                PENDING_SPAWNS.put(entity.getUUID(), world.dimension());
            }
        });

        ServerTickEvents.END_LEVEL_TICK.register(OfficeChairHitboxes::tick);

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            var chair = chairOf(entity);
            if (chair == null) {
                return InteractionResult.PASS;
            }

            if (world.isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }

            if (!chair.isVehicle()) {
                player.startRiding(chair, true, true);
            }
            return InteractionResult.SUCCESS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            var chair = chairOf(entity);
            if (chair == null) {
                return InteractionResult.PASS;
            }

            if (world instanceof ServerLevel server) {
                // PFM's chair has ten health and regains a little every tick. Four damage per
                // punch brings it down in three hits, which is quicker than the mod's ten.
                chair.hurtServer(server, player.damageSources().playerAttack(player), 4.0f);
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static boolean isChair(Entity entity) {
        var id = EntityType.getKey(entity.getType());
        return id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)
                && id.getPath().equals("office_chair");
    }

    private static void spawnHitbox(Entity chair, ServerLevel world) {
        if (CHAIR_TO_HITBOX.containsKey(chair.getUUID())) {
            return;
        }

        var hitbox = EntityType.INTERACTION.create(world, EntitySpawnReason.COMMAND);
        if (hitbox == null) {
            return;
        }

        // A rider is drawn at the display entity's position, so raising the chair to seat height
        // puts the player's feet at the seat rather than on the floor.
        chair.setNoGravity(true);
        if (chair instanceof Mob mob) {
            mob.setNoAi(true);
        }
        chair.setPos(chair.getX(), (double) chair.getBlockY() + SEAT_HEIGHT, chair.getZ());

        hitbox.snapTo(chair.getX(), chair.getY() - MODEL_DROP, chair.getZ(), chair.getYRot(), 0f);
        hitbox.setNoGravity(true);
        hitbox.setInvulnerable(true);
        size(hitbox);
        world.addFreshEntity(hitbox);

        CHAIR_TO_HITBOX.put(chair.getUUID(), new Hitbox(hitbox.getUUID(), world.dimension()));
        HITBOX_TO_CHAIR.put(hitbox.getUUID(), chair.getUUID());
    }

    /** An interaction's box is synced state, so it has to be told its size once it exists. */
    private static void size(Interaction hitbox) {
        resolve();
        if (widthAccessor == null || heightAccessor == null) {
            return;
        }

        try {
            hitbox.getEntityData().set(widthAccessor, WIDTH);
            hitbox.getEntityData().set(heightAccessor, HEIGHT);
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.debug("Could not size an office chair's hitbox", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        try {
            var width = Interaction.class.getDeclaredField("DATA_WIDTH_ID");
            width.setAccessible(true);
            widthAccessor = (EntityDataAccessor<Float>) width.get(null);

            var height = Interaction.class.getDeclaredField("DATA_HEIGHT_ID");
            height.setAccessible(true);
            heightAccessor = (EntityDataAccessor<Float>) height.get(null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not size office chair hitboxes", exception);
        }
    }

    private static void spawnPending(ServerLevel world) {
        if (PENDING_SPAWNS.isEmpty()) {
            return;
        }

        for (Iterator<Map.Entry<UUID, ResourceKey<Level>>> iterator = PENDING_SPAWNS.entrySet().iterator();
             iterator.hasNext(); ) {
            var entry = iterator.next();
            if (!entry.getValue().equals(world.dimension())) {
                continue;
            }
            iterator.remove();

            var chair = world.getEntity(entry.getKey());
            if (chair != null && chair.isAlive()) {
                spawnHitbox(chair, world);
            }
        }
    }

    private static void tick(ServerLevel world) {
        spawnPending(world);

        for (Iterator<Map.Entry<UUID, Hitbox>> iterator = CHAIR_TO_HITBOX.entrySet().iterator();
             iterator.hasNext(); ) {
            var entry = iterator.next();
            var ref = entry.getValue();
            if (!ref.level().equals(world.dimension())) {
                continue;
            }

            var chair = world.getEntity(entry.getKey());
            var hitbox = world.getEntity(ref.id());

            if (chair == null || !chair.isAlive() || hitbox == null) {
                if (hitbox != null) {
                    hitbox.discard();
                }
                HITBOX_TO_CHAIR.remove(ref.id());
                iterator.remove();
                continue;
            }

            // Keep the chair at seat height and let its rider steer it. The client sends its own
            // movement input, which vanilla ignores for a mob vehicle, so it is applied here.
            chair.setNoGravity(true);
            double seatY = chair.getBlockY() + SEAT_HEIGHT;
            if (Math.abs(chair.getY() - seatY) > 0.001) {
                chair.setPos(chair.getX(), seatY, chair.getZ());
            }

            if (chair.getFirstPassenger() instanceof ServerPlayer rider) {
                var input = rider.getLastClientInput();
                float turn = (input.right() ? 1f : 0f) - (input.left() ? 1f : 0f);
                if (turn != 0f) {
                    chair.setYRot(chair.getYRot() + turn * 4f);
                }

                int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
                if (forward != 0) {
                    double yaw = Math.toRadians(chair.getYRot());
                    chair.setPos(chair.getX() - Math.sin(yaw) * 0.14 * forward, seatY,
                            chair.getZ() + Math.cos(yaw) * 0.14 * forward);
                }
                chair.setDeltaMovement(0, 0, 0);
            }

            hitbox.setPos(chair.getX(), chair.getY() - MODEL_DROP, chair.getZ());
            hitbox.setYRot(chair.getYRot());
        }
    }

    /** The chair a clicked hitbox belongs to, or null when the entity is not one of ours. */
    private static @Nullable Entity chairOf(Entity clicked) {
        if (!(clicked instanceof Interaction)) {
            return null;
        }

        var chairId = HITBOX_TO_CHAIR.get(clicked.getUUID());
        return chairId == null ? null : clicked.level().getEntity(chairId);
    }

    /** Drops every hitbox, for a reload of the server or the mod's own state. */
    public static void clear() {
        CHAIR_TO_HITBOX.clear();
        HITBOX_TO_CHAIR.clear();
        PENDING_SPAWNS.clear();
    }
}
