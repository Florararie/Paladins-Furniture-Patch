package eu.pb4.paladinsfurniturepatch.impl.ui;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.paladinsfurniturepatch.impl.PFMGeneratedAssets;
import eu.pb4.paladinsfurniturepatch.impl.item.PolyFurnitureItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.Ingredient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static Method output;
    private static Method ingredients;
    private static Constructor<?> input;

    private WorkbenchRecipes() {
    }

    /**
     * The recipe indices that match a search, in the workbench's own order.
     *
     * <p>Matching the output name is what makes the search useful for furniture, since the output
     * is the piece the player is looking for. Ingredient names are matched too, so searching
     * "planks" or "wool" finds the pieces that use them.
     */
    static List<Integer> indices(AbstractContainerMenu menu, String query) {
        var recipes = sorted(menu);
        if (recipes == null) {
            return List.of();
        }

        var needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var indices = new ArrayList<Integer>(recipes.size());
        if (needle.isEmpty()) {
            for (int i = 0; i < recipes.size(); i++) {
                indices.add(i);
            }
            return indices;
        }

        for (int i = 0; i < recipes.size(); i++) {
            if (matches(recipes.get(i), needle)) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** The result items of a slice of those recipes, for the picker slots. */
    static List<ItemStack> icons(AbstractContainerMenu menu, Player player, List<Integer> indices,
                                 int from, int count) {
        var recipes = sorted(menu);
        if (recipes == null || from >= indices.size()) {
            return List.of();
        }

        var craftable = craftable(menu);
        var craftableSet = craftable == null ? new HashSet<>() : new HashSet<>(craftable);

        int end = Math.min(indices.size(), from + count);
        var stacks = new ArrayList<ItemStack>(end - from);
        try {
            var recipeInput = input.newInstance(player.getInventory());
            for (int i = from; i < end; i++) {
                var recipe = recipes.get(indices.get(i));
                var stack = (ItemStack) assemble.invoke(recipe, recipeInput);
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
                // The material list belongs on every piece, not only the currently uncraftable
                // ones. It is the only place the workbench explains why an item is grey or what a
                // click will consume.
                icon.set(DataComponents.LORE, new ItemLore(requirements(recipe, player,
                        craftableSet.contains(recipe))));
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
     * Whether every word in the search appears in the output name, item id, or an ingredient.
     *
     * <p>The item id is part of the haystack because PFM's names are generated at runtime: on a
     * dedicated server the translated name can still be the raw {@code block.pfm.*} key, while the
     * registry path is always the useful {@code oak_simple_stool}. Without it, searching for
     * "Simple Stool" found nothing even though the recipe existed.
     */
    private static boolean matches(Object recipe, String query) {
        resolve();
        var haystack = new StringBuilder();
        try {
            if (output != null) {
                appendName(haystack, (ItemStack) output.invoke(recipe));
            }
            if (ingredients != null) {
                var list = (List<?>) ingredients.invoke(recipe);
                if (list != null) {
                    for (var ingredient : list) {
                        for (var holder : ((Ingredient) ingredient).items().toList()) {
                            appendName(haystack, new ItemStack((Item) holder.value()));
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A recipe from another PFM version that cannot answer still remains in the list.
        }

        var text = haystack.toString();
        for (var word : query.split("\\s+")) {
            if (!word.isEmpty() && !text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static void appendName(StringBuilder haystack, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        haystack.append(' ').append(stack.getHoverName().getString().toLowerCase(Locale.ROOT));
        haystack.append(' ').append(stack.getItem().getDescriptionId().replace('_', ' ').toLowerCase(Locale.ROOT));
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        haystack.append(' ').append(id.getPath().replace('_', ' ').toLowerCase(Locale.ROOT));

        // The name the player actually reads. PFM renames several families, so a wall drawer is a
        // "Kitchen Cabinet" and a wall counter a "Kitchen Filler Cabinet" even though neither word
        // is in their registry path. Without this, searching "filler" found nothing at all.
        var generated = PFMGeneratedAssets.searchName(stack.getItem());
        if (generated != null) {
            haystack.append(' ').append(generated.toLowerCase(Locale.ROOT));
        }
    }

    /** Whether a recipe is currently in the workbench's craftable list. */
    static boolean canCraft(AbstractContainerMenu menu, int index) {
        var recipes = sorted(menu);
        var available = craftable(menu);
        return recipes != null && available != null && index >= 0 && index < recipes.size()
                && available.contains(recipes.get(index));
    }

    /**
     * Selects a recipe and moves its result straight into the player's inventory.
     *
     * <p>The workbench's result slot still owns the ingredient consumption, so this goes through the
     * menu instead of assembling and shrinking stacks here. quickMoveStack indexes the result slot
     * first, as the mod's own menu does, and its onTake path removes the materials.
     */
    static ItemStack craft(AbstractContainerMenu menu, Player player, int index) {
        if (!select(menu, player, index)) {
            return ItemStack.EMPTY;
        }

        try {
            return menu.quickMoveStack(player, 0);
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not move a crafted workbench result", exception);
            return ItemStack.EMPTY;
        }
    }

    /**
     * The materials a recipe still needs, one line each, red while the player is short of them.
     *
     * <p>{@code getItemCounts} is a default method on the mod's recipe interface, so it answers for
     * both the simple and the dynamic recipes the workbench collects.
     */
    private static List<Component> requirements(Object recipe, Player player, boolean craftable) {
        var lines = new ArrayList<Component>();
        lines.add(Component.literal(craftable ? "Click to craft" : "Missing materials")
                .withStyle(craftable ? ChatFormatting.GREEN : ChatFormatting.RED));

        var needed = counts(recipe);
        if (needed.isEmpty()) {
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
    private static Map<Item, Integer> counts(Object recipe) {
        resolve();
        if (itemCounts == null) {
            return Map.of();
        }

        try {
            var counts = (Map<Item, Integer>) itemCounts.invoke(recipe);
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
            output = recipe.getMethod("getRecipeOuput");
            ingredients = recipe.getMethod("getIngredients");
            input = recipeInput.getConstructor(net.minecraft.world.entity.player.Inventory.class);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not find the workbench recipe methods", exception);
        }
    }
}
