package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import dev.townhall.audit.AuditLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Commands.class)
abstract class AuditCommandsMixin {
	@WrapMethod(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V")
	private void townhall$auditCommand(ParseResults<CommandSourceStack> parse, String command, Operation<Void> original) {
		if (!AuditLog.enabled()) { original.call(parse, command); return; }
		try (var scope = AuditLog.commandScope(parse.getContext().getSource())) {
			original.call(parse, command);
		}
	}
}
