package dev.townhall.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Who really sent a command: the player, the server console, a sign (CommandSource.NULL), a command block. */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {

	@Accessor("source")
	CommandSource townhall$source();
}
