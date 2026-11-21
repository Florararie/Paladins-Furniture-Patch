package eu.pb4.paladinsfurniturepatch.impl;

import com.mojang.serialization.JsonOps;
import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.polymer.resourcepack.extras.api.ResourcePackExtras;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.BlockStateAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateModelVariant;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateMultiPartDefinition;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

import org.jspecify.annotations.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which models a furniture block draws, per block state.
 *
 * <p>The mod's blockstate files are read as they are, from the generated asset pack when it exists,
 * the mod's jar when it is static, and the registry-generated equivalent otherwise, so nothing has
 * to be regenerated here when the mod changes. They are plain {@code variants} maps with rotations
 * and a handful of {@code multipart} files, which keeps the parsing small.
 *
 * <p>Single-model furniture draws exactly what its blockstate names. Beds, lamps, nightstands,
 * cabinets, fridges and chairs are different: their blockstate names a model type, and the mod's
 * client code decides which of several model parts that type draws from the block state and its
 * neighbours. That selection is reproduced here, part for part, so the same frame, mattress, shade,
 * bulb and open door are drawn with a client that does not have the mod.
 */
public final class FurnitureModels {
    /** One model to draw, and how far it has to be rotated to match the blockstate. */
    public record Part(Identifier model, Quaternionf rotation) {
        /**
         * Item components are not bound during mod initialisation, so the stack an element draws is
         * created the first time a placed block asks for it rather than while parsing.
         */
        public ItemStack stack() {
            return STACKS.computeIfAbsent(this.model, FurnitureModels::stackFor);
        }
    }

    private static final Map<BlockState, List<Part>> MODELS = new IdentityHashMap<>();
    private static final Map<BlockState, Quaternionf> ROTATIONS = new IdentityHashMap<>();
    private static final Map<Identifier, ItemStack> STACKS = new HashMap<>();

    private FurnitureModels() {
    }

    public static void setup() {
        if (FabricLoader.getInstance().getModContainer(PaladinsFurniturePatch.TARGET_MOD_ID).isEmpty()) {
            PaladinsFurniturePatch.LOGGER.error("Paladin's Furniture is missing, so no models can be loaded");
            return;
        }

        int blocks = 0;
        int withModels = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!id.getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }

            blocks++;
            if (load(block)) {
                withModels++;
            }
            prepare(block);
        }

        // A state without a model is furniture that renders as nothing, so it is worth saying out
        // loud how much of the mod is covered by the assets that are available.
        int states = 0;
        int missing = 0;
        for (var block : BuiltInRegistries.BLOCK) {
            if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(PaladinsFurniturePatch.TARGET_MOD_ID)) {
                continue;
            }
            for (var state : block.getStateDefinition().getPossibleStates()) {
                states++;
                if (!MODELS.containsKey(state)) {
                    missing++;
                }
            }
        }

        PaladinsFurniturePatch.LOGGER.info("Loaded models for {} of {} furniture blocks ({} of {} states, {} without a model).",
                withModels, blocks, MODELS.size(), states, missing);
    }

    /** Builds every part of a multi-part furniture block, so a pack can reference all of them. */
    public static void prepare(Block block) {
        for (var spec : PFMParts.specs(block)) {
            PFMBlockStates.part(block, spec.key(), spec.template());
        }
    }

    public static List<Part> get(BlockState state) {
        return get(state, null, null);
    }

    public static List<Part> get(BlockState state, @Nullable BlockGetter level,
                                 @Nullable BlockPos pos) {
        var keys = select(state, level, pos);
        if (keys != null) {
            var rotation = ROTATIONS.getOrDefault(state, new Quaternionf());
            var family = PFMBlockStates.family(state.getBlock());
            var lampWood = "basic_lamp".equals(family) ? lampWood(level, pos) : null;
            var lampColor = "basic_lamp".equals(family) ? lampColor(level, pos) : null;
            var parts = new ArrayList<Part>(keys.size());
            for (var key : keys) {
                // All lamps are one block, so the pole has to come from the wood on the block
                // entity rather than from the block's own part table.
                var model = lampWood != null && PFMBlockStates.isLampPole(key)
                        ? PFMBlockStates.lampPart(lampWood, key)
                        : null;
                if (model == null && lampColor != null && "l4".equals(key)) {
                    model = PFMBlockStates.lampShadeModel(lampColor);
                }
                if (model == null) {
                    model = PFMBlockStates.partId(state.getBlock(), key);
                }
                if (model != null) {
                    parts.add(new Part(model, rotation));
                }
            }
            if (!parts.isEmpty()) {
                return List.copyOf(parts);
            }
        }

        return MODELS.getOrDefault(state, List.of());
    }

    /** Whether this block's models can change without its own block state changing. */
    public static boolean needsNeighbours(BlockState state) {
        var family = PFMBlockStates.family(state.getBlock());
        return family != null && switch (family) {
            case "simple_bed", "basic_lamp", "classic_nightstand", "kitchen_cabinet", "kitchen_drawer",
                    "kitchen_counter_oven", "fridge", "freezer", "microwave", "desk_cabinet_basic",
                    "table_basic", "coffee_table_basic", "table_classic", "coffee_table_classic",
                    "log_table", "dinner_table", "modern_dinner_table", "coffee_table_modern",
                    "kitchen_wall_drawer", "kitchen_wall_small_drawer", "kitchen_wall_counter",
                    "iron_fridge", "kitchen_counter", "simple_bunk_ladder", "desk_basic" -> true;
            default -> false;
        };
    }

    private static boolean load(Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        // The same rewritten blockstate that is sent to the client, so the models drawn on the
        // server and the models the client can resolve always agree.
        var plain = PFMBlockStates.plain(block);
        if (plain == null) {
            return false;
        }

        try {
            var asset = BlockStateAsset.CODEC.decode(JsonOps.INSTANCE, plain)
                    .getOrThrow().getFirst();
            var states = block.getStateDefinition().getPossibleStates();

            asset.variants().ifPresent(variants -> {
                for (var entry : variants.entrySet()) {
                    var predicate = parseKey(block, entry.getKey());
                    if (predicate == null) {
                        continue;
                    }
                    var parts = toParts(entry.getValue());
                    for (var state : states) {
                        if (predicate.test(state)) {
                            MODELS.put(state, parts);
                            if (!parts.isEmpty()) {
                                ROTATIONS.put(state, parts.getFirst().rotation());
                            }
                        }
                    }
                }
            });

            asset.multipart().ifPresent(parts -> {
                for (var part : parts) {
                    var predicate = part.when()
                            .map(condition -> parseCondition(block, condition))
                            .orElse(state -> true);
                    var models = toParts(part.apply());
                    if (models.isEmpty()) {
                        continue;
                    }
                    for (var state : states) {
                        if (predicate.test(state)) {
                            MODELS.computeIfAbsent(state, key -> new ArrayList<>()).addAll(models);
                            if (!ROTATIONS.containsKey(state)) {
                                ROTATIONS.put(state, models.getFirst().rotation());
                            }
                        }
                    }
                }
            });

            return true;
        } catch (Throwable throwable) {
            PaladinsFurniturePatch.LOGGER.warn("Could not read models for {}", id, throwable);
            return false;
        }
    }

    private static List<Part> toParts(List<StateModelVariant> variants) {
        var parts = new ArrayList<Part>(variants.size());
        for (var variant : variants) {
            parts.add(new Part(variant.model(), rotationOf(variant)));
        }
        return List.copyOf(parts);
    }

    private static Quaternionf rotationOf(StateModelVariant variant) {
        return new Quaternionf()
                .rotateY(-Mth.DEG_TO_RAD * variant.y())
                .rotateX(Mth.DEG_TO_RAD * variant.x());
    }

    private static ItemStack stackFor(Identifier model) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, ResourcePackExtras.bridgeModel(model));
        return stack;
    }

    /**
     * The parts a multi-part family draws for one state, or null when the family is not one of
     * those - in which case the blockstate's own models are used.
     *
     * <p>Neighbour checks are skipped when there is no world, which is the case while a pack is
     * being built. A part only draws an attached edge, so the standalone selection is the right
     * default for an unloaded block and the holder re-checks once it is attached.
     */
    private static @Nullable List<String> select(
            BlockState state, @Nullable BlockGetter level,
            @Nullable BlockPos pos) {
        var family = PFMBlockStates.family(state.getBlock());
        if (family == null) {
            return null;
        }

        var path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return switch (family) {
            case "simple_bed" -> bed(state, level, pos, path.contains("classic"));
            case "basic_lamp" -> lamp(state, level, pos);
            case "classic_nightstand" -> nightstand(state, level, pos);
            case "kitchen_cabinet" -> cabinet(state, level, pos);
            case "kitchen_drawer" -> drawer(state, level, pos);
            case "kitchen_counter_oven" -> oven(state, level, pos);
            case "fridge" -> fridge(state, level, pos);
            case "iron_fridge" -> ironFridge(state, level, pos);
            case "freezer" -> freezer(state, level, pos);
            case "chair", "chair_classic", "chair_dinner", "chair_modern" -> chairs(state);
            case "simple_stool", "classic_stool", "modern_stool", "log_stool" -> stools(state);
            case "desk_basic" -> basicDesk(state, level, pos);
            case "desk_cabinet_basic" -> deskCabinet(state, level, pos);
            case "table_basic" -> basicTable(state, level, pos, "bt");
            case "coffee_table_basic" -> basicTable(state, level, pos, "cb");
            case "table_classic" -> classicTable(state, level, pos, "ct");
            case "coffee_table_classic" -> classicTable(state, level, pos, "cc");
            case "log_table" -> sideTable(state, level, pos, "lt");
            case "dinner_table" -> sideTable(state, level, pos, "dt");
            case "modern_dinner_table" -> axisTable(state, level, pos, "md");
            case "coffee_table_modern" -> axisTable(state, level, pos, "cm");
            case "kitchen_wall_drawer" -> wallCounterLike(state, level, pos, "wd", true);
            case "kitchen_wall_small_drawer" -> wallSmallDrawer(state);
            case "kitchen_wall_counter" -> wallCounterLike(state, level, pos, "wc", false);
            case "kitchen_counter" -> kitchenCounter(state, level, pos);
            case "simple_bunk_ladder" -> bunkLadder(state);
            default -> null;
        };
    }

    private static List<String> bed(BlockState state, @Nullable BlockGetter level,
                                    @Nullable BlockPos pos, boolean classic) {
        var prefix = classic ? "c" : "s";
        var keys = new ArrayList<String>(4);
        boolean head = state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD;

        boolean left = false;
        boolean right = false;
        boolean bunk = false;
        if (level != null && pos != null && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            left = sameBed(level, pos, state, facing.getCounterClockWise());
            right = sameBed(level, pos, state, facing.getClockWise());
            // A bunk is another bed directly below with the same part and the same facing, exactly
            // what BedInterface.isBed checks.
            bunk = sameBed(level, pos, state, Direction.DOWN);
        }

        if (head) {
            keys.add(prefix + "1");
            keys.add(prefix + "3");
            if (!right) {
                keys.add(prefix + "6");
            }
            if (!left) {
                keys.add(prefix + "7");
            }
            if (bunk && !classic) {
                keys.add(prefix + "10");
            }
        } else {
            keys.add(prefix + "0");
            keys.add(prefix + "2");
            if (!right) {
                keys.add(prefix + "4");
            }
            if (!left) {
                keys.add(prefix + "5");
            }
            if (!right && bunk) {
                keys.add(prefix + "8");
            }
            if (!left && bunk) {
                keys.add(prefix + "9");
            }
        }

        return keys;
    }

    private static boolean sameBed(BlockGetter level, BlockPos pos, BlockState state, net.minecraft.core.Direction direction) {
        var other = level.getBlockState(pos.relative(direction));
        if (other.getBlock() != state.getBlock()) {
            return false;
        }
        // A bed only joins the same half: a head does not connect to another bed's foot, and a
        // bunk is the same half directly above or below.
        if (state.hasProperty(BlockStateProperties.BED_PART) && other.hasProperty(BlockStateProperties.BED_PART)
                && other.getValue(BlockStateProperties.BED_PART) != state.getValue(BlockStateProperties.BED_PART)) {
            return false;
        }
        return !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || !other.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || other.getValue(BlockStateProperties.HORIZONTAL_FACING) == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static List<String> lamp(BlockState state, @Nullable BlockGetter level,
                                     @Nullable BlockPos pos) {
        boolean up = level != null && pos != null && level.getBlockState(pos.above()).getBlock() == state.getBlock();
        boolean down = level != null && pos != null && level.getBlockState(pos.below()).getBlock() == state.getBlock();
        boolean lit = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
        var bulb = lit ? "l6" : "l5";

        var keys = new ArrayList<String>(4);
        if (up && down) {
            keys.add("l1");
        } else if (up) {
            keys.add("l0");
        } else if (down) {
            keys.add("l3");
            keys.add(bulb);
            keys.add("l4");
        } else {
            keys.add("l4");
            keys.add("l2");
            keys.add(bulb);
        }
        return keys;
    }

    private static List<String> nightstand(BlockState state, @Nullable BlockGetter level,
                                           @Nullable BlockPos pos) {
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 4 : 0;

        boolean left = false;
        boolean right = false;
        if (level != null && pos != null && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            left = sameFurniture(level, pos, state, facing.getCounterClockWise());
            right = sameFurniture(level, pos, state, facing.getClockWise());
        }

        int index;
        if (left && right) {
            index = 0;
        } else if (!left && right) {
            index = 1;
        } else if (left) {
            index = 2;
        } else {
            index = 3;
        }
        return List.of("n" + (offset + index));
    }

    private static boolean sameFurniture(BlockGetter level, BlockPos pos, BlockState state, net.minecraft.core.Direction direction) {
        var other = level.getBlockState(pos.relative(direction));
        if (other.getBlock() != state.getBlock()) {
            return false;
        }
        return !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || !other.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || other.getValue(BlockStateProperties.HORIZONTAL_FACING) == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static List<String> cabinet(BlockState state, @Nullable BlockGetter level,
                                        @Nullable BlockPos pos) {
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 5 : 0;

        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return List.of("k" + offset);
        }

        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        var front = level.getBlockState(pos.relative(facing));
        var opposite = level.getBlockState(pos.relative(facing.getOpposite()));

        // The mod checks the cabinet in front first: if it faces across this one and the block
        // behind that cabinet is free, this is the outer corner.
        Direction frontDirection = null;
        boolean outerCorner = false;
        if (front.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            frontDirection = front.getValue(BlockStateProperties.HORIZONTAL_FACING);
            outerCorner = frontDirection.getAxis() != facing.getAxis()
                    && isDifferentOrientation(level, pos, frontDirection.getOpposite());
        }

        // Otherwise the cabinet behind decides whether this is an inner corner.
        Direction innerDirection = null;
        boolean innerCorner = false;
        if (isCabinet(opposite) && opposite.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            innerDirection = opposite.getValue(BlockStateProperties.HORIZONTAL_FACING);
            innerCorner = innerDirection.getAxis() != facing.getAxis()
                    && isDifferentOrientation(level, pos, innerDirection);
        }

        if (isCabinet(front) && frontDirection != null && outerCorner) {
            return List.of("k" + ((frontDirection == facing.getCounterClockWise() ? 3 : 4) + offset));
        }
        if (innerCorner && innerDirection != null) {
            return List.of("k" + ((innerDirection == facing.getCounterClockWise() ? 2 : 1) + offset));
        }

        return List.of("k" + offset);
    }

    private static boolean isCabinet(BlockState other) {
        return "kitchen_cabinet".equals(PFMBlockStates.family(other.getBlock()));
    }

    private static List<String> drawer(BlockState state, @Nullable BlockGetter level,
                                       @Nullable BlockPos pos) {
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 7 : 0;

        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return List.of("d" + offset);
        }

        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean right = counterConnects(level.getBlockState(pos.relative(facing.getCounterClockWise())));
        boolean left = counterConnects(level.getBlockState(pos.relative(facing.getClockWise())));
        var front = level.getBlockState(pos.relative(facing));
        var opposite = level.getBlockState(pos.relative(facing.getOpposite()));

        if (counterConnects(front) && front.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = front.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (other.getAxis() != facing.getAxis()
                    && !counterConnects(level.getBlockState(pos.relative(other.getOpposite())))) {
                return List.of("d" + (offset + (other == facing.getCounterClockWise() ? 5 : 6)));
            }
            return List.of("d" + (offset + middleIndex(left, right)));
        }

        if (counterConnects(opposite) && opposite.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = opposite.getValue(BlockStateProperties.HORIZONTAL_FACING);
            var direction = opposite.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                    ? other.getOpposite()
                    : other;
            if (direction.getAxis() != facing.getAxis()
                    && !counterConnects(level.getBlockState(pos.relative(direction)))) {
                return List.of("d" + (offset + (direction == facing.getCounterClockWise() ? 4 : 3)));
            }
        }

        return List.of("d" + (offset + middleIndex(left, right)));
    }

    /** The counter/drawer middle model: 0 joined, 1 left edge, 2 right edge, 0 standalone. */
    private static int middleIndex(boolean left, boolean right) {
        if (left && right) {
            return 0;
        }
        return left ? 1 : right ? 2 : 0;
    }

    private static boolean counterConnects(BlockState state) {
        return counter(state)
                || state.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock;
    }

    private static List<String> oven(BlockState state, @Nullable BlockGetter level,
                                     @Nullable BlockPos pos) {
        boolean connected = false;
        if (level != null && pos != null) {
            connected = isOven(level.getBlockState(pos.above())) || isOven(level.getBlockState(pos.below()));
        }
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        return List.of("o" + ((open ? 2 : 0) + (connected ? 1 : 0)));
    }

    private static List<String> kitchenCounter(BlockState state, @Nullable BlockGetter level,
                                               @Nullable BlockPos pos) {
        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return List.of("kc0");
        }

        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean right = counterConnects(level.getBlockState(pos.relative(facing.getCounterClockWise())));
        boolean left = counterConnects(level.getBlockState(pos.relative(facing.getClockWise())));
        var front = level.getBlockState(pos.relative(facing));
        var opposite = level.getBlockState(pos.relative(facing.getOpposite()));

        if (counterConnects(front) && front.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = front.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (other.getAxis() != facing.getAxis()
                    && !counterConnects(level.getBlockState(pos.relative(other.getOpposite())))) {
                return List.of("kc" + (other == facing.getCounterClockWise() ? 5 : 6));
            }
            return List.of("kc" + middleIndex(left, right));
        }

        if (counterConnects(opposite) && opposite.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = opposite.getValue(BlockStateProperties.HORIZONTAL_FACING);
            var direction = opposite.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                    ? other.getOpposite()
                    : other;
            if (direction.getAxis() != facing.getAxis()
                    && !counterConnects(level.getBlockState(pos.relative(direction)))) {
                return List.of("kc" + (direction == facing.getCounterClockWise() ? 4 : 3));
            }
        }

        return List.of("kc" + middleIndex(left, right));
    }

    private static List<String> ironFridge(BlockState state, @Nullable BlockGetter level,
                                           @Nullable BlockPos pos) {
        boolean bottom = level != null && pos != null && level.getBlockState(pos.above()).getBlock() == state.getBlock();
        boolean top = level != null && pos != null && level.getBlockState(pos.below()).getBlock() == state.getBlock();
        boolean freezerBelow = false;
        if (level != null && pos != null) {
            freezerBelow = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock())
                    .getPath().contains("iron_freezer");
        }

        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 5 : 0;
        int index;
        if (top && bottom) {
            index = 2;
        } else if (bottom) {
            index = 3;
        } else if (top) {
            index = 1;
        } else if (freezerBelow) {
            index = 4;
        } else {
            index = 0;
        }
        return List.of("if" + (offset + index));
    }

    private static List<String> bunkLadder(BlockState state) {
        return List.of(bool(state, "up") ? "sl1" : "sl0");
    }

    private static boolean isOven(BlockState state) {
        var path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("kitchen_counter_oven") || path.contains("kitchen_counter");
    }

    private static boolean isDifferentOrientation(BlockGetter level, BlockPos pos, Direction direction) {
        return !isCabinet(level.getBlockState(pos.relative(direction)));
    }

    private static List<String> fridge(BlockState state, @Nullable BlockGetter level,
                                       @Nullable BlockPos pos) {
        boolean bottom = level != null && pos != null && level.getBlockState(pos.above()).getBlock() == state.getBlock();
        boolean top = level != null && pos != null && level.getBlockState(pos.below()).getBlock() == state.getBlock();
        boolean freezerAbove = false;
        if (level != null && pos != null) {
            freezerAbove = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.above()).getBlock())
                    .getPath().contains("freezer");
        }

        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 6 : 0;

        int index;
        if (top && freezerAbove) {
            index = 5;
        } else if (top && bottom) {
            index = 2;
        } else if (bottom) {
            index = 3;
        } else if (top) {
            index = 1;
        } else if (freezerAbove) {
            index = 4;
        } else {
            index = 0;
        }
        return List.of("f" + (offset + index));
    }

    private static List<String> freezer(BlockState state, @Nullable BlockGetter level,
                                        @Nullable BlockPos pos) {
        boolean fridgeBelow = false;
        if (level != null && pos != null) {
            fridgeBelow = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock())
                    .getPath().contains("fridge");
        }

        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 2 : 0;
        return List.of("z" + (offset + (fridgeBelow ? 1 : 0)));
    }

    private static boolean lampResolved;
    private static Method lampGetVariant;
    private static Method variantGetSerializedName;

    /**
     * The wood a lamp block entity was built from, in PFM's own naming ({@code oak},
     * {@code umbral_cinderscapes}), or null if it cannot be read.
     */
    private static @Nullable String lampWood(
            @Nullable BlockGetter level,
            @Nullable BlockPos pos) {
        if (!(level instanceof Level world) || pos == null) {
            return null;
        }

        try {
            if (!lampResolved) {
                lampResolved = true;
                var entity = Class.forName("com.unlikepaladin.pfm.blocks.blockentities.LampBlockEntity");
                lampGetVariant = entity.getMethod("getVariant");
            }
            if (lampGetVariant == null) {
                return null;
            }

            var blockEntity = world.getBlockEntity(pos);
            if (blockEntity == null) {
                return null;
            }

            var variant = lampGetVariant.invoke(blockEntity);
            if (variant == null) {
                return null;
            }
            if (variantGetSerializedName == null) {
                variantGetSerializedName = variant.getClass().getMethod("getSerializedName");
            }
            var name = variantGetSerializedName.invoke(variant);
            return name instanceof String wood && !wood.isBlank() ? wood : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static @Nullable String lampColor(
            @Nullable BlockGetter level,
            @Nullable BlockPos pos) {
        if (!(level instanceof Level world) || pos == null) {
            return null;
        }

        try {
            var blockEntity = world.getBlockEntity(pos);
            if (blockEntity == null) {
                return null;
            }
            var method = blockEntity.getClass().getMethod("getPFMColor");
            var value = method.invoke(blockEntity);
            if (value instanceof net.minecraft.world.item.DyeColor color) {
                return color.getName();
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Older PFM versions store the colour only on the block; white is the safe default.
        }
        return null;
    }

    private static List<String> chairs(BlockState state) {
        return List.of(bool(state, "tucked") ? "ch1" : "ch0");
    }

    private static List<String> stools(BlockState state) {
        return List.of(bool(state, "tucked") ? "st1" : "st0");
    }


    private static boolean connects(BlockState state, BlockState other) {
        var family = PFMBlockStates.family(state.getBlock());
        var otherFamily = PFMBlockStates.family(other.getBlock());
        if (family == null || otherFamily == null) {
            return false;
        }
        if (family.equals(otherFamily)) {
            // Basic and coffee tables only connect along their own axis; two tables crossing at a
            // right angle meet at a corner, not as one continuous table.
            if ("table_basic".equals(family) || "coffee_table_basic".equals(family)) {
                // These blocks declare HORIZONTAL_AXIS, not the vertical AXIS property. Checking
                // AXIS made every neighbour fail and left each table in its standalone model.
                return state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                        && other.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                        && state.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                        == other.getValue(BlockStateProperties.HORIZONTAL_AXIS);
            }
            return true;
        }
        // A basic desk cabinet forms its legs and corners against a basic desk of the same wood.
        return ("desk_cabinet_basic".equals(family) && "desk_basic".equals(otherFamily))
                || ("desk_basic".equals(family) && "desk_cabinet_basic".equals(otherFamily));
    }

    private static boolean connects(BlockGetter level, BlockPos pos, BlockState state, Direction direction) {
        return connects(state, level.getBlockState(pos.relative(direction)));
    }

    private static boolean counter(BlockState state) {
        var family = PFMBlockStates.family(state.getBlock());
        return family != null && switch (family) {
            case "kitchen_counter", "kitchen_drawer", "kitchen_cabinet", "kitchen_counter_oven",
                    "kitchen_wall_counter", "kitchen_wall_drawer", "kitchen_wall_small_drawer",
                    "kitchen_sink", "kitchen_stovetop", "stove" -> true;
            default -> false;
        };
    }

    private static boolean isDeskCabinet(BlockState other) {
        return "desk_cabinet_basic".equals(PFMBlockStates.family(other.getBlock()));
    }

    private static List<String> basicTable(BlockState state, @Nullable BlockGetter level,
                                           @Nullable BlockPos pos, String prefix) {
        boolean north = level != null && pos != null && connects(level, pos, state, Direction.NORTH);
        boolean east = level != null && pos != null && connects(level, pos, state, Direction.EAST);
        boolean west = level != null && pos != null && connects(level, pos, state, Direction.WEST);
        boolean south = level != null && pos != null && connects(level, pos, state, Direction.SOUTH);
        boolean cornerNorthWest = north && west && level != null && pos != null
                && !connects(state, level.getBlockState(pos.north().west()));
        boolean cornerNorthEast = north && east && level != null && pos != null
                && !connects(state, level.getBlockState(pos.north().east()));
        boolean cornerSouthWest = south && west && level != null && pos != null
                && !connects(state, level.getBlockState(pos.south().west()));
        boolean cornerSouthEast = south && east && level != null && pos != null
                && !connects(state, level.getBlockState(pos.south().east()));

        var keys = new ArrayList<String>();
        keys.add(prefix + "0");

        if (!north && !south && !east && !west) {
            keys.add(prefix + "8");
            keys.add(prefix + "7");
        }

        var axis = state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                ? state.getValue(BlockStateProperties.HORIZONTAL_AXIS)
                : Direction.Axis.Z;
        if (axis == Direction.Axis.Z) {
            if (!north && !east) keys.add(prefix + "1");
            if (!north && !west) keys.add(prefix + "2");
            if (!south && !east) keys.add(prefix + "3");
            if (!south && !west) keys.add(prefix + "4");
            if (!north && south && !east && !west) keys.add(prefix + "7");
            if (north && !south && !east && !west) keys.add(prefix + "8");
            if (!north && east && !west) keys.add(prefix + "5");
            if (!south && !east && west) keys.add(prefix + "10");
            if (!south && east && !west) keys.add(prefix + "9");
            if (!north && !east && west) keys.add(prefix + "6");
            if (!north && east && west) keys.add(prefix + "12");
            if (!south && east && west) keys.add(prefix + "11");
            if (cornerNorthEast) {
                keys.add(prefix + "13");
                keys.add(prefix + "1");
            }
            if (cornerNorthWest) {
                keys.add(prefix + "14");
                keys.add(prefix + "2");
            }
            if (cornerSouthWest) {
                keys.add(prefix + "16");
                keys.add(prefix + "4");
            }
            if (cornerSouthEast) {
                keys.add(prefix + "15");
                keys.add(prefix + "3");
            }
        } else {
            if (!north && !east) keys.add(prefix + "2");
            if (!north && !west) keys.add(prefix + "4");
            if (!south && !east) keys.add(prefix + "1");
            if (!south && !west) keys.add(prefix + "3");
            if (!north && south && !west) keys.add(prefix + "9");
            if (north && !south && !west) keys.add(prefix + "10");
            if (!north && south && !east) keys.add(prefix + "5");
            if (north && !south && !east) keys.add(prefix + "6");
            if (!north && !south && !east) keys.add(prefix + "7");
            if (!north && !south && !west) keys.add(prefix + "8");
            if (north && south && !east) keys.add(prefix + "12");
            if (north && south && !west) keys.add(prefix + "11");
            if (cornerNorthEast) {
                keys.add(prefix + "14");
                keys.add(prefix + "2");
            }
            if (cornerSouthEast) {
                keys.add(prefix + "13");
                keys.add(prefix + "1");
            }
            if (cornerNorthWest) {
                keys.add(prefix + "16");
                keys.add(prefix + "4");
            }
            if (cornerSouthWest) {
                keys.add(prefix + "15");
                keys.add(prefix + "3");
            }
        }

        return keys;
    }

    private static List<String> classicTable(BlockState state, @Nullable BlockGetter level,
                                             @Nullable BlockPos pos, String prefix) {
        boolean north = level != null && pos != null && connects(level, pos, state, Direction.NORTH);
        boolean east = level != null && pos != null && connects(level, pos, state, Direction.EAST);
        boolean west = level != null && pos != null && connects(level, pos, state, Direction.WEST);
        boolean south = level != null && pos != null && connects(level, pos, state, Direction.SOUTH);

        var keys = new ArrayList<String>();
        keys.add(prefix + "0");
        if (!north && !east) keys.add(prefix + "1");
        if (!north && !west) keys.add(prefix + "2");
        if (!south && !west) keys.add(prefix + "3");
        if (!south && !east) keys.add(prefix + "4");
        return keys;
    }

    private static List<String> sideTable(BlockState state, @Nullable BlockGetter level,
                                          @Nullable BlockPos pos, String prefix) {
        var keys = new ArrayList<String>();
        keys.add(prefix + "0");
        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return keys;
        }

        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean left = sameFacingTable(level, pos, state, facing.getCounterClockWise());
        boolean right = sameFacingTable(level, pos, state, facing.getClockWise());

        if (!left && right) {
            keys.add(prefix + (facing == Direction.NORTH || facing == Direction.WEST ? "1" : "2"));
        }
        if (!right && left) {
            keys.add(prefix + (facing == Direction.NORTH || facing == Direction.WEST ? "2" : "1"));
        }
        if (!right && !left) {
            keys.add(prefix + "3");
        }
        return keys;
    }

    private static boolean sameFacingTable(BlockGetter level, BlockPos pos, BlockState state, Direction direction) {
        var other = level.getBlockState(pos.relative(direction));
        return connects(state, other)
                && other.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && other.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static List<String> axisTable(BlockState state, @Nullable BlockGetter level,
                                          @Nullable BlockPos pos, String prefix) {
        var keys = new ArrayList<String>();
        keys.add(prefix + "0");
        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return keys;
        }

        var axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        boolean left = axisTable(level, pos, state, axis, -1);
        boolean right = axisTable(level, pos, state, axis, 1);

        if (left && right) keys.add(prefix + "4");
        if (!left && right) keys.add(prefix + "1");
        if (!right && left) keys.add(prefix + "2");
        if (!right && !left) keys.add(prefix + "3");
        return keys;
    }

    private static boolean axisTable(BlockGetter level, BlockPos pos, BlockState state, Direction.Axis axis, int offset) {
        var other = level.getBlockState(pos.relative(axis, offset));
        return connects(state, other)
                && other.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)
                && other.getValue(BlockStateProperties.HORIZONTAL_AXIS) == axis;
    }

    /**
     * A basic desk draws its top and each missing corner leg, exactly like the mod's own renderer.
     * The leg model indices are the mod's order: north-west, north-east, south-west, south-east.
     */
    private static List<String> basicDesk(BlockState state, @Nullable BlockGetter level,
                                          @Nullable BlockPos pos) {
        var keys = new ArrayList<String>();
        keys.add("db0");
        if (level == null || pos == null) {
            return keys;
        }

        boolean north = connects(level, pos, state, Direction.NORTH);
        boolean south = connects(level, pos, state, Direction.SOUTH);
        boolean east = connects(level, pos, state, Direction.EAST);
        boolean west = connects(level, pos, state, Direction.WEST);

        if (!north && !west) keys.add("db1");
        if (!north && !east) keys.add("db2");
        if (!south && !west) keys.add("db3");
        if (!south && !east) keys.add("db4");
        return keys;
    }

    private static List<String> deskCabinet(BlockState state, @Nullable BlockGetter level,
                                            @Nullable BlockPos pos) {
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 8 : 0;
        var keys = new ArrayList<String>();

        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            keys.add("dc" + offset);
            keys.add("dc16");
            keys.add("dc17");
            keys.add("dc18");
            keys.add("dc19");
            return keys;
        }

        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean north = connects(level, pos, state, Direction.NORTH);
        boolean south = connects(level, pos, state, Direction.SOUTH);
        boolean east = connects(level, pos, state, Direction.EAST);
        boolean west = connects(level, pos, state, Direction.WEST);

        boolean left = isDeskCabinet(level.getBlockState(pos.relative(facing.getCounterClockWise())));
        boolean right = isDeskCabinet(level.getBlockState(pos.relative(facing.getClockWise())));

        int index;
        if (left && right) {
            index = 3;
        } else if (left) {
            index = 1;
        } else if (right) {
            index = 2;
        } else {
            index = 0;
        }

        var front = level.getBlockState(pos.relative(facing));
        var opposite = level.getBlockState(pos.relative(facing.getOpposite()));
        if (connects(state, front) && front.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = front.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (other.getAxis() != facing.getAxis()
                    && !connects(state, level.getBlockState(pos.relative(other.getOpposite())))) {
                index = other == facing.getCounterClockWise() ? 4 : 5;
            }
        } else if (connects(state, opposite) && opposite.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = opposite.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (other.getAxis() != facing.getAxis()
                    && !connects(state, level.getBlockState(pos.relative(other)))) {
                index = other == facing.getCounterClockWise() ? 6 : 7;
            }
        }

        keys.add("dc" + (offset + index));
        legsDesk(keys, facing, north, south, east, west);
        return keys;
    }

    /**
     * The desk cabinet's four legs, exactly the way PFM picks them.
     *
     * <p>The mod's call passes its east/west checks in the opposite slots to the method's parameters,
     * and the body compensates with its conditions. Both are kept as they are so each facing draws
     * the same legs a client with the mod sees.
     */
    private static void legsDesk(List<String> keys, Direction facing, boolean north, boolean south,
                                 boolean east, boolean west) {
        int northLeg;
        int southLeg;
        int westLeg;
        int eastLeg;
        switch (facing) {
            case SOUTH -> {
                northLeg = 18;
                southLeg = 19;
                westLeg = 16;
                eastLeg = 17;
            }
            case NORTH -> {
                northLeg = 17;
                southLeg = 16;
                westLeg = 19;
                eastLeg = 18;
            }
            case EAST -> {
                northLeg = 19;
                southLeg = 17;
                westLeg = 18;
                eastLeg = 16;
            }
            default -> {
                northLeg = 16;
                southLeg = 18;
                westLeg = 17;
                eastLeg = 19;
            }
        }
        legsDesk(keys, north, south, east, west, northLeg, southLeg, westLeg, eastLeg);
    }

    private static void legsDesk(List<String> keys, boolean north, boolean south, boolean west, boolean east,
                                 int northLeg, int southLeg, int westLeg, int eastLeg) {
        if (!north && !east) keys.add("dc" + northLeg);
        if (!north && !west) keys.add("dc" + southLeg);
        if (!south && !west) keys.add("dc" + eastLeg);
        if (!south && !east) keys.add("dc" + westLeg);
    }

    private static List<String> wallCounterLike(BlockState state, @Nullable BlockGetter level,
                                                @Nullable BlockPos pos,
                                                String prefix, boolean openable) {
        boolean open = openable && state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN);
        int offset = open ? 5 : 0;

        if (level == null || pos == null || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return List.of(prefix + offset);
        }

        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        var front = level.getBlockState(pos.relative(facing));
        var opposite = level.getBlockState(pos.relative(facing.getOpposite()));

        if (counter(front) && front.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = front.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (other.getAxis() != facing.getAxis()
                    && !counter(level.getBlockState(pos.relative(other.getOpposite())))) {
                return List.of(prefix + ((other == facing.getCounterClockWise() ? 3 : 4) + offset));
            }
        } else if (counter(opposite) && opposite.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var other = opposite.getValue(BlockStateProperties.HORIZONTAL_FACING);
            var probe = opposite.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                    ? facing.getOpposite()
                    : facing;
            if (other.getAxis() != facing.getAxis()
                    && !counter(level.getBlockState(pos.relative(probe)))) {
                return List.of(prefix + ((other == facing.getCounterClockWise() ? 2 : 1) + offset));
            }
        }

        return List.of(prefix + offset);
    }

    private static List<String> wallSmallDrawer(BlockState state) {
        boolean open = state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        return List.of("ws" + (open ? 1 : 0));
    }

    private static boolean bool(BlockState state, String propertyName) {
        var property = state.getBlock().getStateDefinition().getProperty(propertyName);
        if (property == null) {
            return false;
        }
        var value = state.getValue(property);
        return value instanceof Boolean flag && flag;
    }

    /** Turns a {@code "facing=north,half=lower"} variant key into a state predicate. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate<BlockState> parseKey(Block block, String key) {
        var predicate = BlockStatePredicate.forBlock(block);
        if (key.isEmpty()) {
            return predicate;
        }

        for (var pair : key.split(",")) {
            var split = pair.split("=", 2);
            if (split.length != 2) {
                return null;
            }
            var property = (Property) block.getStateDefinition().getProperty(split[0]);
            if (property == null) {
                return null;
            }
            predicate.where(property, value -> property.getName((Comparable) value).equals(split[1]));
        }

        return predicate;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate<BlockState> parseCondition(Block block, StateMultiPartDefinition.Condition condition) {
        if (condition instanceof StateMultiPartDefinition.KeyValueCondition keyValue) {
            Predicate<BlockState> predicate = state -> true;

            for (var test : keyValue.tests().entrySet()) {
                var property = (Property) block.getStateDefinition().getProperty(test.getKey());
                if (property == null) {
                    continue;
                }
                var terms = test.getValue().entries();
                predicate = predicate.and(state -> {
                    var value = property.getName((Comparable) state.getValue(property));
                    for (var term : terms) {
                        if (term.negated() != term.value().equals(value)) {
                            return true;
                        }
                    }
                    return false;
                });
            }

            return predicate;
        }

        if (condition instanceof StateMultiPartDefinition.CombinedCondition combined) {
            var predicates = combined.terms().stream()
                    .map(term -> parseCondition(block, term))
                    .toList();

            return switch (combined.operation()) {
                case AND -> state -> predicates.stream().allMatch(predicate -> predicate.test(state));
                case OR -> state -> predicates.stream().anyMatch(predicate -> predicate.test(state));
            };
        }

        return state -> false;
    }
}
