package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.key.Keys;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/** The crafter block doesn't turn keys into trapped chests or crossbows either. */
@Mixin(CrafterBlock.class)
abstract class CrafterBlockMixin {

	@ModifyReturnValue(method = "getPotentialResults", at = @At("RETURN"))
	private static Optional<RecipeHolder<CraftingRecipe>> townhall$noKeys(Optional<RecipeHolder<CraftingRecipe>> recipe, ServerLevel level, CraftingInput input) {
		return recipe.isPresent() && Keys.containsKey(input.items()) ? Optional.empty() : recipe;
	}
}
