package eu.pb4.paladinsfurniturepatch.impl;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws one furniture block with item display elements, one per model the blockstate asks for.
 *
 * <p>The block stays a real block on the server; this holder is attached to it, so it moves with
 * pistons and disappears with the block. When the state changes - a door opening, a lamp turning on,
 * a chair being tucked in - Polymer notifies the holder and the models are swapped.
 */
public class FurnitureBlockModel extends ElementHolder {
    private final List<ItemDisplayElement> elements = new ArrayList<>();
    private final Map<Integer, ItemDisplayElement> contents = new LinkedHashMap<>();
    private List<ItemStack> contentStacks = List.of();
    private String contentFamily = "";
    private List<FurnitureModels.Part> parts = List.of();
    private BlockState state;
    private int ticks;

    public FurnitureBlockModel(BlockState state) {
        this.state = state;
        this.applyParts(FurnitureModels.get(state));
    }

    @Override
    public void notifyUpdate(HolderAttachment.UpdateType updateType) {
        super.notifyUpdate(updateType);

        if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
            var current = this.blockState();
            if (current != this.state) {
                this.state = current;
                this.applyParts(FurnitureModels.get(current, this.level(), this.pos()));
            }
        }
    }

    protected BlockState blockState() {
        return this.getAttachment() instanceof BlockAwareAttachment attachment
                ? attachment.getBlockState()
                : Blocks.AIR.defaultBlockState();
    }

    private @Nullable BlockGetter level() {
        return this.getAttachment() instanceof BlockAwareAttachment attachment ? attachment.getWorld() : null;
    }

    private @Nullable BlockPos pos() {
        return this.getAttachment() instanceof BlockAwareAttachment attachment ? attachment.getBlockPos() : null;
    }

    /**
     * Furniture whose model depends on its neighbours - fridges stacking, cabinets forming corners,
     * beds joining head to foot, lamps stacking - changes when a neighbour is placed or broken
     * without a state change of its own. The selection is re-read often and only applied when the
     * models actually differ, so a stacked fridge connects on the next tick instead of waiting for
     * its door to be opened.
     */
    @Override
    public void tick() {
        super.tick();
        this.showShower();

        // The state is read from the world here rather than only from the holder's update
        // notification: a block whose client stand-in stays the same when it changes - a cabinet
        // whose door swings, a drawer that slides out - does not always produce one, and the door
        // then stayed shut however often it was opened.
        var current = this.blockState();
        if (current != this.state) {
            this.state = current;
            this.applyParts(FurnitureModels.get(current, this.level(), this.pos()));
        }

        if (++this.ticks < 2) {
            this.applyContent();
            return;
        }
        this.ticks = 0;

        if (!FurnitureModels.needsNeighbours(this.state)) {
            this.applyContent();
            return;
        }

        var next = FurnitureModels.get(this.state, this.level(), this.pos());
        if (!sameModels(next)) {
            this.applyParts(next);
        }
    }

    private boolean sameModels(List<FurnitureModels.Part> next) {
        if (next.size() != this.parts.size()) {
            return false;
        }
        for (int i = 0; i < next.size(); i++) {
            var part = next.get(i);
            var drawn = this.parts.get(i);
            if (!part.model().equals(drawn.model()) || !part.rotation().equals(drawn.rotation())) {
                return false;
            }
        }
        return true;
    }

    private void applyParts(List<FurnitureModels.Part> next) {
        this.parts = next;
        this.applyContent();

        while (this.elements.size() > next.size()) {
            this.removeElement(this.elements.removeLast());
        }

        for (int i = 0; i < next.size(); i++) {
            var part = next.get(i);
            boolean created = i >= this.elements.size();
            ItemDisplayElement element;

            if (created) {
                element = createElement();
                this.elements.add(element);
            } else {
                element = this.elements.get(i);
            }

            element.setItem(part.stack());
            element.setLeftRotation(part.rotation());

            if (created) {
                this.addElement(element);
            } else {
                element.tick();
            }
        }
    }

    /**
     * PFM draws the items inside a cooking block with its own block entity renderer, which a client
     * without the mod does not have. The same items are mirrored here by item displays, at the
     * positions and with the rotations the mod's renderers use.
     */
    private void applyContent() {
        var kind = contentKind();
        if (kind == null) {
            this.clearContent();
            return;
        }

        var level = this.level();
        var pos = this.pos();
        if (!(level instanceof Level world) || pos == null) {
            this.clearContent();
            return;
        }

        var blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            this.clearContent();
            return;
        }

        var stacks = contentStacks(kind, blockEntity);
        if (stacks == null) {
            this.clearContent();
            return;
        }

        if (!kind.equals(this.contentFamily) || !sameStacks(stacks)) {
            this.rebuildContent(kind, stacks);
            this.contentFamily = kind;
            // Container stacks are live objects. Copying only the list kept the old stack object
            // next to the one in the container, so removing an item looked like no change and the
            // display stayed behind.
            this.contentStacks = stacks.stream().map(ItemStack::copy).toList();
        }

        this.positionContent(kind, world, pos);
    }

    /** The renderer a block uses, or null for furniture that holds no visible items. */
    private @Nullable String contentKind() {
        var path = BuiltInRegistries.BLOCK.getKey(this.state.getBlock()).getPath();
        if (path.contains("microwave")) {
            return "microwave";
        }
        if (path.contains("toaster")) {
            return "toaster";
        }
        if (path.contains("plate")) {
            return "plate";
        }
        if (path.contains("stovetop")) {
            return "stovetop";
        }
        return path.contains("stove") ? "stove" : null;
    }

    private static @Nullable List<ItemStack> contentStacks(String kind, Object blockEntity) {
        return switch (kind) {
            case "microwave" -> microwaveStacks(blockEntity);
            case "plate" -> plateStacks(blockEntity);
            case "toaster" -> toasterStacks(blockEntity);
            default -> cookedStacks(blockEntity);
        };
    }

    private static @Nullable List<ItemStack> plateStacks(Object blockEntity) {
        try {
            var value = blockEntity.getClass().getMethod("getItemInPlate").invoke(blockEntity);
            return value instanceof ItemStack stack ? List.of(stack) : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static @Nullable List<ItemStack> toasterStacks(Object blockEntity) {
        try {
            var value = blockEntity.getClass().getMethod("getItems").invoke(blockEntity);
            return value instanceof List<?> list ? stacksOf(list) : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static @Nullable List<ItemStack> microwaveStacks(Object blockEntity) {
        if (!(blockEntity instanceof Container container)) {
            return null;
        }
        return List.of(container.getItem(0));
    }

    private static @Nullable List<ItemStack> cookedStacks(Object blockEntity) {
        try {
            var value = blockEntity.getClass().getMethod("getItemsBeingCooked").invoke(blockEntity);
            if (value instanceof List<?> list) {
                return stacksOf(list);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older builds expose the list as a field instead of a getter.
        }

        try {
            var value = blockEntity.getClass().getField("itemsBeingCooked").get(blockEntity);
            if (value instanceof List<?> list) {
                return stacksOf(list);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Not a cooking block this patch knows.
        }
        return null;
    }

    private static List<ItemStack> stacksOf(List<?> list) {
        var stacks = new ArrayList<ItemStack>(list.size());
        for (var entry : list) {
            if (entry instanceof ItemStack stack) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    private boolean sameStacks(List<ItemStack> stacks) {
        if (stacks.size() != this.contentStacks.size()) {
            return false;
        }
        for (int i = 0; i < stacks.size(); i++) {
            if (!ItemStack.matches(stacks.get(i), this.contentStacks.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void rebuildContent(String kind, List<ItemStack> stacks) {
        this.clearContentElements();
        for (int slot = 0; slot < stacks.size(); slot++) {
            var stack = stacks.get(slot);
            if (stack.isEmpty()) {
                continue;
            }

            var element = new ItemDisplayElement();
            element.setItem(stack.copy());
            element.setItemDisplayContext(kind.equals("microwave") || kind.equals("plate") || kind.equals("toaster")
                    ? ItemDisplayContext.GROUND
                    : ItemDisplayContext.FIXED);
            element.setViewRange(2f);
            element.setDisplaySize(1f, 1f);
            element.setTeleportDuration(0);
            element.setInterpolationDuration(0);
            this.contents.put(slot, element);
            this.addElement(element);
        }
    }

    private void positionContent(String kind, Level world, BlockPos pos) {
        var facing = this.state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? this.state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;

        for (var entry : this.contents.entrySet()) {
            var slot = entry.getKey();
            var element = entry.getValue();

            if (kind.equals("plate")) {
                // PFM: turn to the plate's facing, then tip the item flat above the block floor.
                // Its renderer also slides the item a sixth of a block towards one edge of the
                // block, but that is the plate's least centred point, not its middle: the plate
                // model is centred in the block, so the item is kept near the block centre.
                // PFM does not scale the item either - the GROUND context already carries its own scale.
                var rotation = new Quaternionf()
                        .rotateY((float) Math.toRadians(-facing.toYRot()))
                        .rotateX((float) Math.toRadians(90));

                // Where the item sits before any per-item adjustment.
                var position = new Vector3f(0.2f, 0.08f - 0.5f, 0f);

                // A sandwich is stood upright where every other item lies flat, and lifted off the
                // plate by the offset PFM applies after that extra turn. That offset is defined in
                // the item's own (post-turn) space, so it is rotated into place before being added.
                if (standsUpright(element.getItem())) {
                    rotation.rotateX((float) Math.toRadians(270));
                    position.add(rotation.transform(new Vector3f(0f, 0.11f, 0.05f)));
                }

                // The rotation is passed as it is, without the half turn the other content displays
                // are given: with it the item faced the wrong way round.
                element.setTranslation(position);
                element.setLeftRotation(rotation);
                element.startInterpolationIfDirty();
                continue;
            }

            if (kind.equals("toaster")) {
                int degrees = switch (facing) {
                    case NORTH -> 270;
                    case SOUTH -> 90;
                    case WEST -> 0;
                    default -> 180;
                };
                boolean active = boolProperty(this.state, "on") || toasting(world.getBlockEntity(pos));
                float y = active ? 0.19f : 0.3f;
                var rotation = new Quaternionf().rotateY((float) Math.toRadians(degrees));
                // PFM scales the 0.8 toaster and places the two slots 0.14 apart in model space.
                float offset = slot == 0 ? -0.14f * 0.8f : 0.15f * 0.8f;
                var shift = rotation.transform(new Vector3f(0f, 0f, -offset));
                element.setTranslation(new Vector3f(shift.x, y - 0.5f, shift.z));
                element.setScale(new Vector3f(0.8f));
                element.setLeftRotation(itemSpace(rotation));
                element.startInterpolationIfDirty();
                continue;
            }

            if (kind.equals("microwave")) {
                float x;
                float z;
                switch (facing) {
                    case NORTH -> {
                        x = 0.625f;
                        z = 0.57f;
                    }
                    case SOUTH -> {
                        x = 0.375f;
                        z = 0.43f;
                    }
                    case WEST -> {
                        x = 0.57f;
                        z = 0.375f;
                    }
                    case EAST -> {
                        x = 0.43f;
                        z = 0.625f;
                    }
                    default -> {
                        x = 0.5f;
                        z = 0.5f;
                    }
                }

                element.setTranslation(new Vector3f(x - 0.5f, -0.25f, z - 0.5f));
                element.setScale(new Vector3f(0.5f));
                var rotation = new Quaternionf().rotateY((float) Math.toRadians(-facing.toYRot()));
                if (microwaveActive(world.getBlockEntity(pos))) {
                    rotation.rotateY((float) Math.toRadians((world.getGameTime() * 4L) % 360L));
                }
                element.setLeftRotation(itemSpace(rotation));
                element.startInterpolationIfDirty();
                continue;
            }

            // PFM turns each item to face out from the block's own facing, then tips it flat.
            var direction = Direction.from2DDataValue((slot + facing.get2DDataValue()) % 4);
            float x;
            float z;
            switch (facing) {
                case NORTH -> {
                    x = 0.5f;
                    z = 0.45f;
                }
                case SOUTH -> {
                    x = 0.5f;
                    z = 0.55f;
                }
                case WEST -> {
                    x = 0.45f;
                    z = 0.5f;
                }
                case EAST -> {
                    x = 0.55f;
                    z = 0.5f;
                }
                default -> {
                    x = 0.5f;
                    z = 0.5f;
                }
            }

            float y;
            float scale;
            float offset;
            if (kind.equals("stove")) {
                y = 1.02f;
                scale = 0.4f;
                offset = 0.16f;
                if (facing == Direction.EAST) {
                    x = 0.5f;
                }
            } else {
                y = 0.08f;
                scale = 0.355f;
                offset = 0.1625f;
            }

            var rotation = new Quaternionf()
                    .rotateY((float) Math.toRadians(-direction.toYRot()))
                    .rotateY((float) Math.toRadians(180))
                    .rotateX((float) Math.toRadians(90));
            var shift = rotation.transform(new Vector3f(-offset, -offset, 0f));
            element.setTranslation(new Vector3f(x - 0.5f + shift.x, y - 0.5f + shift.y, z - 0.5f + shift.z));
            element.setScale(new Vector3f(scale));
            element.setLeftRotation(itemSpace(rotation));
            element.startInterpolationIfDirty();
        }
    }

    /** The transform used by the existing content displays, whose entity yaw is the default. */
    private static Quaternionf itemSpace(Quaternionf rotation) {
        return new Quaternionf().rotateY((float) Math.PI).mul(rotation);
    }

    /** The one item PFM stands up on a plate instead of laying it flat. */
    private static boolean standsUpright(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("sandwichable:sandwich");
    }

    private void clearContent() {
        this.clearContentElements();
        this.contentFamily = "";
        this.contentStacks = List.of();
    }

    private void clearContentElements() {
        for (var element : this.contents.values()) {
            this.removeElement(element);
        }
        this.contents.clear();
    }

    /**
     * Draws the water falling from an open shower head.
     *
     * <p>The mod spawns those particles from its own block entity, on the client only, so a client
     * without the mod shows a running shower with no water. The particles are sent from here instead,
     * at the same offsets the mod uses, which also keeps them visible to clients that have the mod.
     */
    private void showShower() {
        if (!(this.getAttachment() instanceof BlockAwareAttachment attachment)
                || !(attachment.getWorld() instanceof ServerLevel world)) {
            return;
        }

        if (!BuiltInRegistries.BLOCK.getKey(this.state.getBlock()).getPath().contains("shower_head")) {
            return;
        }

        var pos = attachment.getBlockPos();
        var blockEntity = world.getBlockEntity(pos);
        if (!showerOpen(blockEntity) || world.getGameTime() % 3L != 0L) {
            return;
        }

        var facing = this.state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? this.state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
        double x = 0.5;
        double z = 0.5;
        switch (facing) {
            case WEST -> {
                x = 0.55;
                z = 0.5;
            }
            case NORTH -> {
                x = 0.5;
                z = 0.55;
            }
            case SOUTH -> {
                x = 0.5;
                z = 0.45;
            }
            case EAST -> {
                x = 0.45;
                z = 0.5;
            }
            default -> {
            }
        }

        world.sendParticles(ParticleTypes.FALLING_WATER,
                pos.getX() + x, pos.getY() + 0.2, pos.getZ() + z,
                2, 0.1, 0.0, 0.1, 0.0);
    }

    private static boolean showerOpen(@Nullable Object blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        // The mod keeps the valve as a protected field and exposes it through an isOpen() getter;
        // the getter is used first because a protected field is invisible to getField.
        try {
            return (boolean) blockEntity.getClass().getMethod("isOpen").invoke(blockEntity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            try {
                var field = blockEntity.getClass().getDeclaredField("isOpen");
                field.setAccessible(true);
                return field.getBoolean(blockEntity);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
    }


    private static boolean toasting(@Nullable Object blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        try {
            return (boolean) blockEntity.getClass().getMethod("isToasting").invoke(blockEntity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean boolProperty(BlockState state, String name) {
        var property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            return false;
        }
        return state.getValue(property) instanceof Boolean value && value;
    }

    private static boolean microwaveActive(Object blockEntity) {
        try {
            return blockEntity.getClass().getField("isActive").getBoolean(blockEntity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }


    private static ItemDisplayElement createElement() {
        var element = new ItemDisplayElement();
        element.setItemDisplayContext(ItemDisplayContext.NONE);
        element.setViewRange(2f);
        element.setDisplaySize(2, 2);
        element.setTeleportDuration(0);
        // Item space is turned around relative to block space, so the blockstate's own rotation has
        // to be turned back by 180 degrees before it matches what a client with the mod draws.
        element.setYaw(180);
        return element;
    }
}
