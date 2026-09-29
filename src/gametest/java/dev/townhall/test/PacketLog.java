package dev.townhall.test;

import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Packets sent to watched players (filled by PacketLogMixin). Only watched UUIDs are kept. */
public final class PacketLog {

	private static final Map<UUID, List<Packet<?>>> LOG = new ConcurrentHashMap<>();

	private PacketLog() {}

	public static void watch(UUID player) {
		LOG.put(player, java.util.Collections.synchronizedList(new ArrayList<>()));
	}

	public static void stop(UUID player) {
		LOG.remove(player);
	}

	public static void record(UUID player, Packet<?> packet) {
		List<Packet<?>> list = LOG.get(player);
		if (list != null) list.add(packet);
	}

	public static <T extends Packet<?>> List<T> of(UUID player, Class<T> type) {
		List<Packet<?>> list = LOG.getOrDefault(player, List.of());
		synchronized (list) {
			return list.stream().filter(type::isInstance).map(type::cast).toList();
		}
	}
}
