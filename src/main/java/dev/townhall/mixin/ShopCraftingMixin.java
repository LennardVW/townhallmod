package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.townhall.shop.ShopService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Optional;

/** Civic items must not become ordinary recipe output that can be sold. */
@Mixin(CraftingMenu.class)
abstract class ShopCraftingMixin {
    @ModifyExpressionValue(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;"))
    private static Optional<RecipeHolder<CraftingRecipe>> townhall$noCivicIngredients(Optional<RecipeHolder<CraftingRecipe>> recipe,
        AbstractContainerMenu menu, ServerLevel level, Player player, CraftingContainer slots, ResultContainer result,
        RecipeHolder<CraftingRecipe> hint) {
        return recipe.isPresent() && ShopService.containsForbiddenGoods(slots.getItems()) ? Optional.empty() : recipe;
    }
}
