package eu.pb4.paladinsfurniturepatch.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The model parts behind Paladin's Furniture's multi-part models.
 *
 * <p>Most furniture is one model in the jar. Beds, lamps, nightstands, cabinets and fridges are not:
 * the mod's client code bakes a list of parts and picks from it while rendering - a bed's frame and
 * mattress, a lamp's pole, shade and bulb, a cabinet's open door. Those parts are ordinary models in
 * the jar, but a blockstate can only name one model, and the mod's own blockstate names the list
 * type instead. This table lists those parts and their jar paths, so the patch can build one child
 * model per part and draw the same selection the mod would.
 *
 * <p>The order of every list is the order the mod indexes its parts in; the selection code depends
 * on it.
 */
final class PFMParts {
    record Spec(String key, String template) {
    }

    private PFMParts() {
    }

    /** All model parts any state of this block can draw, or an empty list for single-model furniture. */
    static List<Spec> specs(Block block) {
        var family = PFMBlockStates.family(block);
        if (family == null) {
            return List.of();
        }

        var path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return switch (family) {
            case "simple_bed" -> path.contains("classic") ? classicBed() : simpleBed();
            case "basic_lamp" -> lamp();
            case "classic_nightstand" -> nightstand();
            case "kitchen_cabinet" -> cabinet();
            case "kitchen_drawer" -> drawer();
            case "kitchen_counter_oven" -> oven();
            case "kitchen_counter" -> kitchenCounter();
            case "fridge" -> fridge(path.contains("gray"));
            case "iron_fridge" -> ironFridge();
            case "freezer" -> freezer(path.contains("gray"));
            case "chair", "chair_classic", "chair_dinner", "chair_modern" -> chair(family);
            case "desk_basic" -> deskBasic();
            case "desk_cabinet_basic" -> deskCabinet();
            case "table_basic" -> tableBasic();
            case "coffee_table_basic" -> coffeeBasic();
            case "table_classic", "coffee_table_classic" -> tableClassic(family);
            case "log_table" -> simpleTable("log_table", "lt", 4);
            case "dinner_table" -> simpleTable("dinner_table", "dt", 5);
            case "modern_dinner_table" -> modernTable("table_modern_dinner");
            case "coffee_table_modern" -> modernTable("coffee_table_modern");
            case "kitchen_wall_drawer" -> wallDrawer();
            case "kitchen_wall_small_drawer" -> wallSmallDrawer();
            case "kitchen_wall_counter" -> wallCounter();
            case "simple_bunk_ladder" -> bunkLadder();
            default -> List.of();
        };
    }

    /** The part an item should show, or null to keep the blockstate's own model. */
    static @Nullable String itemKey(Block block) {
        var family = PFMBlockStates.family(block);
        if (family == null) {
            return null;
        }

        var path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return switch (family) {
            case "simple_bed" -> path.contains("classic") ? "c11" : "s11";
            case "basic_lamp" -> "l2";
            case "classic_nightstand" -> "n3";
            case "kitchen_cabinet" -> "k0";
            case "kitchen_drawer" -> "d0";
            case "kitchen_counter_oven" -> "o0";
            case "fridge" -> "f0";
            case "iron_fridge" -> "if0";
            case "freezer" -> "z0";
            case "chair", "chair_classic", "chair_dinner", "chair_modern" -> "ch0";
            case "desk_basic" -> "db0";
            case "desk_cabinet_basic" -> "dc0";
            case "table_basic" -> "bt0";
            case "coffee_table_basic" -> "cb0";
            case "table_classic" -> "ct0";
            case "coffee_table_classic" -> "cc0";
            case "log_table" -> "lt0";
            case "dinner_table" -> "dt0";
            case "modern_dinner_table" -> "md0";
            case "coffee_table_modern" -> "cm0";
            case "kitchen_wall_drawer" -> "wd0";
            case "kitchen_wall_small_drawer" -> "ws0";
            case "kitchen_wall_counter" -> "wc0";
            case "kitchen_counter" -> "kc0";
            case "simple_bunk_ladder" -> "sl0";
            default -> null;
        };
    }

    static @Nullable String template(Block block, String key) {
        for (var spec : specs(block)) {
            if (spec.key().equals(key)) {
                return spec.template();
            }
        }
        return null;
    }

    private static List<Spec> simpleBed() {
        var parts = new ArrayList<Spec>();
        var paths = List.of(
                "block/simple_bed/mattresses/red_foot_mattress",
                "block/simple_bed/mattresses/red_head_mattress",
                "block/simple_bed/template/foot/simple_bed_foot",
                "block/simple_bed/template/head/simple_bed_head",
                "block/simple_bed/template/foot/simple_bed_foot_right",
                "block/simple_bed/template/foot/simple_bed_foot_left",
                "block/simple_bed/template/head/simple_bed_head_right",
                "block/simple_bed/template/head/simple_bed_head_left",
                "block/simple_bed/template/bunk/foot/simple_bed_foot_right",
                "block/simple_bed/template/bunk/foot/simple_bed_foot_left",
                "block/simple_bed/template/bunk/head/simple_bed_head",
                "block/simple_bed/template/full/simple_bed");
        for (int i = 0; i < paths.size(); i++) {
            parts.add(new Spec("s" + i, paths.get(i)));
        }
        return parts;
    }

    private static List<Spec> classicBed() {
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < 10; i++) {
            parts.add(new Spec("c" + i, switch (i) {
                case 0, 1 -> "block/classic_bed/mattresses/" + (i == 0 ? "red_foot_mattress" : "red_head_mattress");
                case 2, 3 -> "block/classic_bed/template/" + (i == 2 ? "foot/classic_bed_foot" : "head/classic_bed_head");
                case 4, 5 -> "block/classic_bed/template/foot/classic_bed_foot_" + (i == 4 ? "right" : "left");
                case 6, 7 -> "block/classic_bed/template/head/classic_bed_head_" + (i == 6 ? "right" : "left");
                case 8, 9 -> "block/classic_bed/template/bunk/foot/classic_bed_foot_" + (i == 8 ? "right" : "left");
                default -> throw new IllegalStateException();
            }));
        }
        // The mod's own list has the simple bed's bunk head at this index; keep that exact part so
        // the geometry matches what a modded client draws.
        parts.add(new Spec("c10", "block/simple_bed/template/bunk/head/simple_bed_head"));
        parts.add(new Spec("c11", "block/classic_bed/template/full/classic_bed"));
        return parts;
    }

    static List<Spec> lampSpecs() {
        return List.of(
                new Spec("l0", "block/basic_lamp/basic_lamp_bottom"),
                new Spec("l1", "block/basic_lamp/basic_lamp_middle"),
                new Spec("l2", "block/basic_lamp/basic_lamp_single"),
                new Spec("l3", "block/basic_lamp/basic_lamp_top"),
                new Spec("l4", "block/basic_lamp/basic_lamp_shade"),
                new Spec("l5", "block/basic_lamp/basic_lamp_light_bulb_off"),
                new Spec("l6", "block/basic_lamp/basic_lamp_light_bulb_on"));
    }

    private static List<Spec> lamp() {
        return lampSpecs();
    }

    private static List<Spec> nightstand() {
        return List.of(
                new Spec("n0", "block/classic_nightstand/classic_nightstand_middle"),
                new Spec("n1", "block/classic_nightstand/classic_nightstand_right"),
                new Spec("n2", "block/classic_nightstand/classic_nightstand_left"),
                new Spec("n3", "block/classic_nightstand/classic_nightstand"),
                new Spec("n4", "block/classic_nightstand/classic_nightstand_middle_open"),
                new Spec("n5", "block/classic_nightstand/classic_nightstand_right_open"),
                new Spec("n6", "block/classic_nightstand/classic_nightstand_left_open"),
                new Spec("n7", "block/classic_nightstand/classic_nightstand_open"));
    }

    private static List<Spec> cabinet() {
        return List.of(
                new Spec("k0", "block/kitchen_cabinet/kitchen_cabinet"),
                new Spec("k1", "block/kitchen_cabinet/kitchen_cabinet_inner_corner_left"),
                new Spec("k2", "block/kitchen_cabinet/kitchen_cabinet_inner_corner_right"),
                new Spec("k3", "block/kitchen_cabinet/kitchen_cabinet_outer_corner_left"),
                new Spec("k4", "block/kitchen_cabinet/kitchen_cabinet_outer_corner_right"),
                new Spec("k5", "block/kitchen_cabinet/kitchen_cabinet_open"),
                new Spec("k6", "block/kitchen_cabinet/kitchen_cabinet_inner_corner_open_left"),
                new Spec("k7", "block/kitchen_cabinet/kitchen_cabinet_inner_corner_open_right"),
                new Spec("k8", "block/kitchen_cabinet/kitchen_cabinet_outer_corner_open_left"),
                new Spec("k9", "block/kitchen_cabinet/kitchen_cabinet_outer_corner_open_right"));
    }

    private static List<Spec> drawer() {
        // The drawer model bakes these in this order and indexes into the list with an offset of
        // seven when the drawer is open, exactly like PFM's FabricKitchenDrawerModel.
        return List.of(
                new Spec("d0", "block/kitchen_drawer/kitchen_drawer"),
                new Spec("d1", "block/kitchen_drawer/kitchen_drawer_edge_left"),
                new Spec("d2", "block/kitchen_drawer/kitchen_drawer_edge_right"),
                new Spec("d3", "block/kitchen_drawer/kitchen_drawer_inner_corner_left"),
                new Spec("d4", "block/kitchen_drawer/kitchen_drawer_inner_corner_right"),
                new Spec("d5", "block/kitchen_drawer/kitchen_drawer_outer_corner_left"),
                new Spec("d6", "block/kitchen_drawer/kitchen_drawer_outer_corner_right"),
                new Spec("d7", "block/kitchen_drawer/kitchen_drawer_open"),
                new Spec("d8", "block/kitchen_drawer/kitchen_drawer_edge_left_open"),
                new Spec("d9", "block/kitchen_drawer/kitchen_drawer_edge_right_open"),
                new Spec("d10", "block/kitchen_drawer/kitchen_drawer_inner_corner_left"),
                new Spec("d11", "block/kitchen_drawer/kitchen_drawer_inner_corner_right"),
                new Spec("d12", "block/kitchen_drawer/kitchen_drawer_outer_corner_open_left"),
                new Spec("d13", "block/kitchen_drawer/kitchen_drawer_outer_corner_open_right"));
    }

    private static List<Spec> oven() {
        return List.of(
                new Spec("o0", "block/kitchen_counter_oven/kitchen_counter_oven"),
                new Spec("o1", "block/kitchen_counter_oven/kitchen_counter_oven_middle"),
                new Spec("o2", "block/kitchen_counter_oven/kitchen_counter_oven_open"),
                new Spec("o3", "block/kitchen_counter_oven/kitchen_counter_oven_middle_open"));
    }

    private static List<Spec> fridge(boolean gray) {
        var prefix = gray ? "block/gray_fridge/" : "block/white_fridge/";
        var names = List.of("fridge_single", "fridge_top", "fridge_middle", "fridge_bottom", "fridge",
                "fridge_middle_freezer", "fridge_single_open", "fridge_top_open", "fridge_middle_open",
                "fridge_bottom_open", "fridge_open", "fridge_middle_freezer_open");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec("f" + i, prefix + names.get(i)));
        }
        return parts;
    }

    private static List<Spec> ironFridge() {
        var prefix = "block/iron_fridge/iron_fridge";
        var names = List.of("single", "top", "middle", "bottom", "",
                "single_open", "top_open", "middle_open", "bottom_open", "open");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            parts.add(new Spec("if" + i, prefix + (name.isEmpty() ? "" : "_" + name)));
        }
        return parts;
    }

    private static List<Spec> kitchenCounter() {
        var names = List.of(
                "kitchen_counter",
                "kitchen_counter_edge_left",
                "kitchen_counter_edge_right",
                "kitchen_counter_inner_corner_left",
                "kitchen_counter_inner_corner_right",
                "kitchen_counter_outer_corner_left",
                "kitchen_counter_outer_corner_right");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec("kc" + i, "block/kitchen_counter/" + names.get(i)));
        }
        return parts;
    }

    private static List<Spec> bunkLadder() {
        return List.of(
                new Spec("sl0", "block/simple_bunk_ladder/simple_ladder"),
                new Spec("sl1", "block/simple_bunk_ladder/simple_ladder_top"));
    }

    private static List<Spec> freezer(boolean gray) {
        var prefix = gray ? "block/gray_fridge/" : "block/white_fridge/";
        return List.of(
                new Spec("z0", prefix + "freezer_single"),
                new Spec("z1", prefix + "freezer"),
                new Spec("z2", prefix + "freezer_single_open"),
                new Spec("z3", prefix + "freezer_open"));
    }

    private static List<Spec> chair(String family) {
        var base = "block/" + family + "/" + family;
        return List.of(new Spec("ch0", base), new Spec("ch1", base + "_tucked"));
    }

    /** The basic desk's five parts, in the mod's baked-model order. */
    private static List<Spec> deskBasic() {
        return List.of(
                new Spec("db0", "block/basic_desk/basic_desk_base"),
                new Spec("db1", "block/basic_desk/basic_desk_north_west"),
                new Spec("db2", "block/basic_desk/basic_desk_north_east"),
                new Spec("db3", "block/basic_desk/basic_desk_south_west"),
                new Spec("db4", "block/basic_desk/basic_desk_south_east"));
    }

    private static List<Spec> deskCabinet() {
        var names = List.of(
                "basic_desk_cabinet_closed",
                "basic_desk_cabinet_left_closed",
                "basic_desk_cabinet_right_closed",
                "basic_desk_cabinet_middle_closed",
                "basic_desk_cabinet_inside_corner_closed_left",
                "basic_desk_cabinet_inside_corner_closed_right",
                "basic_desk_cabinet_outer_corner_closed_left",
                "basic_desk_cabinet_outer_corner_closed_right",
                "basic_desk_cabinet_open",
                "basic_desk_cabinet_left_open",
                "basic_desk_cabinet_right_open",
                "basic_desk_cabinet_middle_open",
                "basic_desk_cabinet_inside_corner_open_left",
                "basic_desk_cabinet_inside_corner_open_right",
                "basic_desk_cabinet_outer_corner_open_left",
                "basic_desk_cabinet_outer_corner_open_right",
                "basic_desk_cabinet_leg_north_east",
                "basic_desk_cabinet_leg_north_west",
                "basic_desk_cabinet_leg_south_east",
                "basic_desk_cabinet_leg_south_west");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec("dc" + i, "block/basic_desk_cabinet/" + names.get(i)));
        }
        return parts;
    }

    private static List<Spec> tableBasic() {
        return tableBasicParts("table_basic", "bt");
    }

    private static List<Spec> coffeeBasic() {
        return tableBasicParts("coffee_table_basic", "cb");
    }

    /** The basic table's parts: one base, four corner posts, six edge boards and four corners. */
    private static List<Spec> tableBasicParts(String folder, String prefix) {
        var names = List.of(
                folder + "_base",
                folder + "_north_east",
                folder + "_north_west",
                folder + "_south_east",
                folder + "_south_west",
                folder + "_north_south_east_top",
                folder + "_north_south_west_top",
                folder + "_east_west_north",
                folder + "_east_west_south",
                folder + "_north_south_east_bottom",
                folder + "_north_south_west_bottom",
                folder + "_north_south_east",
                folder + "_north_south_west",
                folder + "_north_east_corner",
                folder + "_north_west_corner",
                folder + "_south_east_corner",
                folder + "_south_west_corner");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec(prefix + i, "block/" + folder + "/" + names.get(i)));
        }
        return parts;
    }

    private static List<Spec> tableClassic(String family) {
        var folder = family.equals("table_classic") ? "table_classic" : "coffee_table_classic";
        var prefix = family.equals("table_classic") ? "ct" : "cc";
        var names = List.of("middle", "two", "two_uved", "one", "one_uved", "");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            parts.add(new Spec(prefix + i, "block/" + folder + "/" + folder + (name.isEmpty() ? "" : "_" + name)));
        }
        return parts;
    }

    /** A table whose parts are middle, right, left and a shared set of legs. */
    private static List<Spec> simpleTable(String folder, String prefix, int size) {
        var names = List.of("middle", "right", "left", "legs", "");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < size; i++) {
            var name = names.get(i);
            parts.add(new Spec(prefix + i, "block/" + folder + "/" + folder + (name.isEmpty() ? "" : "_" + name)));
        }
        return parts;
    }

    private static List<Spec> modernTable(String folder) {
        var names = List.of("base", "right", "left", "legs", "middle");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec((folder.startsWith("coffee") ? "cm" : "md") + i,
                    "block/" + folder + "/" + folder + "_" + names.get(i)));
        }
        return parts;
    }

    private static List<Spec> wallDrawer() {
        // Closed: middle and corners at 0-4. Open: the same list shifted by five, with the outer
        // corners replaced by their open forms.
        var names = List.of(
                "kitchen_drawer_middle",
                "kitchen_drawer_middle_inner_corner_left",
                "kitchen_drawer_middle_inner_corner_right",
                "kitchen_drawer_middle_outer_corner_left",
                "kitchen_drawer_middle_outer_corner_right",
                "kitchen_drawer_middle_open",
                "kitchen_drawer_middle_inner_corner_left",
                "kitchen_drawer_middle_inner_corner_right",
                "kitchen_drawer_middle_outer_corner_open_left",
                "kitchen_drawer_middle_outer_corner_open_right");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec("wd" + i, "block/kitchen_drawer/" + names.get(i)));
        }
        return parts;
    }

    private static List<Spec> wallSmallDrawer() {
        return List.of(
                new Spec("ws0", "block/kitchen_wall_drawer_small/kitchen_wall_drawer_small"),
                new Spec("ws1", "block/kitchen_wall_drawer_small/kitchen_wall_drawer_small_open"));
    }

    private static List<Spec> wallCounter() {
        var names = List.of(
                "kitchen_counter_middle",
                "kitchen_counter_middle_inner_corner_left",
                "kitchen_counter_middle_inner_corner_right",
                "kitchen_counter_middle_outer_corner_left",
                "kitchen_counter_middle_outer_corner_right");
        var parts = new ArrayList<Spec>();
        for (int i = 0; i < names.size(); i++) {
            parts.add(new Spec("wc" + i, "block/kitchen_counter/" + names.get(i)));
        }
        return parts;
    }
}
