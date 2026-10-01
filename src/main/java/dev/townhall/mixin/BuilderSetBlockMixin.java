package dev.townhall.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.townhall.protection.BuilderSchematics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.SetBlockCommand;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(SetBlockCommand.class)
abstract class BuilderSetBlockMixin {
	@Inject(method = "setBlock", at = @At("HEAD"))
	private static void townhall$check(CommandSourceStack source, BlockPos pos, BlockInput block, @Coerce Object mode,
			Predicate<BlockInWorld> filter, boolean strict, CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
		BuilderSchematics.check(source, BoundingBox.fromCorners(pos, pos), block);
	}
}
