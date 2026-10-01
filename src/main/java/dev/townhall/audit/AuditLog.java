package dev.townhall.audit;

import dev.townhall.TownhallMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit successful admin records and synchronous block attribution, with no command transcript capture.
 * Async WorldEdit workers, deferred changes, direct chunk writes and block entity NBT are outside this scope.
 * Callers must never pass chat, ballot contents, nicknames or entire typed commands as detail.
 */
public final class AuditLog {
	/** Reserved actor for console/command sources without a player; actorName identifies that source. */
	public static final UUID SYSTEM_ACTOR = new UUID(0, 0);
	private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
	private AuditLog() {}

	/** Register during mod initialization: preload on startup, so first block writes never read a file. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(AuditLog::initialize);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
	}

	/** Alternative for a parent that already owns lifecycle handlers; load once before gameplay starts. */
	public static void initialize(MinecraftServer server) { AuditStorage.get(server); }
	public static void reset() { CURRENT.remove(); }

	public static boolean enabled() { return TownhallMod.CONFIG.get().city.auditEnabled; }

	public static void record(CommandSourceStack source, String action, String detail) {
		ServerPlayer player = source.getPlayer();
		record(source.getServer(), player == null ? SYSTEM_ACTOR : player.getUUID(),
				player == null ? source.getTextName() : player.getGameProfile().name(), action,
				source.getLevel().dimension().identifier(), BlockPos.containing(source.getPosition()), "", "", detail);
	}

	public static void record(MinecraftServer server, ServerPlayer actor, String action, String detail) {
		record(server, actor.getUUID(), actor.getGameProfile().name(), action,
				actor.level().dimension().identifier(), actor.blockPosition(), "", "", detail);
	}

	public static void record(MinecraftServer server, UUID actorId, String actorName, String action, String detail) {
		record(server, actorId, actorName, action, null, null, "", "", detail);
	}

	public static void record(MinecraftServer server, UUID actorId, String actorName, String action,
			Identifier dimension, BlockPos position, String before, String after, String detail) {
		if (!enabled() || !server.isSameThread()) return;
		AuditStorage.get(server).append(System.currentTimeMillis(), actorId, actorName, action,
				Optional.ofNullable(dimension), Optional.ofNullable(position), before, after, detail, AuditStorage.configuredLimit());
	}

	/** Placement/break scopes discard their bounded pending changes unless explicitly committed on success. */
	public static Scope playerScope(ServerPlayer player, String action) {
		return new Scope(player.level().getServer(), player.getUUID(), player.getGameProfile().name(), action, true);
	}

	/** Records each changed block immediately, including synchronous setblock/fill/WorldEdit calls. */
	public static Scope commandScope(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return new Scope(source.getServer(), player == null ? SYSTEM_ACTOR : player.getUUID(),
				player == null ? source.getTextName() : player.getGameProfile().name(), "block.command", false);
	}

	/** Cheap guard before reading any old state on the very hot setBlock path. */
	public static boolean tracks(ServerLevel level) {
		Scope scope = CURRENT.get();
		return scope != null && scope.server == level.getServer() && enabled() && scope.server.isSameThread();
	}

	public static boolean hasActorScope() { return CURRENT.get() != null; }

	public static void changed(ServerLevel level, BlockPos position, BlockState before, BlockState after) {
		if (before.equals(after) || !tracks(level)) return;
		Scope scope = CURRENT.get();
		Change change = new Change(System.currentTimeMillis(), level.dimension().identifier(), position.immutable(),
				AuditEntry.shortText(before.toString(), AuditEntry.MAX_TEXT), AuditEntry.shortText(after.toString(), AuditEntry.MAX_TEXT));
		if (scope.buffered) {
			while (scope.pending.size() >= AuditStorage.configuredLimit()) scope.pending.removeFirst();
			scope.pending.addLast(change);
		} else scope.write(change);
	}

	private record Change(long timestamp, Identifier dimension, BlockPos position, String before, String after) {}

	/** Use try-with-resources: cleanup restores a nested caller even on cancellation or exceptions. */
	public static final class Scope implements AutoCloseable {
		private final Scope previous;
		private final MinecraftServer server;
		private final UUID actorId;
		private final String actorName;
		private final String action;
		private final boolean buffered;
		private final ArrayDeque<Change> pending = new ArrayDeque<>();
		private boolean committed;
		private boolean closed;

		private Scope(MinecraftServer server, UUID actorId, String actorName, String action, boolean buffered) {
			this.previous = CURRENT.get();
			this.server = server;
			this.actorId = actorId;
			this.actorName = actorName;
			this.action = action;
			this.buffered = buffered;
			CURRENT.set(this);
		}

		public void commit() { committed = true; }

		private void write(Change change) {
			if (!enabled() || !server.isSameThread()) return;
			AuditStorage.get(server).append(change.timestamp, actorId, actorName, action,
					Optional.of(change.dimension), Optional.of(change.position), change.before, change.after, "", AuditStorage.configuredLimit());
		}

		@Override public void close() {
			if (closed) return;
			closed = true;
			try {
				if (committed) for (Change change : pending) write(change);
			} finally {
				pending.clear();
				if (previous == null) CURRENT.remove();
				else CURRENT.set(previous);
			}
		}
	}
}
