package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.StringReader;
import dev.townhall.command.NickCommand;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets entity arguments read nicknames: vanilla reads a plain name only from [0-9A-Za-z_-.+] and at most 16 characters,
 * so "Bürgermeister" would stop at the "ü". Unquoted names may also contain other letters and digits now, and quoted
 * names ("Der Bürgermeister") may be as long as a nickname. Inputs that vanilla accepted are read exactly as before.
 */
@Mixin(EntitySelectorParser.class)
abstract class EntitySelectorParserMixin {

	@WrapOperation(method = "parseNameOrUUID", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/StringReader;readString()Ljava/lang/String;"))
	private String townhall$readLetters(StringReader reader, Operation<String> original) {
		int start = reader.getCursor();
		boolean quoted = reader.canRead() && StringReader.isQuotedStringStart(reader.peek());
		String name = original.call(reader);
		if (quoted) return name;
		int end = reader.getCursor();
		while (reader.canRead() && (Character.isLetterOrDigit(reader.peek()) || StringReader.isAllowedInUnquotedString(reader.peek()))) reader.skip();
		return reader.getCursor() == end ? name : reader.getString().substring(start, reader.getCursor());
	}

	@ModifyExpressionValue(method = "parseNameOrUUID", at = @At(value = "CONSTANT", args = "intValue=16"))
	private int townhall$nickLength(int vanilla) {
		return Math.max(vanilla, NickCommand.MAX_LENGTH);
	}
}
