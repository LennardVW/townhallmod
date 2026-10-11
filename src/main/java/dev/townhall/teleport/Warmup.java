package dev.townhall.teleport;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Standing-still countdown before a teleport: title "3 / 2 / 1" with "Nicht bewegen" and particles around the player.
 * Cancel mode (home, tpa): moving, damage, death, disconnect or a world change abort. Freeze mode (return): the player
 * is held in place instead. Tick based, state per UUID, server thread only.
 */
public final class Warmup {

	public enum Cancel { MOVED, DAMAGE, WORLD }

	private static final double MAX_MOVE = 0.2;

	private static final class Wait {
		final ServerPlayer player;
		final ServerLevel level;
		final Vec3 anchor;
		final long startTick;
		final long endTick;
		final boolean freeze;
		final Consumer<ServerPlayer> action;
		final BiConsumer<ServerPlayer, Cancel> onCancel;
		int shown = -1;

		Wait(ServerPlayer player, long startTick, long endTick, boolean freeze, Consumer<ServerPlayer> action, BiConsumer<ServerPlayer, Cancel> onCancel) {
			this.player = player;
			this.level = (ServerLevel) player.level();
			this.anchor = player.position();
			this.startTick = startTick;
			this.endTick = endTick;
			this.freeze = freeze;
			this.action = action;
			this.onCancel = onCancel;
		}
	}

	private static final Map<UUID, Wait> WAITING = new HashMap<>();

	private Warmup() {}

	public static boolean isWaiting(ServerPlayer player) {
		return WAITING.containsKey(player.getUUID());
	}

	public static void start(ServerPlayer player, int seconds, boolean freeze, Consumer<ServerPlayer> action, BiConsumer<ServerPlayer, Cancel> onCancel) {
		long now = player.level().getServer().getTickCount();
		Wait w = new Wait(player, now, now + seconds * 20L, freeze, action, onCancel);
		WAITING.put(player.getUUID(), w);
		show(w, now);
	}

	public static void clear() {
		WAITING.clear();
	}

	/** Called for every hit a player takes; aborts a cancelable warmup. */
	public static void damaged(ServerPlayer player) {
		Wait w = WAITING.get(player.getUUID());
		if (w == null || w.freeze) return;
		WAITING.remove(player.getUUID());
		if (w.onCancel != null) w.onCancel.accept(player, Cancel.DAMAGE);
	}

	public static void onServerTick(MinecraftServer server) {
		if (WAITING.isEmpty()) return;
		long now = server.getTickCount();
		List<Wait> due = new ArrayList<>();
		List<Wait> cancelled = new ArrayList<>();
		List<Cancel> reasons = new ArrayList<>();
		Iterator<Map.Entry<UUID, Wait>> it = WAITING.entrySet().iterator();
		while (it.hasNext()) {
			Wait w = it.next().getValue();
			ServerPlayer p = w.player;
			if (p.isRemoved() || !p.isAlive()) {
				it.remove();
				continue;
			}
			if (p.level() != w.level) {
				it.remove();
				if (!w.freeze && w.onCancel != null) {
					cancelled.add(w);
					reasons.add(Cancel.WORLD);
				}
				continue;
			}
			if (w.freeze) {
				if (p.position().distanceToSqr(w.anchor) > 0.01) {
					p.teleportTo(w.level, w.anchor.x, w.anchor.y, w.anchor.z, java.util.Set.of(), p.getYRot(), p.getXRot(), true);
				}
			} else if (moved(p.position(), w.anchor)) {
				it.remove();
				if (w.onCancel != null) {
					cancelled.add(w);
					reasons.add(Cancel.MOVED);
				}
				continue;
			}
			if (now >= w.endTick) {
				it.remove();
				due.add(w);
				continue;
			}
			show(w, now);
		}
		for (int i = 0; i < cancelled.size(); i++) cancelled.get(i).onCancel.accept(cancelled.get(i).player, reasons.get(i));
		for (Wait w : due) w.action.accept(w.player);
	}

	private static boolean moved(Vec3 now, Vec3 anchor) {
		double dx = now.x - anchor.x, dz = now.z - anchor.z;
		return dx * dx + dz * dz > MAX_MOVE * MAX_MOVE || Math.abs(now.y - anchor.y) > MAX_MOVE;
	}

	/** Title when the second changes, particles every third tick (a spiral that gets denser each second). */
	private static void show(Wait w, long now) {
		long left = Math.max(1, w.endTick - now);
		int secs = (int) ((left + 19) / 20);
		if (secs != w.shown) {
			w.shown = secs;
			var conn = w.player.connection;
			conn.send(new ClientboundSetTitlesAnimationPacket(0, 22, 4));
			conn.send(new ClientboundSetSubtitleTextPacket(Component.literal("Nicht bewegen").withStyle(ChatFormatting.GRAY)));
			conn.send(new ClientboundSetTitleTextPacket(Component.literal(String.valueOf(secs)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
		}
		long elapsed = now - w.startTick;
		if (elapsed % 3 != 0) return;
		int points = 3 + (int) (elapsed / 20) * 2;
		double phase = elapsed * 0.35;
		double rise = (elapsed % 40) / 40.0 * 1.8;
		Vec3 pos = w.player.position();
		for (int k = 0; k < points; k++) {
			double a = phase + k * (Math.PI * 2 / points);
			double y = pos.y + (rise + k * 0.3) % 1.9;
			w.level.sendParticles(ParticleTypes.PORTAL, pos.x + Math.cos(a) * 0.8, y, pos.z + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
		}
	}
}
