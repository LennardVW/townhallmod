package dev.townhall.nick;

/** Implemented by the server's name cache (CachedUserNameToIdResolverMixin): is this name stored, without asking Mojang? */
public interface KnownNames {
	/** @param lowerCaseName name in lower case (Locale.ROOT) */
	boolean townhall$knows(String lowerCaseName);
}
