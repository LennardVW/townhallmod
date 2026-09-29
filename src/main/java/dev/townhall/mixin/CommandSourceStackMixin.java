package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.nick.Nicknames;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Server-side name suggestions (see CommandNodeInspectorMixin) also offer the nicknames of online players. */
@Mixin(CommandSourceStack.class)
abstract class CommandSourceStackMixin {

	@ModifyReturnValue(method = "getOnlinePlayerNames", at = @At("RETURN"))
	private Collection<String> townhall$nicknames(Collection<String> names) {
		List<String> nicks = Nicknames.suggestions(((CommandSourceStack) (Object) this).getServer());
		if (nicks.isEmpty()) return names;
		List<String> all = new ArrayList<>(names);
		all.addAll(nicks);
		return all;
	}
}
