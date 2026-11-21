package eu.pb4.paladinsfurniturepatch.impl.ui;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import eu.pb4.sgui.api.SguiUtils;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.elements.SimpleGuiElement;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The furniture workbench as one browser screen.
 *
 * <p>This is deliberately an anvil screen: it is the only vanilla menu with a text field, and
 * keeping the search field and the furniture list in that one menu avoids the client/server menu
 * desync that happened when the old search screen closed and a second workbench menu was opened.
 * Escape now closes the workbench itself, and every furniture cell is an SGui virtual slot, so no
 * button or result icon can be picked up.
 *
 * <p>The layout follows Polydex's search page: a search field at the top, 27 items in a 3x9 grid,
 * and page controls in the bottom row. Clicking a piece crafts it directly into the player's
 * inventory; the tooltip always shows the recipe's material list.
 */
public final class WorkbenchGui extends AnvilInputGui {
    private static final int GRID_FIRST = 3;
    private static final int GRID_SIZE = 27;
    // Polydex's footer is a centred three-slot pager with the close button on the far right.
    private static final int PREVIOUS = 33;
    private static final int PAGE = 34;
    private static final int NEXT = 35;
    private static final int CLOSE = 38;
    /** The footer buttons, and the hotbar cells an anvil screen draws them in. */
    private static final int[] FOOTER = {PREVIOUS, PAGE, NEXT, CLOSE};
    private static final int[] FOOTER_HOTBAR = {PREVIOUS - 30, PAGE - 30, NEXT - 30, CLOSE - 30};
    private final Map<Integer, ItemStack> pinned = new HashMap<>();
    private final ItemStack[] lastHotbar =
            {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};

    private final AbstractContainerMenu source;
    private final Component title;
    private String query;
    private int page;
    private int ticks;
    private boolean sourceClosed;

    private WorkbenchGui(ServerPlayer player, AbstractContainerMenu source, Component title, String query) {
        super(player, true);
        this.source = source;
        this.title = title;
        this.query = query == null ? "" : query;

        // The mod's menu is only the recipe database here. Its slots are never shown, and letting it
        // broadcast would overwrite the anvil menu the client is actually interacting with.
        source.suppressRemoteUpdates();

        // The title is static. Changing it after the screen opens makes SGui resend the anvil menu,
        // which resets the client's search field and drops everything typed after the first letter.
        this.setTitle(GuiPack.searchTitle(title));
        this.setDefaultInputValue(this.query);
        this.refresh();
    }

    public static @Nullable WorkbenchGui open(ServerPlayer player, AbstractContainerMenu source,
                                              Component title) {
        var gui = new WorkbenchGui(player, source, title, "");
        if (!gui.open()) {
            gui.closeSource();
            return null;
        }
        gui.refresh();
        return gui;
    }

    /**
     * Slot 0 is the anvil's input item. It has to stay non-empty: the vanilla anvil disables its
     * edit box and refuses to send rename packets when this slot is empty. A plain paper marker
     * keeps the field editable, and replacing SGui's default click callback stops the old
     * reopen-and-reset behaviour. The panel paints over this slot, so the marker is never visible.
     *
     * <p>The item name is empty on purpose: the client fills the anvil's edit box from this stack's
     * hover name, and an unnamed paper showed up as the text "Paper" that had to be deleted before
     * anything could be typed.
     *
     * <p>The anvil treats this item as a rename and draws its "Enchantment Cost: 1" label over the
     * panel. Nothing on the server can stop that - the label is drawn by the client from its own
     * copy of the menu - so it is left as it is.
     */
    @Override
    public void updateDefaultInputItem(String text) {
        var marker = new ItemStack(Items.PAPER);
        marker.set(DataComponents.ITEM_MODEL, GuiPack.ui("ui_blank"));
        marker.set(DataComponents.ITEM_NAME, Component.empty());
        marker.set(DataComponents.CUSTOM_NAME, Component.literal(text == null ? "" : text));
        this.setSlot(0, new GuiElementBuilder(marker).hideDefaultTooltip().build());
    }

    @Override
    public void onInput(String input) {
        this.query = input == null ? "" : input;
        this.page = 0;
        this.refresh();
    }

    private void refresh() {
        var indices = WorkbenchRecipes.indices(this.source, this.query);
        int pages = Math.max(1, (indices.size() + GRID_SIZE - 1) / GRID_SIZE);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        var icons = WorkbenchRecipes.icons(this.source, this.getPlayer(), indices,
                this.page * GRID_SIZE, GRID_SIZE);
        for (int i = 0; i < GRID_SIZE; i++) {
            int cell = GRID_FIRST + i;
            if (i >= icons.size() || icons.get(i).isEmpty()) {
                this.setSlot(cell, SimpleGuiElement.EMPTY);
                continue;
            }

            var stack = icons.get(i);
            int recipe = indices.get(this.page * GRID_SIZE + i);
            this.setSlot(cell, new GuiElementBuilder(stack)
                    .setCallback((index, type, action, gui) -> this.craft(recipe))
                    .build());
        }

        this.setSlot(PREVIOUS, pageButton(PREVIOUS, "ui_prev", "Previous page", this.page > 0, () -> {
            this.page--;
            this.refresh();
        }));
        this.setSlot(NEXT, pageButton(NEXT, "ui_next", "Next page", this.page + 1 < pages, () -> {
            this.page++;
            this.refresh();
        }));

        var pageStack = new ItemStack(Items.BOOK);
        pageStack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Page " + (this.page + 1) + " / " + pages)
                        .withStyle(ChatFormatting.WHITE));
        pageStack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(indices.size() + (indices.size() == 1 ? " piece" : " pieces"))
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("Type above to search").withStyle(ChatFormatting.DARK_GRAY))));
        this.pinned.put(PAGE, pageStack.copy());
        this.setSlot(PAGE, new GuiElementBuilder(pageStack).hideDefaultTooltip().build());

        this.setSlot(CLOSE, pageButton(CLOSE, "ui_close", "Close", true, this::close));

        // The other footer cells are part of the anvil's hotbar row. Empty virtual elements keep
        // them looking like a clean control strip instead of nine stray empty inventory slots.
        for (int slot = 30; slot <= 38; slot++) {
            if (slot != PREVIOUS && slot != PAGE && slot != NEXT && slot != CLOSE) {
                this.setSlot(slot, SimpleGuiElement.EMPTY);
            }
        }

    }

    private void craft(int recipe) {
        var player = this.getPlayer();
        if (!WorkbenchRecipes.canCraft(this.source, recipe)) {
            player.sendSystemMessage(
                    Component.literal("You don't have the materials for that.").withStyle(ChatFormatting.RED),
                    true);
            return;
        }

        var result = WorkbenchRecipes.craft(this.source, player, recipe);
        if (result.isEmpty()) {
            player.sendSystemMessage(
                    Component.literal("No room in your inventory.").withStyle(ChatFormatting.RED),
                    true);
            return;
        }

        player.sendSystemMessage(
                Component.literal("Crafted ").append(result.getHoverName()).withStyle(ChatFormatting.GREEN),
                true);
        this.refresh();
    }

    private GuiElementBuilder pageButton(int slot, String icon, String name, boolean active,
                                         Runnable action) {
        var stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, GuiPack.ui(icon));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.WHITE));
        this.pinned.put(slot, stack.copy());
        var builder = new GuiElementBuilder(stack).hideDefaultTooltip();
        if (active) {
            builder.setCallback((index, type, actionType, gui) -> {
                GuiSounds.click(this.getPlayer());
                action.run();
            });
        }
        return builder;
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

        // Material counts can change while the crafting screen is open. Rebuilding the page every
        // second keeps the red/green requirements and the craftable-first order current without
        // resetting the anvil input.
        this.pinFooter();
        if (++this.ticks >= 20) {
            this.ticks = 0;
            this.refresh();
        }
        super.onTick();
    }

    @Override
    public void onRemoved() {
        this.closeSource();
        super.onRemoved();
    }

    @Override
    public void onPlayerClose(boolean clientSide) {
        this.closeSource();
        super.onPlayerClose(clientSide);
    }

    @Override
    public void onManualClose() {
        this.closeSource();
        super.onManualClose();
    }

    /**
     * Keeps the footer buttons on screen while the player's own inventory changes.
     *
     * <p>An anvil screen draws its footer in the nine hotbar cells, and the client's copy of a cell
     * is replaced whenever the player's inventory slot behind it changes: the item the player is
     * holding wins over whatever button was sent there, which is why the close button turned into
     * an apple but the other three buttons were fine. SGui only sends a slot when its element
     * changes, so the button is re-sent here whenever the cells behind the footer change.
     */
    private void pinFooter() {
        var inventory = this.getPlayer().getInventory();
        boolean changed = false;
        for (int i = 0; i < FOOTER.length; i++) {
            var current = inventory.getItem(FOOTER_HOTBAR[i]);
            if (!sameStack(current, this.lastHotbar[i])) {
                this.lastHotbar[i] = current.copy();
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        for (var entry : this.pinned.entrySet()) {
            SguiUtils.sendSlotUpdate(this.getPlayer(), this.getSyncId(), entry.getKey(), entry.getValue(),
                    this.wrappedMenu.getStateId());
        }
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        return ItemStack.isSameItemSameComponents(first, second) && first.getCount() == second.getCount();
    }

    private void closeSource() {
        if (this.sourceClosed) {
            return;
        }
        this.sourceClosed = true;
        try {
            this.source.removed(this.getPlayer());
        } catch (RuntimeException exception) {
            PaladinsFurniturePatch.LOGGER.warn("Could not close the workbench menu", exception);
        }
    }
}
