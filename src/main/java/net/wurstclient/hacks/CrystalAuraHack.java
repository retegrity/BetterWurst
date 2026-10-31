/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import net.wurstclient.settings.filterlists.CrystalAuraFilterList;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.AttackUtils;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FaceAimPoint;
import net.wurstclient.util.PlaceRotationGuard;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"crystal aura"})
public final class CrystalAuraHack extends Hack implements UpdateListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Determines how far CrystalAura will reach to place and detonate"
			+ " crystals.\n\nSurvival players can only reach 4.5 blocks,"
			+ " anything above that can get you flagged.",
		4.5, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoPlace = new CheckboxSetting(
		"Auto-place crystals",
		"When enabled, CrystalAura will automatically place crystals near"
			+ " valid entities.\n"
			+ "When disabled, CrystalAura will only detonate manually placed"
			+ " crystals.",
		true);
	
	private final SliderSetting minDamage = new SliderSetting("Min damage",
		"Only places and detonates crystals that would deal at least this"
			+ " much damage to the target (without armor).",
		3, 0, 20, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	private final SliderSetting maxSelfDamage = new SliderSetting(
		"Max self damage",
		"Won't place or detonate crystals that would deal more than this much"
			+ " damage to you (without armor).",
		4, 0, 20, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	private final CheckboxSetting hitCrystal =
		new CheckboxSetting("Hit crystal",
			"When the target is standing too low for a crystal to hurt them,"
				+ " hits them first so they fly up, then detonates the crystal"
				+ " while they are in the air.",
			true);
	
	private final SliderSetting actionDelay = new SliderSetting("Action delay",
		"How many ticks to wait after placing or detonating a crystal.", 1, 0,
		10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"How fast your server-side rotation turns towards the crystals.", 90,
		10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final SliderSetting returnSpeed = new SliderSetting("Return speed",
		"How fast your server-side rotation turns back to your real rotation"
			+ " once CrystalAura is done.",
		30, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final CheckboxSetting movementCorrection =
		new CheckboxSetting("Movement correction",
			"Moves you in the direction of your server-side rotation while"
				+ " CrystalAura is aiming, the way the server expects it."
				+ " Needed for anti-cheats like Grim.",
			true);
	
	private final CheckboxSetting switchBack = new CheckboxSetting(
		"Switch back",
		"Switches back to the item you were holding once CrystalAura is done.",
		true);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private final EntityFilterList entityFilters =
		CrystalAuraFilterList.create();
	
	private static final float EXPLOSION_RADIUS = 6;
	
	// how far crystal bases can be from the target
	private static final int BASE_SEARCH_RADIUS = 3;
	
	// roughly how high a hit knocks the target
	private static final double HIT_KNOCKUP_HEIGHT = 1;
	
	// the client only finds out that a crystal was placed or destroyed once
	// the server says so, so don't do the same thing again right away
	private static final int RETRY_COOLDOWN = 5;
	
	private final FaceAimPoint aimPoint = new FaceAimPoint();
	private final PlaceRotationGuard rotationGuard = new PlaceRotationGuard();
	
	private LivingEntity target;
	private int delay;
	private int originalSlot = -1;
	private BlockPos lastPlacedBase;
	private int lastPlacedCooldown;
	private int lastBrokenId = -1;
	private int lastBrokenCooldown;
	private boolean waitingForLanding;
	
	public CrystalAuraHack()
	{
		super("CrystalAura");
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(autoPlace);
		addSetting(minDamage);
		addSetting(maxSelfDamage);
		addSetting(hitCrystal);
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
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		target = null;
		delay = 0;
		originalSlot = -1;
		lastPlacedBase = null;
		lastBrokenId = -1;
		waitingForLanding = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		stop();
	}
	
	@Override
	public void onUpdate()
	{
		if(lastPlacedCooldown > 0 && --lastPlacedCooldown == 0)
			lastPlacedBase = null;
		if(lastBrokenCooldown > 0 && --lastBrokenCooldown == 0)
			lastBrokenId = -1;
		
		updateTarget();
		if(target == null)
		{
			stop();
			return;
		}
		
		if(waitingForLanding && target.onGround())
			waitingForLanding = false;
		
		Action action = getNextAction();
		if(action == null)
		{
			stop();
			return;
		}
		
		WURST.getRotationFaker().faceVectorSmoothly(action.aim(),
			turnSpeed.getValueF(), 2, movementCorrection.isChecked(),
			returnSpeed.getValueF());
		rotationGuard.onTurn(WURST.getRotationFaker().getLastTurnYawDelta(),
			WURST.getRotationFaker().getLastTurnPitchDelta());
		
		if(delay > 0)
		{
			delay--;
			return;
		}
		
		// a real player couldn't do this while moving a totem or blocking
		if(AttackUtils.shouldDelayAttack())
			return;
		
		switch(action.type())
		{
			case PLACE -> place(action);
			case BREAK -> breakCrystal(action.entity());
			case HIT -> hitTarget(action.entity());
		}
	}
	
	private void stop()
	{
		target = null;
		waitingForLanding = false;
		
		// the rotation turns back to the camera by itself once it expires
		if(switchBack.isChecked() && originalSlot != -1 && MC.player != null)
			MC.player.getInventory().setSelectedSlot(originalSlot);
		
		originalSlot = -1;
	}
	
	private void updateTarget()
	{
		double rangeSq = Mth.square(range.getValue() + BASE_SEARCH_RADIUS);
		
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
	
	private Action getNextAction()
	{
		AABB targetBox = target.getBoundingBox();
		
		// 1. detonate the best crystal that is already there
		Optional<EndCrystal> crystal = getBestCrystal(targetBox);
		if(crystal.isPresent())
			return breakAction(crystal.get());
		
		// 2. place a crystal where it would hurt the target right now
		boolean canPlace =
			autoPlace.isChecked() && findHotbarSlot(Items.END_CRYSTAL) != -1;
		if(canPlace)
		{
			Action place = getPlaceAction(targetBox);
			if(place != null)
				return place;
		}
		
		// 3. hit crystal: the target is too low, so knock them up first
		if(!hitCrystal.isChecked() || !target.onGround() || waitingForLanding)
			return null;
		
		AABB raisedBox = targetBox.move(0, HIT_KNOCKUP_HEIGHT, 0);
		
		// crystal ready that would hurt them once they're up -> hit them
		if(getBestCrystal(raisedBox).isPresent())
			return hitAction();
		
		// otherwise place one for that first
		return canPlace ? getPlaceAction(raisedBox) : null;
	}
	
	private Optional<EndCrystal> getBestCrystal(AABB targetBox)
	{
		double rangeSq = range.getValueSq();
		Vec3 eyes = RotationUtils.getEyesPos();
		
		return EntityUtils.getAliveEntities(EndCrystal.class)
			.filter(e -> e.getId() != lastBrokenId)
			.filter(e -> e.getBoundingBox().distanceToSqr(eyes) <= rangeSq)
			.filter(e -> isWorthIt(e.position(), targetBox))
			.max(Comparator.comparingDouble(
				e -> getDamage(e.position(), targetBox, target)))
			.map(EndCrystal.class::cast);
	}
	
	private Action getPlaceAction(AABB targetBox)
	{
		BlockPos center = BlockPos.containing(targetBox.getCenter().x,
			targetBox.minY, targetBox.getCenter().z);
		
		return BlockUtils.getAllInBoxStream(center, BASE_SEARCH_RADIUS)
			.filter(this::canPlaceOn)
			.filter(base -> !base.equals(lastPlacedBase))
			.filter(base -> isWorthIt(getCrystalPos(base), targetBox))
			.sorted(Comparator
				.<BlockPos> comparingDouble(
					base -> getDamage(getCrystalPos(base), targetBox, target))
				.reversed())
			.map(this::placeAction).filter(a -> a != null).findFirst()
			.orElse(null);
	}
	
	/**
	 * Whether a crystal at that position would hurt the target enough and
	 * you little enough.
	 */
	private boolean isWorthIt(Vec3 crystalPos, AABB targetBox)
	{
		float targetDamage = getDamage(crystalPos, targetBox, target);
		if(targetDamage < minDamage.getValueF() * 2)
			return false;
		
		LocalPlayer player = MC.player;
		float selfDamage =
			getDamage(crystalPos, player.getBoundingBox(), player);
		return selfDamage <= maxSelfDamage.getValueF() * 2
			&& selfDamage < player.getHealth() + player.getAbsorptionAmount();
	}
	
	private boolean canPlaceOn(BlockPos base)
	{
		Block block = BlockUtils.getBlock(base);
		if(block != Blocks.OBSIDIAN && block != Blocks.BEDROCK)
			return false;
		
		BlockPos above = base.above();
		if(!BlockUtils.getState(above).isAir())
			return false;
		
		// the crystal needs this space to be free of entities
		AABB crystalSpace = new AABB(above).expandTowards(0, 1, 0);
		return MC.level.getEntities(null, crystalSpace).isEmpty();
	}
	
	private Vec3 getCrystalPos(BlockPos base)
	{
		return Vec3.atBottomCenterOf(base.above());
	}
	
	private Action placeAction(BlockPos base)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Direction best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		// the top face if you can see it (then the same aim also hits the
		// crystal afterwards), otherwise the closest visible side
		for(Direction side : Direction.values())
		{
			if(side == Direction.DOWN)
				continue;
			
			if(side != Direction.UP
				&& !BlockUtils.getState(base.relative(side)).canBeReplaced())
				continue;
			
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 faceCenter = Vec3.atCenterOf(base).add(normal.scale(0.5));
			if(eyes.subtract(faceCenter).dot(normal) <= 0)
				continue;
			
			double distSq = eyes.distanceToSqr(faceCenter);
			if(distSq > range.getValueSq())
				continue;
			
			if(side == Direction.UP)
			{
				best = side;
				break;
			}
			
			if(distSq < bestDistSq)
			{
				best = side;
				bestDistSq = distSq;
			}
		}
		
		if(best == null)
			return null;
		
		Vec3 faceCenter = Vec3.atCenterOf(base)
			.add(Vec3.atLowerCornerOf(best.getUnitVec3i()).scale(0.5));
		return new Action(ActionType.PLACE, base, best,
			aimPoint.get(base, best, faceCenter), null);
	}
	
	private Action breakAction(EndCrystal crystal)
	{
		BlockPos base = crystal.blockPosition().below();
		Vec3 eyes = RotationUtils.getEyesPos();
		
		// looking at the top of the block below the crystal also looks at
		// the crystal, so the aim doesn't have to flick between them
		Vec3 aim;
		if(eyes.y > base.getY() + 1)
			aim = aimPoint.get(base, Direction.UP,
				Vec3.atCenterOf(base).add(0, 0.5, 0));
		else
			aim = getClosestPoint(crystal.getBoundingBox(), eyes);
		
		return new Action(ActionType.BREAK, base, Direction.UP, aim, crystal);
	}
	
	private Action hitAction()
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		return new Action(ActionType.HIT, null, null,
			getClosestPoint(target.getBoundingBox(), eyes), target);
	}
	
	private void place(Action action)
	{
		// only click once the rotation that will be sent this tick actually
		// looks at the right face
		BlockHitResult hit = raycastBlocks(range.getValue());
		if(hit.getType() != HitResult.Type.BLOCK
			|| !hit.getBlockPos().equals(action.base())
			|| hit.getDirection() != action.side())
			return;
		
		if(!rotationGuard.canPlace())
		{
			aimPoint.next();
			return;
		}
		
		selectSlot(findHotbarSlot(Items.END_CRYSTAL));
		InteractionResult result =
			MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		if(!(result instanceof InteractionResult.Success success))
			return;
		
		if(success.swingSource() == InteractionResult.SwingSource.CLIENT)
			swingHand.swing(InteractionHand.MAIN_HAND);
		
		rotationGuard.onPlaced();
		aimPoint.next();
		lastPlacedBase = action.base();
		lastPlacedCooldown = RETRY_COOLDOWN;
		delay = actionDelay.getValueI();
	}
	
	private void hitTarget(Entity entity)
	{
		// full cooldown, so the hit has its full knockback
		if(MC.player.getAttackStrengthScale(0.5F) < 0.9F)
			return;
		
		if(!looksAt(entity, MC.player.entityInteractionRange()))
			return;
		
		MC.gameMode.attack(MC.player, entity);
		swingHand.swing(InteractionHand.MAIN_HAND);
		waitingForLanding = true;
	}
	
	private void breakCrystal(Entity crystal)
	{
		if(!looksAt(crystal, range.getValue()))
			return;
		
		MC.gameMode.attack(MC.player, crystal);
		swingHand.swing(InteractionHand.MAIN_HAND);
		
		aimPoint.next();
		lastBrokenId = crystal.getId();
		lastBrokenCooldown = RETRY_COOLDOWN;
		delay = actionDelay.getValueI();
	}
	
	/**
	 * Whether the rotation that will be sent this tick looks at the entity,
	 * with no block in the way.
	 */
	private boolean looksAt(Entity entity, double reach)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 end = eyes.add(getServerLookVec().scale(reach));
		Optional<Vec3> entityHit = entity.getBoundingBox().clip(eyes, end);
		if(entityHit.isEmpty())
			return false;
		
		BlockHitResult blockHit = BlockUtils.raycast(eyes, end);
		return blockHit.getType() == HitResult.Type.MISS
			|| eyes.distanceToSqr(entityHit.get()) < eyes
				.distanceToSqr(blockHit.getLocation());
	}
	
	private BlockHitResult raycastBlocks(double reach)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		return BlockUtils.raycast(eyes,
			eyes.add(getServerLookVec().scale(reach)));
	}
	
	private Vec3 getServerLookVec()
	{
		return new Rotation(WURST.getRotationFaker().getServerYaw(),
			WURST.getRotationFaker().getServerPitch()).toLookVec();
	}
	
	private Vec3 getClosestPoint(AABB box, Vec3 eyes)
	{
		return new Vec3(Mth.clamp(eyes.x, box.minX, box.maxX),
			Mth.clamp(eyes.y, box.minY, box.maxY),
			Mth.clamp(eyes.z, box.minZ, box.maxZ));
	}
	
	/**
	 * Estimates the damage that an end crystal explosion at the given
	 * position would deal to an entity with the given hitbox, ignoring armor
	 * and difficulty. Same formula as vanilla's ServerExplosion.
	 */
	private float getDamage(Vec3 center, AABB box, Entity entity)
	{
		double diameter = EXPLOSION_RADIUS * 2;
		Vec3 feet = new Vec3(box.getCenter().x, box.minY, box.getCenter().z);
		double dist = feet.distanceTo(center) / diameter;
		if(dist > 1)
			return 0;
		
		double impact = (1 - dist) * getSeenPercent(center, box, entity);
		return (float)((impact * impact + impact) / 2 * 7 * diameter + 1);
	}
	
	/**
	 * Same as vanilla's ServerExplosion.getSeenPercent(), but for any hitbox,
	 * so it can also be used for where the target will be.
	 */
	private float getSeenPercent(Vec3 center, AABB box, Entity entity)
	{
		double xStep = 1 / ((box.maxX - box.minX) * 2 + 1);
		double yStep = 1 / ((box.maxY - box.minY) * 2 + 1);
		double zStep = 1 / ((box.maxZ - box.minZ) * 2 + 1);
		double xOffset = (1 - Math.floor(1 / xStep) * xStep) / 2;
		double zOffset = (1 - Math.floor(1 / zStep) * zStep) / 2;
		if(xStep < 0 || yStep < 0 || zStep < 0)
			return 0;
		
		int seen = 0;
		int total = 0;
		for(double x = 0; x <= 1; x += xStep)
			for(double y = 0; y <= 1; y += yStep)
				for(double z = 0; z <= 1; z += zStep)
				{
					Vec3 from =
						new Vec3(Mth.lerp(x, box.minX, box.maxX) + xOffset,
							Mth.lerp(y, box.minY, box.maxY),
							Mth.lerp(z, box.minZ, box.maxZ) + zOffset);
					
					if(MC.level.clip(new ClipContext(from, center,
						ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
						entity)).getType() == HitResult.Type.MISS)
						seen++;
					
					total++;
				}
			
		return total == 0 ? 0 : (float)seen / total;
	}
	
	private void selectSlot(int slot)
	{
		int selected = MC.player.getInventory().getSelectedSlot();
		if(slot == -1 || selected == slot)
			return;
		
		if(originalSlot == -1)
			originalSlot = selected;
		
		// vanilla sends the slot to the server in useItemOn()
		MC.player.getInventory().setSelectedSlot(slot);
	}
	
	private int findHotbarSlot(net.minecraft.world.item.Item item)
	{
		int selected = MC.player.getInventory().getSelectedSlot();
		if(MC.player.getInventory().getItem(selected).is(item))
			return selected;
		
		for(int i = 0; i < 9; i++)
			if(MC.player.getInventory().getItem(i).is(item))
				return i;
			
		return -1;
	}
	
	private enum ActionType
	{
		PLACE,
		BREAK,
		HIT;
	}
	
	private record Action(ActionType type, BlockPos base, Direction side,
		Vec3 aim, Entity entity)
	{}
}
