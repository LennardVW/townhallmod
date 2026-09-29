package dev.townhall.protection;

import dev.townhall.TownhallMod;
import dev.townhall.dimension.DimensionSettings;
import dev.townhall.onboarding.Onboarding;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Per-world rules (pvp, build, fall damage) and the "accept the rules first" restrictions, using Fabric events.
 * Block placement and hunger are handled in mixins (BlockItemMixin, PlayerMixin) because there is no event for them.
 */
public final class Protection {

	private Protection() {}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Protection::allowDamage);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !(player instanceof ServerPlayer sp) || mayChangeWorld(sp));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (Onboarding.isRestricted(sp)) return deniedOnboarding(sp);
			return changesWorld(player.getItemInHand(hand)) && !mayBuild(sp) ? denied(sp) : InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (Onboarding.isRestricted(sp)) return deniedOnboarding(sp);
			ItemStack stack = player.getItemInHand(hand);
			return (stack.getItem() instanceof BucketItem || stack.getItem() instanceof BoatItem) && !mayBuild(sp) ? denied(sp) : InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (Onboarding.isRestricted(sp)) return deniedOnboarding(sp);
			return isWorldObject(entity) && !mayBuild(sp) ? denied(sp) : InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			if (Onboarding.isRestricted(sp)) return deniedOnboarding(sp);
			return (entity instanceof ItemFrame || entity instanceof ArmorStand) && !mayBuild(sp) ? denied(sp) : InteractionResult.PASS;
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (!Onboarding.isRestricted(sender)) return true;
			Onboarding.remind(sender);
			return false;
		});
	}

	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer victim)) return true;
		if (Onboarding.isRestricted(victim)) return false;
		DimensionSettings.Rules rules = DimensionSettings.of(victim.level().dimension());
		if (!rules.fallDamage() && source.is(DamageTypeTags.IS_FALL)) return false;
		if (!rules.pvp() && source.getEntity() instanceof Player attacker && attacker != victim) {
			if (attacker instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal("PvP is off in this world.").withStyle(ChatFormatting.RED));
			return false;
		}
		return true;
	}

	/** Operators may always build; everyone else only where "build" isn't false. */
	public static boolean mayBuild(ServerPlayer player) {
		DimensionSettings.Rules rules = DimensionSettings.of(player.level().dimension());
		return rules.build() || rules.builders().contains(player.getUUID()) || TownhallMod.isOperator(player.permissions());
	}

	/** Set on players who switched to creative with /builder creative. */
	public static final String BUILDER_CREATIVE_TAG = "townhall.builder_creative";

	public static boolean isBuilderHere(ServerPlayer player) {
		return DimensionSettings.of(player.level().dimension()).builders().contains(player.getUUID());
	}

	/**
	 * Builders (non-operators) may only use creative inside a world where they are builders. Anywhere else, or after
	 * losing the right, they go back to survival. Checked on join, world change and respawn. Players who are builders
	 * somewhere count even without the tag, so a lost tag can't leave them in creative.
	 * Also resends builders' command list, since their WorldEdit commands depend on the world.
	 */
	public static void enforceBuilderMode(ServerPlayer player) {
		// WorldEdit commands depend on the world (BuilderPermissions), so builders get a fresh command list here.
		if (DimensionSettings.isBuilderAnywhere(player.getUUID())) player.level().getServer().getCommands().sendCommands(player);
		boolean usedBuilderCreative = player.entityTags().contains(BUILDER_CREATIVE_TAG) || DimensionSettings.isBuilderAnywhere(player.getUUID());
		if (!usedBuilderCreative || TownhallMod.isOperator(player.permissions()) || isBuilderHere(player)) return;
		player.removeTag(BUILDER_CREATIVE_TAG);
		if (player.gameMode() == GameType.CREATIVE) {
			player.setGameMode(GameType.SURVIVAL);
			player.sendSystemMessage(Component.literal("Back to survival: creative only works where you are a builder.").withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Break/place check: build rule plus the rules-not-accepted restriction. Sends the reason. */
	public static boolean mayChangeWorld(ServerPlayer player) {
		if (Onboarding.isRestricted(player)) {
			Onboarding.remind(player);
			return false;
		}
		if (mayBuild(player)) return true;
		denied(player);
		return false;
	}

	/** Items that change blocks or place things when used on a block. */
	private static boolean changesWorld(ItemStack stack) {
		var item = stack.getItem();
		return item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem
				|| item instanceof BoneMealItem || item instanceof SpawnEggItem || item instanceof HangingEntityItem
				|| item instanceof ArmorStandItem || item instanceof EndCrystalItem || item instanceof MinecartItem
				|| item instanceof BoatItem || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES);
	}

	/** Paintings, item frames, armor stands, boats, minecarts, end crystals: things you'd break by hitting them. */
	private static boolean isWorldObject(Entity entity) {
		return !(entity instanceof LivingEntity) || entity instanceof ArmorStand;
	}

	private static InteractionResult denied(ServerPlayer player) {
		player.sendOverlayMessage(Component.literal("You can't build here.").withStyle(ChatFormatting.RED));
		return InteractionResult.FAIL;
	}

	private static InteractionResult deniedOnboarding(ServerPlayer player) {
		Onboarding.remind(player);
		return InteractionResult.FAIL;
	}
}
