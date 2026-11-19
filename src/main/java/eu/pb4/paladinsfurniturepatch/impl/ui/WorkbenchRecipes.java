package eu.pb4.paladinsfurniturepatch.impl.ui;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads the workbench's recipes without linking against the mod.
 *
 * <p>Paladin's Furniture's workbench crafts out of the player's inventory rather than a grid: its
 * menu keeps a list of every furniture recipe with the craftable ones first, and selecting one
 * fills the result slot that taking the item then consumes. Both halves are reachable through
 * public methods on the menu, so the patch only needs their names, not the mod's classes.
 *
 * <p>The picker shows every recipe, like the mod's own screen, not only the ones the player can
 * afford right now. Recipes that cannot be crafted yet carry a lore line saying so, and the server
 * still refuses to select them.
 */
final class WorkbenchRecipes {
    private static final String MENU = "com.unlikepaladin.pfm.menus.WorkbenchScreenHandler";
    private static final String RECIPE = "com.unlikepaladin.pfm.recipes.FurnitureRecipe$CraftableFurnitureRecipe";
    private static final String INPUT = "com.unlikepaladin.pfm.recipes.FurnitureRecipe$FurnitureRecipeInput";

    private static boolean resolved;
    private static Method sorted;
    private static Method available;
    private static Method select;
    private static Method assemble;
    private static Method itemCounts;
    private static Constructor<?> input;

    private WorkbenchRecipes() {
    }

    /** How many recipes the workbench can show. */
    static int count(AbstractContainerMenu menu) {
        var recipes = sorted(menu);
        return recipes == null ? 0 : recipes.size();
    }

    /** The result items of a slice of those recipes, for the picker slots. */
    static List<ItemStack> icons(AbstractContainerMenu menu, Player player, int from, int count) {
        var recipes = sorted(menu);
        if (recipes == null || from >= recipes.size()) {
            return List.of();
        }

        var craftable = craftable(menu);
        var craftableSet = craftable == null ? new HashSet<>() : new HashSet<>(craftable);

        int end = Math.min(recipes.size(), from + count);
        var stacks = new ArrayList<ItemStack>(end - from);
        try {
            var recipeInput = input.newInstance(player.getInventory());
            for (int i = from; i < end; i++) {
                var stack = (ItemStack) assemble.invoke(recipes.get(i), recipeInput);
                if (stack == null || stack.isEmpty()) {
                    stacks.add(ItemStack.EMPTY);
                    continue;
                }

                // The picker shows one of each recipe, however many the recipe yields: a stack
                // count here would read as a quantity the player already has.
                var icon = stack.copyWithCount(1);
                // The icon is sent as it is, without going through the item conversion, so a lamp
                // needs its wood and colour model written onto the stack to show as a whole lamp.
                PolyFurnitureItem.applyModel(icon);
                if (!craftableSet.contains(recipes.get(i))) {
                    // Neither a recipe viewer nor polydex can see these recipes: the mod keeps them
                    // in its own list instead of the vanilla recipe manager. The list is the only
                    // place a player can read what a piece of furniture is made of, so the tooltip
                    // spells the materials out - required count, and how many they already have.
                    icon.set(DataComponents.LORE, new ItemLore(requirements(recipes.get(i), player)));
                }
                // SGui sends its slots itself, so the icon is turned into the paper and model a
                // client without the mod can draw instead of being left as the mod's item.
                stacks.add(PolyFurnitureItem.toClientStack(icon));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not build the workbench recipe list", exception);
            return List.of();
        }
        return stacks;
    }

    /**
     * The materials a recipe still needs, one line each, red while the player is short of them.
     *
     * <p>{@code getItemCounts} is a default method on the mod's recipe interface, so it answers for
     * both the simple and the dynamic recipes the workbench collects.
     */
    private static List<Component> requirements(Object recipe, Player player) {
        var lines = new ArrayList<Component>();
        var needed = counts(recipe);
        if (needed.isEmpty()) {
            lines.add(Component.literal("Missing materials").withStyle(ChatFormatting.RED));
            return lines;
        }

        lines.add(Component.literal("Requires:").withStyle(ChatFormatting.GRAY));
        var inventory = player.getInventory().getNonEquipmentItems();
        for (var entry : needed.entrySet()) {
            var item = entry.getKey();
            int need = entry.getValue();
            int have = 0;
            for (var stack : inventory) {
                if (stack.is(item)) {
                    have += stack.getCount();
                }
            }

            var line = Component.literal(need + "x ")
                    .append(Component.translatable(item.getDescriptionId()))
                    .append(Component.literal("  " + have + "/" + need));
            lines.add(line.withStyle(have >= need ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static Map<net.minecraft.world.item.Item, Integer> counts(Object recipe) {
        resolve();
        if (itemCounts == null) {
            return Map.of();
        }

        try {
            var counts = (Map<net.minecraft.world.item.Item, Integer>) itemCounts.invoke(recipe);
            return counts == null ? Map.of() : counts;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Map.of();
        }
    }

    /** Selects one recipe, which fills the workbench's result slot when it can be crafted. */
    static boolean select(AbstractContainerMenu menu, Player player, int index) {
        resolve();
        if (select == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(select.invoke(menu, player, index));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not select a workbench recipe", exception);
            return false;
        }
    }

    private static List<?> sorted(AbstractContainerMenu menu) {
        resolve();
        if (sorted == null) {
            return null;
        }

        try {
            return (List<?>) sorted.invoke(menu);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not read the workbench recipes", exception);
            return null;
        }
    }

    private static List<?> craftable(AbstractContainerMenu menu) {
        resolve();
        if (available == null) {
            return null;
        }

        try {
            return (List<?>) available.invoke(menu);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        try {
            var menu = Class.forName(MENU);
            var recipe = Class.forName(RECIPE);
            var recipeInput = Class.forName(INPUT);
            sorted = menu.getMethod("getSortedRecipes");
            available = menu.getMethod("getAvailableRecipes");
            select = menu.getMethod("clickMenuButton", Player.class, int.class);
            assemble = recipe.getMethod("assemble", recipeInput);
            itemCounts = recipe.getMethod("getItemCounts");
            input = recipeInput.getConstructor(net.minecraft.world.entity.player.Inventory.class);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not find the workbench recipe methods", exception);
        }
    }
}
