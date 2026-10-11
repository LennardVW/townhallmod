package dev.townhall.test;

import dev.townhall.TownhallMod;
import dev.townhall.city.*;
import dev.townhall.config.TownhallConfig;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.protection.Protection;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

public class CityGameTests {
	@GameTest
	public void locationNamesCannotMergeIntoCityCommands(GameTestHelper h) {
		var config = TownhallMod.CONFIG.get();
		var original = new LinkedHashMap<>(config.locations);
		var names = List.of("role", "rolle", "stadt", "plot", "polizei", "wahl", "shop", "audit");
		try {
			for (String name : names) {
				var location = new TownhallConfig.Location();
				location.command = name;
				config.locations.put("collision_" + name, location);
			}
			var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
			TownhallMod.registerCommands(dispatcher);
			for (String name : names) {
				var node = dispatcher.getRoot().getChild(name);
				h.assertTrue(node != null && node.getChild("send") == null && node.getChild("return") == null,
						"location named " + name + " cannot merge teleport commands into a city root");
			}
			var player = h.makeMockServerPlayerInLevel();
			var source = h.getLevel().getServer().createCommandSourceStack().withEntity(player);
			var role = dispatcher.getRoot().getChild("role");
			h.assertTrue(role.getChild("grant") != null, "role administration remains registered");
			h.assertFalse(role.canUse(source), "normal players cannot administer roles despite conflicting location");
			h.assertTrue(role.canUse(h.getLevel().getServer().createCommandSourceStack()), "console retains role administration");
		} finally {
			config.locations.clear();
			config.locations.putAll(original);
		}
		h.succeed();
	}
	@GameTest
	public void defaultRolesAndNoElevatedPowers(GameTestHelper h) {
		RoleStorage storage = new RoleStorage(); UUID id=UUID.randomUUID();
		h.assertTrue(storage.definitions().keySet().containsAll(List.of("buergermeister","polizei","haendler","architekt","wahlhelfer")),"five predefined town roles");
		storage.grant(id,"OfflinePlayer","architekt");
		h.assertTrue(!storage.has(id,"shop.manage") && !storage.has(id,"creative") && !storage.has(id,"worldedit"),"architect has no creative/op/shop permission");
		storage.grant(id,"OfflinePlayer","polizei");
		h.assertTrue(storage.has(id,"police.jail") && storage.has(id,"police.release"),"police has only designated jail rights");
		storage.revoke(id,"polizei");
		h.assertTrue(!storage.has(id,"police.jail"),"revoking role removes its rights"); h.succeed();
	}
	@GameTest
	public void rolesSurviveCodecAndRename(GameTestHelper h) {
		RoleStorage original = new RoleStorage(); UUID id=UUID.randomUUID();
		original.grant(id,"OldName","haendler");
		var encoded=RoleStorage.CODEC.encodeStart(NbtOps.INSTANCE,original).getOrThrow();
		var decoded=RoleStorage.CODEC.parse(NbtOps.INSTANCE,encoded).getOrThrow();
		h.assertTrue(decoded.has(id,"shop.manage"),"offline UUID persists through native saved-data codec");
		decoded.grant(id,"NewName","architekt");
		h.assertTrue(decoded.has(id,"shop.manage") && decoded.members().get(id).name().equals("NewName"),"renaming preserves roles by UUID");
		decoded.remove("haendler");
		h.assertTrue(!decoded.has(id,"shop.manage"),"deleting a role cleans all memberships"); h.succeed();
	}
	@GameTest
	public void disabledPlotsLeaveFreeWorld(GameTestHelper h) {
		ServerPlayer p=h.makeMockServerPlayerInLevel(); var config=TownhallMod.CONFIG.get();
		boolean enabled=config.city.claimsEnabled; var old=config.dimensions.get(p.level().dimension().identifier().toString());
		String dim=p.level().dimension().identifier().toString(), id="test_"+UUID.randomUUID().toString().substring(0,8); BlockPos pos=h.absolutePos(new BlockPos(2,1,2));
		var storage=PlotStorage.get(p.level().getServer());
		storage.put(id,new Plot(dim,pos.getX(),pos.getZ(),pos.getX()+1,pos.getZ()+1,Optional.of(UUID.randomUUID().toString()),List.of(),List.of(),true));
		try {
			TownhallConfig.DimensionRules rule=new TownhallConfig.DimensionRules();rule.build=true;config.dimensions.put(dim,rule);DimensionSettings.rebuild(config);
			config.city.claimsEnabled=false;
			h.assertTrue(Protection.mayBuildAt(p,p.level(),pos),"disabled claims permit building in free world even in a defined plot");
			config.city.claimsEnabled=true;
			h.assertTrue(!Protection.mayBuildAt(p,p.level(),pos),"enabled claims protect foreign plot");
			h.assertTrue(Protection.mayBuildAt(p,p.level(),pos.offset(3,0,0)),"unclaimed area stays free");
		} finally { config.city.claimsEnabled=enabled;storage.remove(id);if(old==null)config.dimensions.remove(dim);else config.dimensions.put(dim,old);DimensionSettings.rebuild(config); }
		h.succeed();
	}
	@GameTest
	public void plotOwnerAndTrustedRoleCanBuild(GameTestHelper h) {
		ServerPlayer p=h.makeMockServerPlayerInLevel();var config=TownhallMod.CONFIG.get();boolean enabled=config.city.claimsEnabled;
		String dim=p.level().dimension().identifier().toString(),id="test_"+UUID.randomUUID().toString().substring(0,8);BlockPos pos=h.absolutePos(new BlockPos(2,1,2));
		var plots=PlotStorage.get(p.level().getServer());var roles=RoleStorage.get(p.level().getServer());
		try {
			config.city.claimsEnabled=true;
			Plot plot=new Plot(dim,pos.getX(),pos.getZ(),pos.getX(),pos.getZ(),Optional.of(p.getUUID().toString()),List.of(),List.of(),true);plots.put(id,plot);
			h.assertTrue(PlotService.canBuild(p,p.level(),pos),"owner can build");
			plots.put(id,new Plot(dim,pos.getX(),pos.getZ(),pos.getX(),pos.getZ(),Optional.empty(),List.of(),List.of("architekt"),true));
			h.assertTrue(!PlotService.canBuild(p,p.level(),pos),"architect site closed until role assigned");
			roles.grant(p.getUUID(),p.getGameProfile().name(),"architekt");
			h.assertTrue(PlotService.canBuild(p,p.level(),pos),"explicitly trusted role builds here");
			roles.revoke(p.getUUID(),"architekt");
			h.assertTrue(!PlotService.canBuild(p,p.level(),pos),"role removal immediately removes local building right");
		}finally { config.city.claimsEnabled=enabled;plots.remove(id);roles.revoke(p.getUUID(),"architekt"); }
		h.succeed();
	}
	@GameTest
	public void plotSelfManagement(GameTestHelper h) {
		ServerPlayer owner=h.makeMockServerPlayerInLevel(),friend=h.makeMockServerPlayerInLevel(),stranger=h.makeMockServerPlayerInLevel(),manager=h.makeMockServerPlayerInLevel();
		var config=TownhallMod.CONFIG.get();boolean enabled=config.city.claimsEnabled;var plots=PlotStorage.get(owner.level().getServer());
		String dim=owner.level().dimension().identifier().toString(),id="test_"+UUID.randomUUID().toString().substring(0,8);BlockPos pos=h.absolutePos(new BlockPos(2,1,2));
		try {
			config.city.claimsEnabled=true;
			plots.put(id,new Plot(dim,pos.getX(),pos.getZ(),pos.getX(),pos.getZ(),Optional.of(owner.getUUID().toString()),List.of(),List.of(),true));
			int k=10;for(ServerPlayer q:List.of(owner,friend,stranger,manager)) q.absSnapTo(pos.getX()+0.5+(k+=10),pos.getY(),pos.getZ()+0.5,0,0);
			h.assertTrue(!PlotService.canBuild(friend,friend.level(),pos),"friend cannot build yet");
			command(at(stranger,pos),"plot trust "+sel(friend));
			h.assertTrue(plots.plots().get(id).trusted().isEmpty() && !PlotService.canBuild(friend,friend.level(),pos),"stranger trust rejected, no change");
			command(at(owner,pos),"plot trust "+sel(friend));
			h.assertTrue(PlotService.canBuild(friend,friend.level(),pos),"owner trust at own position lets friend build");
			command(at(owner,pos),"plot manager "+id+" "+sel(manager));
			h.assertTrue(plots.plots().get(id).managers().contains(manager.getUUID().toString()) && PlotService.canBuild(manager,manager.level(),pos),"manager added and can build");
			command(at(manager,pos),"plot untrust "+sel(friend));
			h.assertTrue(!PlotService.canBuild(friend,friend.level(),pos),"manager may untrust");
			command(at(manager,pos),"plot manager "+sel(stranger));
			h.assertTrue(plots.plots().get(id).managers().size()==1,"manager cannot appoint managers");
			command(at(manager,pos),"plot transfer "+sel(manager));
			h.assertTrue(plots.plots().get(id).isOwner(owner.getUUID()),"manager cannot transfer");
			command(at(owner,pos),"plot transfer "+sel(friend));
			var moved=plots.plots().get(id);
			h.assertTrue(moved.isOwner(friend.getUUID()) && !PlotService.canBuild(owner,owner.level(),pos),"old owner loses building right after transfer");
			h.assertTrue(PlotService.canBuild(friend,friend.level(),pos) && PlotService.canBuild(manager,manager.level(),pos),"new owner and manager can build");
		} finally { config.city.claimsEnabled=enabled;plots.remove(id); }
		h.succeed();
	}
	private static void snapChunk(ServerPlayer p,int cx,int cz,BlockPos pos) { p.absSnapTo(cx*16+8.5,pos.getY(),cz*16+8.5,0,0); }
	private static long ownedCount(PlotStorage plots,UUID who) { return plots.plots().values().stream().filter(x -> x.isOwner(who)).count(); }
	@GameTest
	public void plotClaimExpandAndCoOwners(GameTestHelper h) {
		ServerPlayer a=h.makeMockServerPlayerInLevel(),b=h.makeMockServerPlayerInLevel(),c=h.makeMockServerPlayerInLevel();
		var server=a.level().getServer();var config=TownhallMod.CONFIG.get();boolean enabled=config.city.claimsEnabled;int per=config.city.claimChunksPerPlayer;
		var plots=PlotStorage.get(server);BlockPos pos=h.absolutePos(new BlockPos(2,1,2));int cx=(pos.getX()>>4)+40,cz=pos.getZ()>>4;
		var admin=server.createCommandSourceStack();
		try {
			config.city.claimsEnabled=true;config.city.claimChunksPerPlayer=1;
			String na=sel(a),nb=sel(b),nc=sel(c);
			snapChunk(a,cx,cz,pos);command(a.createCommandSourceStack(),"plot claim");
			var mine=plots.ownedBy(a.getUUID()).orElseThrow();String id=mine.getKey();
			h.assertTrue(mine.getValue().chunkCount()==1 && mine.getValue().minX()==cx*16 && mine.getValue().maxZ()==cz*16+15,"claim creates exactly the standing chunk");
			snapChunk(a,cx,cz+3,pos);command(a.createCommandSourceStack(),"plot claim");
			h.assertTrue(ownedCount(plots,a.getUUID())==1,"second claim by same player rejected");
			snapChunk(b,cx,cz,pos);command(b.createCommandSourceStack(),"plot claim");
			h.assertTrue(plots.ownedBy(b.getUUID()).isEmpty(),"claim inside foreign plot rejected");
			snapChunk(a,cx+1,cz,pos);command(a.createCommandSourceStack(),"plot expand");
			h.assertTrue(plots.plots().get(id).chunkCount()==1,"expand without quota rejected");
			command(a.createCommandSourceStack(),"plot coowner add "+id+" "+nb);
			h.assertTrue(plots.plots().get(id).coOwners().isEmpty(),"invite alone does not make a co-owner");
			command(a.createCommandSourceStack(),"plot expand");
			h.assertTrue(plots.plots().get(id).chunkCount()==1,"invite alone gives no quota");
			command(b.createCommandSourceStack(),"plot deny");
			h.assertTrue(command(b.createCommandSourceStack(),"plot accept")==0 && plots.plots().get(id).coOwners().isEmpty(),"denied invite is gone, accept finds nothing");
			command(a.createCommandSourceStack(),"plot coowner add "+id+" "+nb);command(b.createCommandSourceStack(),"plot accept");
			h.assertTrue(plots.plots().get(id).coOwners().contains(b.getUUID().toString()),"accept makes co-owner");
			snapChunk(b,cx,cz+8,pos);command(b.createCommandSourceStack(),"plot claim");
			h.assertTrue(plots.ownedBy(b.getUUID()).orElseThrow().getKey().equals(id),"co-owner cannot claim own plot");
			snapChunk(c,cx,cz+20,pos);command(c.createCommandSourceStack(),"plot claim");
			command(a.createCommandSourceStack(),"plot coowner add "+id+" "+nc);command(c.createCommandSourceStack(),"plot accept");
			h.assertTrue(!plots.plots().get(id).coOwners().contains(c.getUUID().toString()),"owner of another plot cannot become co-owner");
			command(a.createCommandSourceStack(),"plot expand");
			h.assertTrue(plots.plots().get(id).chunkCount()==2,"co-owner quota allows two chunks");
			snapChunk(a,cx+2,cz,pos);command(a.createCommandSourceStack(),"plot expand");
			h.assertTrue(plots.plots().get(id).chunkCount()==2,"third chunk rejected");
			command(admin,"plot bonus "+na+" 1");
			h.assertTrue(plots.bonus(a.getUUID())==1,"bonus stored");
			command(a.createCommandSourceStack(),"plot expand");
			h.assertTrue(plots.plots().get(id).chunkCount()==3,"bonus allows third chunk");
			command(b.createCommandSourceStack(),"plot coowner remove "+id+" "+na);
			command(a.createCommandSourceStack(),"plot coowner remove "+id+" "+nb);
			h.assertTrue(plots.plots().get(id).coOwners().contains(b.getUUID().toString()),"co-owner removal rejected while plot exceeds remaining quota");
			command(a.createCommandSourceStack(),"plot unclaim");
			h.assertTrue(plots.plots().containsKey(id),"unclaim needs confirmation");
			command(a.createCommandSourceStack(),"plot unclaim confirm");
			h.assertTrue(!plots.plots().containsKey(id),"confirmed unclaim removes the plot");
		} finally {
			config.city.claimsEnabled=enabled;config.city.claimChunksPerPlayer=per;
			for(var e:List.copyOf(plots.plots().entrySet())) if(e.getValue().isOwner(a.getUUID())||e.getValue().isOwner(c.getUUID())) plots.remove(e.getKey());
			plots.setBonus(a.getUUID(),0);
		}
		h.succeed();
	}
	@GameTest
	public void plotHelpHidesAdminCommands(GameTestHelper h) {
		ServerPlayer p=h.makeMockServerPlayerInLevel();
		h.assertTrue(command(p.createCommandSourceStack(),"plot help")==1 && command(p.createCommandSourceStack(),"plot hilfe")==1,"help runs for non-op");
		String user=String.join("\n",PlotCommand.helpLines(false).stream().map(x -> x.getString()).toList()),admin=String.join("\n",PlotCommand.helpLines(true).stream().map(x -> x.getString()).toList());
		h.assertTrue(user.contains("/plot claim") && user.contains("/plot accept") && user.contains("/plot coowner add"),"player commands listed");
		for(String bad:List.of("/plot create","/plot delete","/plot bonus","/plot enabled","/plot owner ","/plot role")) h.assertTrue(!user.contains(bad) && admin.contains(bad),"admin command only for admins: "+bad);
		h.succeed();
	}
	@GameTest
	public void plotStorageLoadsOldFormat(GameTestHelper h) {
		var plot=new net.minecraft.nbt.CompoundTag();
		plot.putString("dimension","minecraft:overworld");plot.putInt("minX",0);plot.putInt("minZ",0);plot.putInt("maxX",15);plot.putInt("maxZ",15);
		var legacy=new net.minecraft.nbt.CompoundTag();legacy.put("alt",plot);
		var loaded=PlotStorage.CODEC.parse(NbtOps.INSTANCE,legacy).getOrThrow();
		h.assertTrue(loaded.plots().containsKey("alt") && loaded.plots().get("alt").coOwners().isEmpty() && loaded.plots().get("alt").managers().isEmpty(),"old plots.dat without new fields loads");
		UUID u=UUID.randomUUID();loaded.setBonus(u,2);
		var again=PlotStorage.CODEC.parse(NbtOps.INSTANCE,PlotStorage.CODEC.encodeStart(NbtOps.INSTANCE,loaded).getOrThrow()).getOrThrow();
		h.assertTrue(again.bonus(u)==2 && again.plots().containsKey("alt"),"new format round trip keeps plots and bonus");h.succeed();
	}
	@GameTest
	public void plotCodecLoadsOldDataWithoutManagers(GameTestHelper h) {
		var tag=new net.minecraft.nbt.CompoundTag();
		tag.putString("dimension","minecraft:overworld");tag.putInt("minX",0);tag.putInt("minZ",0);tag.putInt("maxX",3);tag.putInt("maxZ",3);
		var plot=Plot.CODEC.parse(NbtOps.INSTANCE,tag).getOrThrow();
		h.assertTrue(plot.managers().isEmpty(),"old plot without managers loads with empty set");
		var again=Plot.CODEC.parse(NbtOps.INSTANCE,Plot.CODEC.encodeStart(NbtOps.INSTANCE,plot.withManagers(List.of(UUID.randomUUID().toString()))).getOrThrow()).getOrThrow();
		h.assertTrue(again.managers().size()==1,"managers survive codec round trip");h.succeed();
	}
	@GameTest
	public void plotBoundsOverlapAndCodec(GameTestHelper h) {
		PlotStorage plots=new PlotStorage(); UUID owner=UUID.randomUUID();Plot p=new Plot("minecraft:overworld",-17,-17,3,3,Optional.of(owner.toString()),List.of(),List.of(),true);
		h.assertTrue(plots.createProblem("one",p).isEmpty(),"valid rectangle accepted");plots.put("one",p);
		h.assertTrue(plots.at("minecraft:overworld",new BlockPos(-16,-60,-16)).isPresent() && plots.at("minecraft:overworld",new BlockPos(-16,300,-16)).isPresent(),"all heights and negative chunk boundaries covered");
		h.assertTrue(plots.createProblem("two",new Plot("minecraft:overworld",3,3,8,8,Optional.empty(),List.of(),List.of(),true)).isPresent(),"inclusive border overlap rejected");
		h.assertTrue(plots.createProblem("two",new Plot("minecraft:overworld",4,4,8,8,Optional.empty(),List.of(),List.of(),true)).isEmpty(),"adjacent rectangle accepted");
		var decoded=PlotStorage.CODEC.parse(NbtOps.INSTANCE,PlotStorage.CODEC.encodeStart(NbtOps.INSTANCE,plots).getOrThrow()).getOrThrow();
		h.assertTrue(decoded.at("minecraft:overworld",new BlockPos(0,90,0)).orElseThrow().getValue().owner().orElseThrow().equals(owner.toString()),"plot index rebuilt from saved-data codec");h.succeed();
	}
	@GameTest
	public void visitorsCannotOpenPlotStorage(GameTestHelper h) {
		ServerPlayer p=h.makeMockServerPlayerInLevel();var config=TownhallMod.CONFIG.get();boolean old=config.city.claimsEnabled;BlockPos pos=h.absolutePos(new BlockPos(3,1,3));
		String id="test_"+UUID.randomUUID().toString().substring(0,8);var plots=PlotStorage.get(p.level().getServer());
		h.setBlock(new BlockPos(3,1,3),Blocks.BARREL);
		try {
			plots.put(id,new Plot(p.level().dimension().identifier().toString(),pos.getX(),pos.getZ(),pos.getX(),pos.getZ(),Optional.empty(),List.of(),List.of(),true));config.city.claimsEnabled=true;
			h.assertTrue(!PlotService.canUse(p,p.level(),pos),"public doors/workstations flag never exposes storage containers");
			h.assertTrue(!((net.minecraft.world.level.block.entity.BaseContainerBlockEntity)p.level().getBlockEntity(pos)).canOpen(p),"actual container open path respects plot protection");
			config.city.claimsEnabled=false;
			h.assertTrue(((net.minecraft.world.level.block.entity.BaseContainerBlockEntity)p.level().getBlockEntity(pos)).canOpen(p),"container opens when optional plot protection is disabled");
		}finally { config.city.claimsEnabled=old;plots.remove(id); }
		h.succeed();
	}
	@GameTest
	public void elevatedSignSourceCannotManageRoles(GameTestHelper h) {
		ServerPlayer p=h.makeMockServerPlayerInLevel();var forged=p.level().getServer().createCommandSourceStack().withEntity(p);
		h.assertTrue(!CityAccess.isAdmin(forged),"elevated source does not grant normal player administration");
		h.assertTrue(!RoleService.hasPermission(forged,"police.jail"),"role capabilities use actor's own permissions");
		h.assertTrue(CityAccess.isAdmin(p.level().getServer().createCommandSourceStack()),"real console can administer city");h.succeed();
	}
	private static String sel(ServerPlayer p) {
		String name="n"+p.getStringUUID().replace("-","").substring(0,12);
		p.level().getServer().services().nameToIdCache().add(new net.minecraft.server.players.NameAndId(p.getUUID(),name));return name;
	}
	private static net.minecraft.commands.CommandSourceStack at(ServerPlayer p,BlockPos pos) { return p.createCommandSourceStack().withPosition(net.minecraft.world.phys.Vec3.atCenterOf(pos)); }
	private static int command(net.minecraft.commands.CommandSourceStack source, String text) {
		try { return source.getServer().getCommands().getDispatcher().execute(text,source); } catch (Exception e) { return -1; }
	}
	@GameTest
	public void offlineRoleCommandsAndWhitelistedPermissions(GameTestHelper h) {
		var server = h.getLevel().getServer();
		var source = server.createCommandSourceStack();
		String role = "job_" + UUID.randomUUID().toString().substring(0, 8);
		String name = "offline" + UUID.randomUUID().toString().substring(0, 8);
		UUID uuid = UUID.randomUUID();
		server.services().nameToIdCache().add(new net.minecraft.server.players.NameAndId(uuid, name));
		RoleStorage storage = RoleStorage.get(server);
		try {
			command(source, "role create " + role + " Stadtgärtner");
			command(source, "role permission " + role + " city.announce true");
			command(source, "role grant " + role + " " + name);
			h.assertTrue(storage.has(uuid, "city.announce"), "offline name resolved and right assigned via real command");
			command(source, "role permission " + role + " minecraft.command.op true");
			h.assertFalse(storage.has(uuid, "minecraft.command.op"), "arbitrary vanilla rights refused");
			command(source, "role revoke " + role + " " + name);
			h.assertFalse(storage.has(uuid, "city.announce"), "command revocation effective immediately");
		} finally { storage.remove(role); }
		h.succeed();
	}
	@GameTest
	public void policeCommandLimitsOnlineTimeAndRelease(GameTestHelper h) throws Exception {
		var server = h.getLevel().getServer();
		var config = TownhallMod.CONFIG.get();
		var officer = h.makeMockServerPlayerInLevel();
		var target = h.makeMockServerPlayerInLevel();
		String id = "jail_" + UUID.randomUUID().toString().substring(0, 8);
		String nickname = "inmate" + UUID.randomUUID().toString().substring(0, 8);
		String oldLocation = config.city.policeLocation;
		int oldLimit = config.city.policeMaxMinutes;
		boolean oldOnboarding = config.onboarding.enabled, oldSafe = config.safeTeleport.enabled;
		var jail = new TownhallConfig.Location();
		jail.command = id; jail.dimension = "minecraft:the_nether"; jail.escapable = false;
		jail.spawn = new TownhallConfig.Spot(); jail.spawn.x = 10.5; jail.spawn.y = 90; jail.spawn.z = 10.5;
		var states = dev.townhall.storage.ReturnPositionStorage.get(server);
		var roles = RoleStorage.get(server);
		try {
			config.locations.put(id, jail); config.city.policeLocation = id; config.city.policeMaxMinutes = 3;
			config.onboarding.enabled = false; config.safeTeleport.enabled = false;
			roles.grant(officer.getUUID(), officer.getGameProfile().name(), "polizei");
			dev.townhall.nick.Nicknames.get(server).set(server, target.getUUID(), target.getGameProfile().name(), nickname);
			var at = h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2, 2.5));
			target.absSnapTo(at.x, at.y, at.z, 45, 10);
			officer.absSnapTo(at.x + 20, at.y, at.z, 0, 0);
			var source = server.createCommandSourceStack().withEntity(officer).withLevel(target.level()).withPosition(target.position());
			command(source, "polizei jail " + nickname + " 4 Test");
			h.assertFalse(states.state(target.getUUID()).confined(), "police cannot exceed admin limit");
			command(source, "polizei jail " + nickname + " 2 Test");
			var jailed = states.state(target.getUUID());
			h.assertTrue(jailed.confined() && jailed.remainingMillis().orElseThrow() == 120000L, "role alone can send to designated prison with real-time duration");
			h.assertTrue(jailed.returnPosition().orElseThrow().x() == at.x, "original return saved");
			dev.townhall.teleport.ConfinementService.check(server, 1000);
			h.assertTrue(states.state(target.getUUID()).remainingMillis().orElseThrow() == 119000L, "online time ticks down in milliseconds");
			source = server.createCommandSourceStack().withEntity(officer).withPosition(target.position()).withLevel(target.level());
			command(source, "polizei jail " + nickname + " 1 Again");
			h.assertTrue(states.state(target.getUUID()).remainingMillis().orElseThrow() == 119000L, "cannot replace an existing sentence");
			command(source, "polizei release " + nickname);
			h.assertFalse(states.state(target.getUUID()).confined(), "role can release designated prisoner");
			h.assertTrue(target.level() == h.getLevel() && target.position().distanceToSqr(at) < 0.001, "release restores exact original position");
		} finally {
			config.locations.remove(id); config.city.policeLocation = oldLocation; config.city.policeMaxMinutes = oldLimit;
			config.onboarding.enabled = oldOnboarding; config.safeTeleport.enabled = oldSafe;
			roles.revoke(officer.getUUID(), "polizei"); states.remove(target.getUUID());
			dev.townhall.nick.Nicknames.get(server).reset(server, target.getUUID());
		}
		h.succeed();
	}
	@GameTest
	public void malformedPlotBoundsRejectedByCodec(GameTestHelper h) {
		var encoded = new net.minecraft.nbt.CompoundTag();
		encoded.putString("dimension", "minecraft:overworld");
		encoded.putInt("minX", Integer.MIN_VALUE); encoded.putInt("maxX", Integer.MAX_VALUE);
		encoded.putInt("minZ", 0); encoded.putInt("maxZ", 0);
		h.assertTrue(Plot.CODEC.parse(NbtOps.INSTANCE, encoded).error().isPresent(), "malformed plot returns codec error without unbounded indexing");
		h.succeed();
	}

	@GameTest
	public void plotPlacementAndAutomationPaths(GameTestHelper h) {
		var level = h.getLevel(); var server = level.getServer();
		var p = h.makeMockServerPlayerInLevel();
		var config = TownhallMod.CONFIG.get();
		boolean enabled = config.city.claimsEnabled, onboarding = config.onboarding.enabled;
		String id = "path_" + UUID.randomUUID().toString().substring(0, 8);
		BlockPos claimed = h.absolutePos(new BlockPos(3, 2, 3));
		var plots = PlotStorage.get(server);
		try {
			config.onboarding.enabled = false; config.city.claimsEnabled = true;
			plots.put(id, new Plot(level.dimension().identifier().toString(), claimed.getX(), claimed.getZ(), claimed.getX(), claimed.getZ(), Optional.empty(), List.of(), List.of(), true));
			level.setBlockAndUpdate(claimed.below(), Blocks.STONE.defaultBlockState());
			var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
			var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(claimed), net.minecraft.core.Direction.UP, claimed, false);
			var context = new net.minecraft.world.item.context.BlockPlaceContext(p, net.minecraft.world.InteractionHand.MAIN_HAND, item, hit);
			var stone = (net.minecraft.world.item.BlockItem)net.minecraft.world.item.Items.STONE;
			h.assertFalse(stone.place(context).consumesAction(), "actual item placement rejects foreign claim");
			h.assertTrue(level.getBlockState(claimed).isAir(), "denied placement changed no blocks");
			config.city.claimsEnabled = false;
			h.assertTrue(stone.place(context).consumesAction() && level.getBlockState(claimed).is(Blocks.STONE), "same placement succeeds with optional protection disabled");
			var piston = claimed.west();
			level.setBlockAndUpdate(piston, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, net.minecraft.core.Direction.EAST));
			config.city.claimsEnabled = true;
			h.assertFalse(new net.minecraft.world.level.block.piston.PistonStructureResolver(level, piston, net.minecraft.core.Direction.EAST, true).resolve(), "outside piston cannot move claimed block");
			config.city.claimsEnabled = false;
			h.assertTrue(new net.minecraft.world.level.block.piston.PistonStructureResolver(level, piston, net.minecraft.core.Direction.EAST, true).resolve(), "ordinary piston positive control");
			level.setBlockAndUpdate(claimed, Blocks.BARREL.defaultBlockState());
			BlockPos outside = claimed.east();
			level.setBlockAndUpdate(outside, Blocks.HOPPER.defaultBlockState());
			var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity)level.getBlockEntity(claimed);
			var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity)level.getBlockEntity(outside);
			config.city.claimsEnabled = true;
			var incoming = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
			h.assertTrue(net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(hopper, barrel, incoming, net.minecraft.core.Direction.EAST).getCount() == 2 && barrel.isEmpty(), "cross-boundary insertion retains items");
			h.assertFalse(barrel.stillValid(p), "already-open container is invalid after claim activation");
			config.city.claimsEnabled = false;
			h.assertTrue(net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(hopper, barrel, incoming, net.minecraft.core.Direction.EAST).isEmpty(), "ordinary insertion positive control");
		} finally {
			config.city.claimsEnabled = enabled; config.onboarding.enabled = onboarding; plots.remove(id);
		}
		h.succeed();
	}

	@GameTest
	public void bedCannotPlaceSecondHalfInForeignPlot(GameTestHelper h) {
		var level = h.getLevel(); var config = TownhallMod.CONFIG.get(); var player = h.makeMockServerPlayerInLevel();
		boolean enabled = config.city.claimsEnabled, onboarding = config.onboarding.enabled;
		String id = "bed_" + UUID.randomUUID().toString().substring(0, 8);
		BlockPos foot = h.absolutePos(new BlockPos(3, 2, 3)), head = foot.east();
		var plots = PlotStorage.get(level.getServer());
		try {
			config.onboarding.enabled = false; config.city.claimsEnabled = true;
			plots.put(id, new Plot(level.dimension().identifier().toString(), head.getX(), head.getZ(), head.getX(), head.getZ(), Optional.empty(), List.of(), List.of(), true));
			level.setBlockAndUpdate(foot.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(head.below(), Blocks.STONE.defaultBlockState());
			player.setYRot(-90); player.setYHeadRot(-90);
			var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BED.red());
			var context = new net.minecraft.world.item.context.BlockPlaceContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, item,
					new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(foot), net.minecraft.core.Direction.UP, foot, false));
			var bed = (net.minecraft.world.item.BlockItem)net.minecraft.world.item.Items.BED.red();
			h.assertFalse(bed.place(context).consumesAction(), "bed foot outside does not permit head placement inside protected column");
			h.assertTrue(level.getBlockState(foot).isAir() && level.getBlockState(head).isAir(), "failed placement leaves both halves empty");
			config.city.claimsEnabled = false;
			h.assertTrue(bed.place(context).consumesAction() && level.getBlockState(foot).is(Blocks.BED.red()) && level.getBlockState(head).is(Blocks.BED.red()), "ordinary two-block bed placement succeeds");
		} finally {
			config.city.claimsEnabled = enabled; config.onboarding.enabled = onboarding; plots.remove(id);
		}
		h.succeed();
	}

}
