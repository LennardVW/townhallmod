package dev.townhall.mixin;

import dev.townhall.protection.BuilderSchematics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.server.commands.SetBlockCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

@Mixin({SetBlockCommand.class, FillCommand.class})
abstract class BuilderSchematicCommandMixin {
	@ModifyArg(method = "register", at = @At(value = "INVOKE",
			target = "Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;requires(Ljava/util/function/Predicate;)Lcom/mojang/brigadier/builder/ArgumentBuilder;"), index = 0)
	private static Predicate<CommandSourceStack> townhall$builderRequirement(Predicate<CommandSourceStack> original) {
		return source -> original.test(source) || BuilderSchematics.mayUse(source);
	}
}
