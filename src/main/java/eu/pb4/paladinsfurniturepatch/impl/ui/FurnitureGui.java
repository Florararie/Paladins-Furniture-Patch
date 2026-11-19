package eu.pb4.paladinsfurniturepatch.impl.ui;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One of Paladin's Furniture's containers, drawn as an SGui screen instead of a chest.
 *
 * <p>The mod's menu is still the thing that does the work: its slots point at the block entity's
 * inventory and carry the mod's slot rules, and its workbench owns the recipe logic. SGui is given
 * those very slot objects, so a click is handled by the mod's own slot - an oven's fuel slot refuses
 * a loaf, a result slot knows what taking it costs - and the client keeps receiving changes because
 * the slots are part of the menu the client knows.
 *
 * <p>The panel behind the slots is a font glyph built from the mod's own container art (see
 * {@link GuiPack}), so the screen shows the mod's title bar, palette and slot frames rather than a
 * chest.
 *
 * <p>The workbench has no storage to show: it crafts from the player's inventory. Its top row holds
 * the mod's result slot, the next fifty-one hold one craftable piece of furniture each and the last
 * two turn the page. Clicking a piece selects that recipe, exactly as clicking it in the mod's own
 * list does.
 *
 * <p>The microwave only has a start button in the mod's screen and the trashcan only a clear
 * button; both are shown as an item whose click runs the same server-side action.
 */
public final class FurnitureGui extends SimpleGui {
    private static final int PLAYER_SLOTS = 36;

    /** The result slot is cell 0, so the list fills cells 1 to 51 and the page arrows 52 and 53. */
    private static final int PICKER_FIRST = 1;
    private static final int PICKER_SIZE = 51;
    private static final int PAGE_PREVIOUS = 52;
    private static final int PAGE_NEXT = 53;

    private static final int MICROWAVE_SLOT = 13;
    private static final int MICROWAVE_BUTTON = 15;
    private static final int TRASHCAN_BUTTON = 17;

    /** The recipes a microwave can use, so a start that cannot cook anything is caught up front. */
    private static final RecipeManager.CachedCheck<SingleRecipeInput, ? extends AbstractCookingRecipe> SMOKING =
            RecipeManager.createCheck(RecipeType.SMOKING);

    private final AbstractContainerMenu source;
    private final GuiPack.Screen screen;
    private final int containerSlots;
    private final boolean workbench;
    private int page;
    private int ticks;
    private boolean sourceClosed;

    private FurnitureGui(ServerPlayer player, AbstractContainerMenu source, GuiPack.Screen screen, Component title) {
        // The last argument says whether the player's inventory belongs to this screen. It has to be
        // false: with it true SGui treats those thirty-six slots as its own, locks them, and every
        // one of them is drawn empty - the player's whole inventory disappeared and nothing could be
        // moved. With it false the client still gets the normal chest layout, the player's slots stay
        // ordinary slots of their inventory, and only the container cells are ours.
        super(type(screen.rows()), player, false);
        this.source = source;
        this.screen = screen;
        this.workbench = screen == GuiPack.Screen.WORKBENCH;

        // The mod's menu is never the menu the client knows. Its remote slot list is empty, so
        // letting it broadcast would clear the screen; its own slots are still live and still
        // called by the block entity.
        source.suppressRemoteUpdates();

        this.setTitle(GuiPack.title(screen, title));
        this.containerSlots = source.slots.size() - PLAYER_SLOTS;
    }

    /**
     * Puts the mod's slots on the grid.
     *
     * <p>This runs after the screen has opened, not before: SGui builds the menu the client will
     * click on while opening, and a slot installed earlier is not in it - which is why clicks did
     * nothing to the mod's containers no matter how the slots were wrapped.
     */
    private void wireSlots() {
        var cells = this.screen.cellsFor(this.containerSlots);
        for (int i = 0; i < cells.length; i++) {
            // The mod's slot is wrapped rather than handed over as it is: a slot's own index is part
            // of the click protocol, and the mod's index is its place in the mod's menu, not the cell
            // it is drawn in here.
            this.setSlot(cells[i], new ForwardingSlot(this.source.slots.get(i), cells[i]));
        }

        if (this.containerSlots > cells.length) {
            PaladinsFurniturePatch.LOGGER.warn("{} has {} slots but its screen only shows {}",
                    this.source.getClass().getSimpleName(), this.containerSlots, cells.length);
        }
    }

    /** Builds and opens the screen, or returns null when it could not be opened. */
    public static @Nullable FurnitureGui open(ServerPlayer player, AbstractContainerMenu source,
                                              String path, boolean workbench, Component title) {
        var screen = GuiPack.Screen.forPath(path, workbench, source.slots.size() - PLAYER_SLOTS);
        var gui = new FurnitureGui(player, source, screen, title);
        if (!gui.open()) {
            gui.closeSource();
            return null;
        }

        gui.wireSlots();
        gui.refresh();
        return gui;
    }

    /** The menu type whose slot grid matches the number of rows this screen uses. */
    private static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    private void refresh() {
        if (this.workbench) {
            this.refreshPicker();
        } else if (this.screen == GuiPack.Screen.MICROWAVE) {
            this.setSlot(MICROWAVE_BUTTON, this.microwaveButton());
        } else if (this.screen == GuiPack.Screen.TRASHCAN) {
            this.setSlot(TRASHCAN_BUTTON, this.trashcanButton());
        }
    }

    private void refreshPicker() {
        var player = this.getPlayer();
        int recipes = WorkbenchRecipes.count(this.source);
        int pages = Math.max(1, (recipes + PICKER_SIZE - 1) / PICKER_SIZE);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        var icons = WorkbenchRecipes.icons(this.source, player, this.page * PICKER_SIZE, PICKER_SIZE);
        for (int i = 0; i < PICKER_SIZE; i++) {
            int cell = PICKER_FIRST + i;
            if (i >= icons.size() || icons.get(i).isEmpty()) {
                this.clearSlot(cell);
                continue;
            }

            var stack = icons.get(i);
            int recipe = this.page * PICKER_SIZE + i;
            this.setSlot(cell, new GuiElementBuilder(stack)
                    .setCallback((index, type, action, gui) -> {
                        if (WorkbenchRecipes.select(this.source, player, recipe)) {
                            this.refreshPicker();
                        }
                    })
                    .build());
        }

        // An arrow only exists on a side there is a page on, as in the mod's own list, so the page
        // count reads from the buttons that are there rather than from greyed-out leftovers.
        String label = (this.page + 1) + "/" + pages;
        if (this.page > 0) {
            this.setSlot(PAGE_PREVIOUS, pageButton("ui_prev", "Previous page " + label, () -> {
                this.page--;
                this.refreshPicker();
            }));
        } else {
            this.clearSlot(PAGE_PREVIOUS);
        }

        if (this.page + 1 < pages) {
            this.setSlot(PAGE_NEXT, pageButton("ui_next", "Next page " + label, () -> {
                this.page++;
                this.refreshPicker();
            }));
        } else {
            this.clearSlot(PAGE_NEXT);
        }
    }

    private static GuiElementBuilder pageButton(String icon, String name, Runnable action) {
        return new GuiElementBuilder(button(icon))
                .setName(Component.literal(name).withStyle(ChatFormatting.WHITE))
                .hideDefaultTooltip()
                .setCallback((index, type, containerInput, gui) -> action.run());
    }

    /** A button's icon: a paper stack naming one of the pack's own button models. */
    private static ItemStack button(String icon) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, GuiPack.ui(icon));
        return stack;
    }

    private GuiElementBuilder microwaveButton() {
        boolean active = microwaveActive();
        String status = microwaveStatus();
        var stack = button(active ? "ui_cooking" : "ui_start");
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(active ? "Cooking..." : "Start cooking").withStyle(ChatFormatting.WHITE));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(active ? microwaveProgress() : status).withStyle(ChatFormatting.GRAY))));
        return new GuiElementBuilder(stack).setCallback((index, type, action, gui) -> this.startMicrowave());
    }

    private GuiElementBuilder trashcanButton() {
        var stack = button("ui_clear");
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Clear contents").withStyle(ChatFormatting.WHITE));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Click to clear").withStyle(ChatFormatting.GRAY))));
        return new GuiElementBuilder(stack).setCallback((index, type, action, gui) -> this.clearTrashcan());
    }

    /**
     * Starts the microwave, or says why it will not start.
     *
     * <p>The mod's own start button sets the microwave running whatever is inside it, so pressing it
     * with something that has no cooking recipe lit the screen up and cooked nothing. The same
     * recipes the block would look for are checked first, so the answer is a message rather than a
     * microwave that appears to be working.
     */
    private void startMicrowave() {
        if (microwaveActive()) {
            this.notice("The microwave is already cooking");
            this.setSlot(MICROWAVE_BUTTON, this.microwaveButton());
            return;
        }

        var input = this.microwaveInput();
        if (input.isEmpty()) {
            this.notice("Put something in the microwave first");
            return;
        }

        if (!this.microwaveCanCook()) {
            this.notice("A microwave can't cook " + input.getHoverName().getString());
            this.setSlot(MICROWAVE_BUTTON, this.microwaveButton());
            return;
        }

        this.setMicrowaveActive();
        this.setSlot(MICROWAVE_BUTTON, this.microwaveButton());
    }

    private void notice(String text) {
        this.getPlayer().sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    private ItemStack microwaveInput() {
        var entity = microwaveEntity();
        return entity instanceof Container container ? container.getItem(0) : ItemStack.EMPTY;
    }

    /** Whether the item in the microwave has a recipe the block can actually use. */
    private boolean microwaveCanCook() {
        var input = this.microwaveInput();
        if (input.isEmpty()) {
            return false;
        }

        var level = this.getPlayer().level();
        try {
            return SMOKING.getRecipeFor(new SingleRecipeInput(input), level).isPresent();
        } catch (RuntimeException exception) {
            // Recipes are read per level; a level that cannot answer must not block a start the
            // block itself would accept.
            return true;
        }
    }

    /** What the start button should say about the item that is in the microwave now. */
    private String microwaveStatus() {
        var input = this.microwaveInput();
        if (input.isEmpty()) {
            return "Put an item in first";
        }
        if (!this.microwaveCanCook()) {
            return "This item can't be cooked";
        }
        return "Click to start";
    }

    private @Nullable Object microwaveEntity() {
        try {
            return this.source.getClass().getField("microwaveBlockEntity").get(this.source);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private boolean microwaveActive() {
        var entity = microwaveEntity();
        if (entity == null) {
            return false;
        }
        try {
            return entity.getClass().getField("isActive").getBoolean(entity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private void setMicrowaveActive() {
        var entity = microwaveEntity();
        if (entity == null) {
            return;
        }
        try {
            entity.getClass().getMethod("setActive", boolean.class).invoke(entity, true);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not start the microwave", exception);
        }
    }

    private int microwaveInt(String name) {
        var entity = microwaveEntity();
        if (entity == null) {
            return 0;
        }
        try {
            var field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(entity);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return 0;
        }
    }

    private String microwaveProgress() {
        int total = microwaveInt("cookTimeTotal");
        int cooked = microwaveInt("cookTime");
        if (total <= 0) {
            return "Working...";
        }
        return "Cooking " + Math.min(100, cooked * 100 / total) + "%";
    }

    private void clearTrashcan() {
        try {
            var entity = this.source.getClass().getField("trashcanBlockEntity").get(this.source);
            if (entity instanceof Container container) {
                container.clearContent();
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not clear the trashcan", exception);
        }
    }

    @Override
    public boolean stillValid() {
        return !this.sourceClosed && this.source.stillValid(this.getPlayer());
    }

    @Override
    public void onTick() {
        if (this.sourceClosed) {
            return;
        }

        if (!this.source.stillValid(this.getPlayer())) {
            this.close();
            return;
        }

        // Recipe lists and the microwave's progress only change a few times a second; rebuilding
        // fifty-one elements every tick would be pure waste.
        if (++this.ticks < 20) {
            return;
        }
        this.ticks = 0;

        if (this.workbench) {
            this.refreshPicker();
        } else if (this.screen == GuiPack.Screen.MICROWAVE) {
            this.setSlot(MICROWAVE_BUTTON, this.microwaveButton());
        }
    }

    @Override
    public void onRemoved() {
        this.closeSource();
    }

    @Override
    public void onPlayerClose(boolean clientSide) {
        this.closeSource();
    }

    @Override
    public void onManualClose() {
        this.closeSource();
    }

    /**
     * A slot drawn where SGui wants it, but handing every rule to the mod's own slot.
     *
     * <p>An oven's fuel slot refuses a loaf, a result slot knows that taking the item costs the
     * ingredients, and a trashcan slot simply accepts anything - all of that lives in the mod's
     * slot, so the wrapper only changes where it sits and what index the client sees.
     */
    private static final class ForwardingSlot extends Slot {
        private final Slot target;

        private ForwardingSlot(Slot target, int index) {
            super(target.container, target.getContainerSlot(), 0, 0);
            this.target = target;
            this.index = index;
        }

        @Override
        public ItemStack getItem() {
            return this.target.getItem();
        }

        @Override
        public boolean hasItem() {
            return this.target.hasItem();
        }

        @Override
        public void set(ItemStack stack) {
            this.target.set(stack);
        }

        @Override
        public void setChanged() {
            this.target.setChanged();
        }

        @Override
        public ItemStack remove(int amount) {
            return this.target.remove(amount);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.target.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
            return this.target.mayPickup(player);
        }

        @Override
        public boolean allowModification(net.minecraft.world.entity.player.Player player) {
            return this.target.allowModification(player);
        }

        // An oven's result slot consumes its ingredients when the result is taken; that lives in the
        // mod's slot, so taking through the wrapper has to reach it.
        @Override
        public void onTake(net.minecraft.world.entity.player.Player player, ItemStack stack) {
            this.target.onTake(player, stack);
        }

        @Override
        public boolean isActive() {
            return this.target.isActive();
        }

        @Override
        public int getMaxStackSize() {
            return this.target.getMaxStackSize();
        }
    }

    private void closeSource() {
        if (this.sourceClosed) {
            return;
        }
        this.sourceClosed = true;
        try {
            this.source.removed(this.getPlayer());
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not close a furniture menu", exception);
        }
    }
}
