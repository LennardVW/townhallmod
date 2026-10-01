package dev.townhall.mixin;

import dev.townhall.nick.KnownNames;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/** Read-only look at the name cache (usercache.json) without the Mojang lookup that get(name) does for unknown names. */
@Mixin(CachedUserNameToIdResolver.class)
abstract class CachedUserNameToIdResolverMixin implements KnownNames {

	/** Keys are lower-case names. */
	@Shadow
	@Final
	private Map<String, ?> profilesByName;

	@Override
	public boolean townhall$knows(String lowerCaseName) {
		return profilesByName.containsKey(lowerCaseName);
	}
}
