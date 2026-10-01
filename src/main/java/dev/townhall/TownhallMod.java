package dev.townhall;

import dev.townhall.command.BuilderCommand;
import dev.townhall.city.RoleCommand;
import dev.townhall.city.RoleStorage;
import dev.townhall.city.PlotCommand;
import dev.townhall.city.PlotStorage;
import dev.townhall.city.CityCommand;
import dev.townhall.city.PoliceCommand;
import dev.townhall.audit.AuditLog;
import dev.townhall.audit.AuditCommands;
import dev.townhall.election.ElectionService;
import dev.townhall.election.ElectionStorage;
import dev.townhall.shop.ShopCommands;
import dev.townhall.shop.ShopService;
import dev.townhall.shop.ShopData;
import dev.townhall.activity.ActivityService;
import dev.townhall.activity.Afk;
import dev.townhall.command.ActivityCommands;
import dev.townhall.command.ChatDisplayCommands;
import dev.townhall.command.KeyCommand;
import dev.townhall.display.JoinMessages;
import dev.townhall.display.TabList;
import dev.townhall.command.LocationCommand;
import dev.townhall.key.Keys;
import dev.townhall.command.NickCommand;
import dev.townhall.nick.NickPackets;
import dev.townhall.nick.Nicknames;
import dev.townhall.command.RulesCommand;
import dev.townhall.command.TownhallCommand;
import dev.townhall.onboarding.Onboarding;
import dev.townhall.protection.BuilderPermissions;
import dev.townhall.protection.Protection;
import dev.townhall.config.ConfigManager;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.display.DeathsInTab;
import dev.townhall.storage.ReturnPositionStorage;
import dev.townhall.teleport.ConfinementService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

public class TownhallMod implements ModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("Townhall");
	public static final ConfigManager CONFIG = new ConfigManager();

	@Override
	public void onInitialize() {
		CONFIG.loadOrCreate();
		DimensionSettings.rebuild(CONFIG.get());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerCommands(dispatcher);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(TownhallMod::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(TownhallMod::onServerStopped);
		// Respawn, in this order: confined players (e.g. prison) are put back inside their location first, so the world
		// settings, builder mode and tab list score below apply to the world they end up in.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) ConfinementService.keepConfined(newPlayer, CONFIG.get());
			DimensionSettings.onEnter(newPlayer);
			Protection.enforceBuilderMode(newPlayer);
			DeathsInTab.sync(newPlayer);
		});
		// Join: nickname maps, the world's difficulty/time (the client only knows one), builder mode, deaths, onboarding, join message, tab list.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Nicknames.onJoin(handler.player);
			DimensionSettings.onEnter(handler.player);
			Protection.enforceBuilderMode(handler.player);
			DeathsInTab.sync(handler.player);
			Onboarding.onJoin(handler.player);
			NickPackets.onJoin(handler.player);
			JoinMessages.onJoin(handler.player);
			TabList.update(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Onboarding.onDisconnect(handler.player);
			NickPackets.onLeave(handler.player);
			Afk.onLeave(handler.player);
			JoinMessages.onLeave(handler.player);
		});
		// World change: that world's difficulty/time/food, and builders may only be in creative inside their builder worlds.
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			DimensionSettings.onEnter(player);
			Protection.enforceBuilderMode(player);
		});
		ElectionService.register();
		ShopService.registerEvents();
		AuditLog.register();
		Protection.register();
		BuilderPermissions.register();
		Keys.register();
		ActivityService.register();
		JoinMessages.register();
		// Once per second: confinement and timers, and players who haven't accepted the rules yet.
		ServerTickEvents.END_SERVER_TICK.register(ConfinementService::onServerTick);
		LOGGER.info("Townhall initialized with locations {}", CONFIG.get().locations.keySet());
	}

	/** The same registration order is used at startup and in command collision regression tests. */
	public static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
		// Fixed commands own their names before configurable location roots are added. Otherwise Brigadier merges
		// a location named "role", for example, into the administration command and keeps its location requirement.
		RulesCommand.register(dispatcher);
		LocationCommand.register(dispatcher);
		BuilderCommand.register(dispatcher);
		NickCommand.register(dispatcher);
		KeyCommand.register(dispatcher);
		ActivityCommands.register(dispatcher);
		ChatDisplayCommands.register(dispatcher);
		RoleCommand.register(dispatcher);
		PlotCommand.register(dispatcher);
		CityCommand.register(dispatcher);
		PoliceCommand.register(dispatcher);
		ElectionService.register(dispatcher);
		ShopCommands.register(dispatcher);
		AuditCommands.register(dispatcher);
		TownhallCommand.register(dispatcher);
	}

	/** Other mods may add dimensions late, so this only reports; commands look the dimension up again every time. */
	private static void onServerStarted(MinecraftServer server) {
		TownhallConfig config = CONFIG.get();
		RoleStorage.get(server);
		PlotStorage.get(server);
		ElectionStorage.get(server);
		ShopData.get(server);
		DeathsInTab.apply(server);
		Nicknames.load(server);
		config.locations.forEach((id, loc) -> {
			if (server.getLevel(loc.dimensionKey()) != null) {
				LOGGER.info("Location {} (/{}): dimension {} found", id, loc.command, loc.dimension);
			} else {
				LOGGER.error("Location {} (/{}): dimension {} is not loaded; the command will fail until it exists. Loaded dimensions: {}",
						id, loc.command, loc.dimension, loadedDimensions(server));
			}
		});
		ReturnPositionStorage storage = ReturnPositionStorage.get(server);
		LOGGER.info("Loaded {} stored return positions ({} confined players)", storage.returnPositionCount(), storage.confinedCount());
	}

	/**
	 * In-memory state of the stopped server goes, so a second server in the same JVM (singleplayer: leave and open
	 * another world) starts clean and nothing keeps the old worlds alive. Stored data lives in SavedData and stays.
	 */
	private static void onServerStopped(MinecraftServer server) {
		TownhallCommand.reset();
		KeyCommand.reset();
		Onboarding.reset();
		Afk.reset();
		PlotCommand.reset();
		AuditLog.reset();
	}

	/** Operator = has the permission level from commands.operatorPermissionLevel. Operators are never confined. */
	public static boolean isOperator(PermissionSet permissions) {
		Permission required = switch (CONFIG.get().commands.operatorPermissionLevel) {
			case 1 -> Permissions.COMMANDS_MODERATOR;
			case 3 -> Permissions.COMMANDS_ADMIN;
			case 4 -> Permissions.COMMANDS_OWNER;
			default -> Permissions.COMMANDS_GAMEMASTER;
		};
		return permissions.hasPermission(required);
	}

	public static String loadedDimensions(MinecraftServer server) {
		return server.levelKeys().stream().map(k -> k.identifier().toString()).sorted().collect(Collectors.joining(", "));
	}
}
