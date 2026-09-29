package dev.townhall.test.mixin;

import dev.townhall.nick.KnownNames;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Locale;
import java.util.Set;

/** The GameTest server has its own name cache; give it the same "known name" check as the real one. */
@Mixin(targets = "net.minecraft.gametest.framework.GameTestServer$MockUserNameToIdResolver")
abstract class MockUserNameToIdResolverMixin implements KnownNames {

	@Shadow
	@Final
	private Set<NameAndId> savedIds;

	@Override
	public boolean townhall$knows(String lowerCaseName) {
		return savedIds.stream().anyMatch(n -> n.name().toLowerCase(Locale.ROOT).equals(lowerCaseName));
	}
}
