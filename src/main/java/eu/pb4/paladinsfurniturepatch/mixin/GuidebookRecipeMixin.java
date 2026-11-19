package eu.pb4.paladinsfurniturepatch.mixin;

import eu.pb4.paladinsfurniturepatch.PaladinsFurniturePatch;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/** Drops the guidebook recipe, since the guidebook itself is no longer offered. */
@Mixin(RecipeManager.class)
public class GuidebookRecipeMixin {
    private static final Identifier GUIDEBOOK_RECIPE =
            Identifier.fromNamespaceAndPath("pfm", "furniture_book");

    @Shadow
    private RecipeMap recipes;

    @Inject(method = "apply", at = @At("TAIL"))
    private void paladinsFurniturePatch$removeGuidebookRecipe(RecipeMap loaded, ResourceManager manager,
                                                              ProfilerFiller profiler, CallbackInfo callback) {
        var kept = new ArrayList<RecipeHolder<?>>(this.recipes.values().size());
        boolean removed = false;
        for (var holder : this.recipes.values()) {
            if (holder.id().identifier().equals(GUIDEBOOK_RECIPE)) {
                removed = true;
            } else {
                kept.add(holder);
            }
        }

        if (removed) {
            this.recipes = RecipeMap.create(kept);
            PaladinsFurniturePatch.LOGGER.info("Removed the PFM guidebook recipe");
        }
    }
}
