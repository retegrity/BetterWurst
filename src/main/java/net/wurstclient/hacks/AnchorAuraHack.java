/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.function.Predicate;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.AnchorAuraFilterList;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FaceAimPoint;
import net.wurstclient.util.PlaceRotationGuard;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"anchor aura", "CrystalAura", "crystal aura"})
public final class AnchorAuraHack extends Hack implements UpdateListener
{
	private final SliderSetting range =
		new SliderSetting("Range", "description.wurst.setting.anchoraura.range",
			4.5, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoPlace =
		new CheckboxSetting("Auto-place anchors",
			"description.wurst.setting.anchoraura.auto-place_anchors", true);
	
	private final CheckboxSetting glowstoneShield = new CheckboxSetting(
		"Glowstone shield",
		"Before detonating an anchor, places a glowstone block next to it on"
			+ " your side, so that the explosion hurts you less than the"
			+ " target.\n\nNeeds at least 2 glowstone (one to charge the"
			+ " anchor, one for the shield).",
		true);
	
	private final SliderSetting minSelfDistance = new SliderSetting(
		"Min self-distance",
		"Won't detonate anchors that are closer to you than this.", 0, 0, 6,
		0.5, ValueDisplay.DECIMAL.withSuffix(" blocks").withLabel(0, "off"));
	
	private final SliderSetting actionDelay = new SliderSetting("Action delay",
		"How many ticks to wait after placing, charging or detonating an"
			+ " anchor before doing the next step.",
		2, 0, 10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"How fast your server-side rotation turns towards the blocks that"
			+ " AnchorAura clicks.",
		60, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final SliderSetting returnSpeed = new SliderSetting("Return speed",
		"How fast your server-side rotation turns back to your real rotation"
			+ " once AnchorAura is done.",
		30, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final CheckboxSetting movementCorrection =
		new CheckboxSetting("Movement correction",
			"Moves you in the direction of your server-side rotation while"
				+ " AnchorAura is aiming, the way the server expects it."
				+ " Needed for anti-cheats like Grim.",
			true);
	
	private final CheckboxSetting switchBack = new CheckboxSetting(
		"Switch back",
		"Switches back to the item you were holding once AnchorAura is done.",
		true);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private final EntityFilterList entityFilters =
		AnchorAuraFilterList.create();
	
	// how close an anchor has to be to the target to be used against it
	private static final double MAX_ANCHOR_DISTANCE = 4;
	
	// an anchor that was just detonated is ignored for this long, since the
	// client only finds out that it's gone once the server says so
	private static final int DETONATED_COOLDOWN = 10;
	
	private LivingEntity target;
	private BlockPos anchorPos;
	private BlockPos detonatedPos;
	private int detonatedCooldown;
	private int delay;
	private int originalSlot = -1;
	private boolean busy;
	private final FaceAimPoint aimPoint = new FaceAimPoint();
	private final PlaceRotationGuard rotationGuard = new PlaceRotationGuard();
	
	public AnchorAuraHack()
	{
		super("AnchorAura");
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(autoPlace);
		addSetting(glowstoneShield);
		addSetting(minSelfDistance);
		addSetting(actionDelay);
		addSetting(turnSpeed);
		addSetting(returnSpeed);
		addSetting(movementCorrection);
		addSetting(switchBack);
		addSetting(swingHand);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		target = null;
		anchorPos = null;
		detonatedPos = null;
		detonatedCooldown = 0;
		delay = 0;
		originalSlot = -1;
		busy = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		stop();
	}
	
	/**
	 * Returns true while AnchorAura is aiming at something, so that other
	 * hacks don't fight over the rotation.
	 */
	public boolean isBusy()
	{
		return isEnabled() && busy;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level.dimensionType().attributes()
			.applyModifier(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, false))
		{
			ChatUtils.error("Respawn anchors don't explode in this dimension.");
			setEnabled(false);
			return;
		}
		
		if(detonatedCooldown > 0 && --detonatedCooldown == 0)
			detonatedPos = null;
		
		LocalPlayer player = MC.player;
		if(player.isShiftKeyDown() || WURST.getHax().sneakHack.isEnabled())
		{
			stop();
			return;
		}
		
		updateTarget();
		if(target == null)
		{
			stop();
			return;
		}
		
		updateAnchorPos();
		if(anchorPos == null)
		{
			stop();
			return;
		}
		
		Action action = getNextAction();
		if(action == null)
		{
			stop();
			return;
		}
		
		busy = true;
		
		// keep aiming during the delay, but don't click
		WURST.getRotationFaker().faceVectorSmoothly(
			aimPoint.get(action.clickPos(), action.side(), action.hitVec()),
			turnSpeed.getValueF(), 2, movementCorrection.isChecked(),
			returnSpeed.getValueF());
		rotationGuard.onTurn(WURST.getRotationFaker().getLastTurnYawDelta(),
			WURST.getRotationFaker().getLastTurnPitchDelta());
		
		if(delay > 0)
		{
			delay--;
			return;
		}
		
		// a real player couldn't click while moving a totem
		if(WURST.getHax().autoTotemHack.isBusy())
			return;
			
		// only click once the rotation that will be sent this tick actually
		// looks at the right face, so the click matches what the server sees
		BlockHitResult hit = raycast(WURST.getRotationFaker().getServerYaw(),
			WURST.getRotationFaker().getServerPitch());
		if(hit.getType() != HitResult.Type.BLOCK
			|| !hit.getBlockPos().equals(action.clickPos())
			|| hit.getDirection() != action.side())
			return;
			
		// Grim's DuplicateRotPlace flags placing twice after the same yaw
		// change, so aim at a different spot and try again next tick
		if(!rotationGuard.canPlace())
		{
			aimPoint.next();
			return;
		}
		
		selectSlot(action.slot());
		if(!click(hit))
			return;
		
		rotationGuard.onPlaced();
		
		if(action.type() == ActionType.DETONATE)
		{
			detonatedPos = anchorPos;
			detonatedCooldown = DETONATED_COOLDOWN;
			anchorPos = null;
		}
		
		aimPoint.next();
		delay = actionDelay.getValueI();
	}
	
	private void stop()
	{
		target = null;
		anchorPos = null;
		busy = false;
		
		// the rotation turns back to the camera by itself once it expires
		if(switchBack.isChecked() && originalSlot != -1 && MC.player != null)
			MC.player.getInventory().setSelectedSlot(originalSlot);
		
		originalSlot = -1;
	}
	
	private void updateTarget()
	{
		double rangeSq = range.getValueSq();
		
		if(target != null && (!target.isAlive() || target.isRemoved()
			|| EntityUtils.distanceToHitboxSq(target) > rangeSq))
			target = null;
		
		if(target != null)
			return;
		
		Stream<LivingEntity> stream =
			EntityUtils.getExplosionWorthyAttackableEntities()
				.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq);
		
		target = entityFilters.applyTo(stream)
			.min(Comparator.comparingDouble(EntityUtils::distanceToHitboxSq))
			.orElse(null);
	}
	
	private void updateAnchorPos()
	{
		// keep working on the current anchor as long as it makes sense
		if(anchorPos != null)
		{
			BlockState state = BlockUtils.getState(anchorPos);
			boolean isAnchor = state.is(Blocks.RESPAWN_ANCHOR);
			if(!isAnchor && !state.canBeReplaced()
				|| !isInRange(Vec3.atCenterOf(anchorPos))
				|| distanceToTarget(anchorPos) > MAX_ANCHOR_DISTANCE)
				anchorPos = null;
		}
		
		if(anchorPos != null)
			return;
		
		// existing anchors near the target, charged ones first
		BlockPos center = target.blockPosition();
		int r = (int)Math.ceil(MAX_ANCHOR_DISTANCE);
		anchorPos = BlockUtils.getAllInBoxStream(center, r)
			.filter(pos -> !pos.equals(detonatedPos))
			.filter(pos -> BlockUtils.getState(pos).is(Blocks.RESPAWN_ANCHOR))
			.filter(pos -> distanceToTarget(pos) <= MAX_ANCHOR_DISTANCE)
			.filter(pos -> isInRange(Vec3.atCenterOf(pos)))
			.min(Comparator
				.<BlockPos> comparingInt(pos -> getCharge(pos) > 0 ? 0 : 1)
				.thenComparingDouble(this::distanceToTarget))
			.orElse(null);
		
		if(anchorPos != null || !autoPlace.isChecked()
			|| findHotbarSlot(s -> s.is(Items.RESPAWN_ANCHOR)) == -1)
			return;
		
		// a free spot next to the target to place a new anchor
		AABB targetBox = target.getBoundingBox();
		AABB playerBox = MC.player.getBoundingBox();
		Vec3 targetEyes = target.getEyePosition();
		anchorPos = BlockUtils.getAllInBoxStream(center, 2)
			.filter(pos -> BlockUtils.getState(pos).canBeReplaced())
			.filter(pos -> !new AABB(pos).intersects(targetBox)
				&& !new AABB(pos).intersects(playerBox))
			.filter(pos -> isInRange(Vec3.atCenterOf(pos)))
			.filter(pos -> getPlacement(pos, b -> true) != null)
			.min(Comparator.comparingDouble(
				pos -> targetEyes.distanceToSqr(Vec3.atCenterOf(pos))))
			.orElse(null);
	}
	
	private Action getNextAction()
	{
		BlockState state = BlockUtils.getState(anchorPos);
		
		// PLACE
		if(!state.is(Blocks.RESPAWN_ANCHOR))
		{
			int slot = findHotbarSlot(s -> s.is(Items.RESPAWN_ANCHOR));
			if(slot == -1)
				return null;
			
			Placement p = getPlacement(anchorPos, b -> true);
			return p == null ? null : new Action(ActionType.PLACE, p.clickPos(),
				p.side(), p.hitVec(), slot);
		}
		
		int glowstoneSlot = findHotbarSlot(s -> s.is(Items.GLOWSTONE));
		
		// CHARGE
		if(getCharge(anchorPos) == 0)
		{
			if(glowstoneSlot == -1)
				return null;
			
			return clickAnchor(ActionType.CHARGE, glowstoneSlot);
		}
		
		// SHIELD
		if(glowstoneShield.isChecked() && glowstoneSlot != -1)
		{
			Action shield = getShieldAction(glowstoneSlot);
			if(shield != null)
				return shield;
		}
		
		// DETONATE
		Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
		if(MC.player.position().distanceTo(anchorCenter) < minSelfDistance
			.getValue())
			return null;
		
		int slot = findDetonateSlot();
		return slot == -1 ? null : clickAnchor(ActionType.DETONATE, slot);
	}
	
	/**
	 * Places a glowstone block next to the anchor, between it and you.
	 * Returns null if that spot is already blocked or can't be used.
	 */
	private Action getShieldAction(int glowstoneSlot)
	{
		Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
		Vec3 toPlayer = RotationUtils.getEyesPos().subtract(anchorCenter);
		Direction dir = Direction.getApproximateNearest(toPlayer);
		BlockPos shieldPos = anchorPos.relative(dir);
		
		BlockState state = BlockUtils.getState(shieldPos);
		if(!state.canBeReplaced())
			return null;
		
		AABB shieldBox = new AABB(shieldPos);
		if(shieldBox.intersects(MC.player.getBoundingBox())
			|| shieldBox.intersects(target.getBoundingBox()))
			return null;
			
		// clicking the anchor with glowstone would charge it instead of
		// placing the glowstone, so place it against something else
		Placement p = getPlacement(shieldPos,
			b -> !BlockUtils.getState(b).is(Blocks.RESPAWN_ANCHOR));
		if(p == null)
			return null;
		
		return new Action(ActionType.SHIELD, p.clickPos(), p.side(), p.hitVec(),
			glowstoneSlot);
	}
	
	/**
	 * Right-clicks one of the anchor's faces that you can see.
	 */
	private Action clickAnchor(ActionType type, int slot)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 center = Vec3.atCenterOf(anchorPos);
		Action best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		for(Direction side : Direction.values())
		{
			// that face has to be uncovered (e.g. not behind the shield)
			if(!BlockUtils.getState(anchorPos.relative(side)).canBeReplaced())
				continue;
			
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 hitVec = center.add(normal.scale(0.5));
			if(eyes.subtract(hitVec).dot(normal) <= 0)
				continue;
			
			double distSq = eyes.distanceToSqr(hitVec);
			if(!isInRange(hitVec) || distSq >= bestDistSq)
				continue;
			
			best = new Action(type, anchorPos, side, hitVec, slot);
			bestDistSq = distSq;
		}
		
		return best;
	}
	
	/**
	 * Returns how to place a block at the given position against one of its
	 * neighbors that you can see, or null if there is none.
	 */
	private Placement getPlacement(BlockPos pos, Predicate<BlockPos> filter)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Placement best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		for(Direction dir : Direction.values())
		{
			BlockPos neighbor = pos.relative(dir);
			BlockState state = BlockUtils.getState(neighbor);
			if(!filter.test(neighbor) || !BlockUtils.canBeClicked(neighbor)
				|| state.canBeReplaced() || BlockUtils.isInteractive(state))
				continue;
			
			// the face of the neighbor that touches pos
			Direction side = dir.getOpposite();
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 hitVec = Vec3.atCenterOf(neighbor).add(normal.scale(0.5));
			if(eyes.subtract(hitVec).dot(normal) <= 0)
				continue;
			
			double distSq = eyes.distanceToSqr(hitVec);
			if(!isInRange(hitVec) || distSq >= bestDistSq)
				continue;
			
			best = new Placement(neighbor, side, hitVec);
			bestDistSq = distSq;
		}
		
		return best;
	}
	
	private BlockHitResult raycast(float yaw, float pitch)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 look = new Rotation(yaw, pitch).toLookVec();
		return BlockUtils.raycast(eyes, eyes.add(look.scale(range.getValue())));
	}
	
	private boolean click(BlockHitResult hit)
	{
		// same as vanilla's startUseItem(), without the extra useItem()
		InteractionResult result =
			MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		if(!(result instanceof InteractionResult.Success success))
			return false;
		
		if(success.swingSource() == InteractionResult.SwingSource.CLIENT)
			swingHand.swing(InteractionHand.MAIN_HAND);
		
		return true;
	}
	
	private void selectSlot(int slot)
	{
		int selected = MC.player.getInventory().getSelectedSlot();
		if(selected == slot)
			return;
		
		if(originalSlot == -1)
			originalSlot = selected;
		
		// vanilla sends the slot to the server in useItemOn()
		MC.player.getInventory().setSelectedSlot(slot);
	}
	
	private int findHotbarSlot(Predicate<ItemStack> predicate)
	{
		int selected = MC.player.getInventory().getSelectedSlot();
		if(predicate.test(MC.player.getInventory().getItem(selected)))
			return selected;
		
		for(int i = 0; i < 9; i++)
			if(predicate.test(MC.player.getInventory().getItem(i)))
				return i;
			
		return -1;
	}
	
	/**
	 * Anything but glowstone detonates a charged anchor. Prefers the item you
	 * were holding before AnchorAura started.
	 */
	private int findDetonateSlot()
	{
		if(originalSlot != -1 && !MC.player.getInventory().getItem(originalSlot)
			.is(Items.GLOWSTONE))
			return originalSlot;
		
		return findHotbarSlot(s -> !s.is(Items.GLOWSTONE));
	}
	
	private boolean isInRange(Vec3 vec)
	{
		return RotationUtils.getEyesPos().distanceToSqr(vec) <= range
			.getValueSq();
	}
	
	private double distanceToTarget(BlockPos pos)
	{
		return target.getBoundingBox().getCenter()
			.distanceTo(Vec3.atCenterOf(pos));
	}
	
	private int getCharge(BlockPos pos)
	{
		return BlockUtils.getState(pos)
			.getOptionalValue(RespawnAnchorBlock.CHARGE).orElse(0);
	}
	
	private enum ActionType
	{
		PLACE,
		CHARGE,
		SHIELD,
		DETONATE;
	}
	
	private record Action(ActionType type, BlockPos clickPos, Direction side,
		Vec3 hitVec, int slot)
	{}
	
	private record Placement(BlockPos clickPos, Direction side, Vec3 hitVec)
	{}
}
