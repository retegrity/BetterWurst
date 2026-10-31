/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockChainPlacer;
import net.wurstclient.util.BlockChainPlacer.Placement;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.FaceAimPoint;
import net.wurstclient.util.PlaceRotationGuard;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.JumpUtils;

@SearchTags({"ScaffoldWalk", "scaffold walk", "bridge", "tower", "auto bridge"})
public final class ScaffoldHack extends Hack implements UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lNormal\u00a7r bridges while you walk.\n\n"
			+ "\u00a7lTelly\u00a7r sprint-jumps while you move forward: it"
			+ " jumps and looks forward on the ground, turns around in the air"
			+ " to build the bridge behind you at the height you jumped from,"
			+ " then turns forward again before landing, so the next jump is"
			+ " a sprint jump too. Much faster than normal bridging.",
		Mode.values(), Mode.NORMAL);
	
	private final SliderSetting tellyStraightTicks =
		new SliderSetting("Telly straight ticks",
			"How many ticks after jumping Telly keeps looking forward before"
				+ " turning around to place blocks.",
			1, 0, 5, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting prediction = new SliderSetting("Prediction",
		"How many ticks ahead Scaffold predicts where you are going, so the"
			+ " blocks are there in time.",
		2, 1, 5, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"How fast your server-side rotation turns towards the blocks that"
			+ " Scaffold places.",
		120, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final SliderSetting returnSpeed = new SliderSetting("Return speed",
		"How fast your server-side rotation turns back to your real rotation"
			+ " once Scaffold is done.",
		40, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final EnumSetting<Aim> aim = new EnumSetting<>("Aim",
		"\u00a7lStay\u00a7r keeps your rotation where it is as long as it"
			+ " still looks at the next block, and otherwise turns to the"
			+ " middle of the block. This keeps your head (and your movement)"
			+ " steady while bridging.\n\n"
			+ "\u00a7lRandom\u00a7r turns to a random point on every block,"
			+ " which looks less robotic but moves your head a lot more.",
		Aim.values(), Aim.STAY);
	
	private final SliderSetting rotationHold =
		new SliderSetting("Rotation hold",
			"How long your server-side rotation stays on the last block before"
				+ " turning back to your real rotation (at the return speed).",
			6, 1, 20, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final CheckboxSetting movementCorrection =
		new CheckboxSetting("Movement correction",
			"Moves you in the direction of your server-side rotation while"
				+ " Scaffold is aiming, the way the server expects it. You"
				+ " still walk where you want to go. Needed for anti-cheats"
				+ " like Grim.",
			true);
	
	private final EnumSetting<Sprint> sprint = new EnumSetting<>("Sprint",
		"§lLegit§r leaves sprinting to the game. With movement"
			+ " correction, you can't sprint while your server-side rotation"
			+ " looks backwards, just like a real player.\n\n"
			+ "§lOff§r never sprints while Scaffold is enabled.\n\n"
			+ "§lAlways§r keeps you sprinting.\n§c§lWARNING:"
			+ "§r This flags most anti-cheats.",
		Sprint.values(), Sprint.LEGIT);
	
	private final CheckboxSetting tower = new CheckboxSetting("Tower",
		"Hold jump without moving to build straight up, without the usual"
			+ " delay between jumps.",
		true);
	
	private final CheckboxSetting keepY = new CheckboxSetting("Keep-Y",
		"While jumping, keeps building at the height you jumped from, so"
			+ " jump-bridging builds a flat bridge.",
		true);
	
	private final CheckboxSetting eagle = new CheckboxSetting("Eagle",
		"Sneaks at the edge until the next block is placed, so you can't walk"
			+ " off.",
		false);
	
	private final CheckboxSetting switchBack =
		new CheckboxSetting("Switch back",
			"Switches back to the item you were holding once Scaffold is done.",
			true);
	
	private final CheckboxSetting debug = new CheckboxSetting("Debug",
		"Writes what Scaffold does on every tick to the game log"
			+ " (latest.log).",
		false);
	
	private static final Logger LOGGER = LoggerFactory.getLogger("Scaffold");
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	// ticks without building before switching back to the original item
	private static final int SWITCH_BACK_DELAY = 10;
	
	// Telly turns forward again this many ticks before landing
	private static final int TELLY_LANDING_TICKS = 2;
	
	// how far a chain can be extended before starting a new one instead
	private static final int MAX_EXTEND_STEPS = 3;
	
	private final FaceAimPoint aimPoint = new FaceAimPoint();
	private final PlaceRotationGuard rotationGuard = new PlaceRotationGuard();
	private final HashSet<BlockPos> placedBlocks = new HashSet<>();
	
	private double lastGroundY = Double.NaN;
	private Vec3 lastPos;
	private Vec3 movement = Vec3.ZERO;
	private int originalSlot = -1;
	private int usedSlot = -1;
	private int idleTicks;
	private boolean holdingSneak;
	private boolean holdingSprint;
	private boolean holdingJump;
	private int airTicks;
	private boolean randomAimOnce;
	private final Random random = new Random();
	private float bridgeYaw = Float.NaN;
	private float bridgeYawOffset;
	
	public ScaffoldHack()
	{
		super("Scaffold");
		setCategory(Category.BLOCKS);
		addSetting(mode);
		addSetting(tellyStraightTicks);
		addSetting(prediction);
		addSetting(turnSpeed);
		addSetting(returnSpeed);
		addSetting(aim);
		addSetting(rotationHold);
		addSetting(movementCorrection);
		addSetting(sprint);
		addSetting(tower);
		addSetting(keepY);
		addSetting(eagle);
		addSetting(switchBack);
		addSetting(swingHand);
		addSetting(debug);
	}
	
	@Override
	protected void onEnable()
	{
		placedBlocks.clear();
		lastGroundY = Double.NaN;
		lastPos = null;
		movement = Vec3.ZERO;
		originalSlot = -1;
		idleTicks = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		releaseSneak();
		releaseSprintKey();
		releaseJumpKey();
		idleTicks = SWITCH_BACK_DELAY;
		goIdle();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		boolean towering = isTowering();
		
		// The game already updates the "old position" before this event, so
		// track the movement of the last tick here. Ignore teleports.
		Vec3 position = player.position();
		movement = lastPos == null || position.distanceToSqr(lastPos) > 4
			? Vec3.ZERO : position.subtract(lastPos);
		lastPos = position;
		
		if(player.onGround() && !towering)
			lastGroundY = player.getY();
		
		handleSprint(player);
		
		if(towering)
			JumpUtils.skipJumpDelay();
		
		airTicks = player.onGround() ? 0 : airTicks + 1;
		boolean telly = isTelly(towering);
		releaseJumpKey();
		if(telly)
			tellyJump(player);
		
		int blockSlot = BlockChainPlacer.findBlockSlot(player);
		if(blockSlot == -1 || player.getAbilities().flying
			|| player.isPassenger())
		{
			releaseSneak();
			goIdle();
			return;
		}
		
		int targetY = getTargetY(player, towering, telly);
		AABB predicted = predictBox(player, prediction.getValueI());
		
		// Build under the middle of where you will be, like a real player
		// does. Waiting until your whole hitbox is over the edge (as before)
		// is too late, since you fall a moment later.
		Vec3 center = predicted.getCenter();
		BlockPos target = BlockPos.containing(center.x, targetY, center.z);
		if(!BlockUtils.getState(target).canBeReplaced())
		{
			releaseSneak();
			
			// keep looking down while towering, instead of flicking back to
			// your real rotation between jumps
			if(towering)
				aimBelowFeet(player);
			
			goIdle();
			return;
		}
		List<BlockPos> targets = List.of(target);
		
		// If you have just walked off and started falling, the block can
		// still be built from the edge you came from, like Clutch does
		int maxY = targetY;
		if(!Double.isNaN(lastGroundY) && player.getY() <= lastGroundY + 1.5)
			maxY = Math.max(targetY, Mth.floor(lastGroundY) - 1);
			
		// sneak at the edge until the block is there
		// (but not while you are typing in chat, in your inventory, etc.)
		if(!WURST.getHax().invWalkHack.canPressKey(MC.options.keyShift))
			releaseSneak();
		else if(eagle.isChecked() && player.onGround() && !towering && !telly)
		{
			MC.options.keyShift.setDown(true);
			holdingSneak = true;
		}
		
		Vec3 futureEyes = BlockChainPlacer.getEyesAt(predicted, player);
		double range = player.blockInteractionRange();
		double rangeSq = range * range;
		AABB playerBox = player.getBoundingBox();
		Placement placement = null;
		if(!placedBlocks.isEmpty())
			placement = BlockChainPlacer.findNextPlacement(targets, maxY,
				playerBox, rangeSq, placedBlocks::contains, MAX_EXTEND_STEPS,
				futureEyes);
		if(placement == null)
			placement = BlockChainPlacer.findNextPlacement(targets, maxY,
				playerBox, rangeSq, pos -> true, Integer.MAX_VALUE, futureEyes);
		if(placement == null)
		{
			goIdle();
			return;
		}
		
		idleTicks = 0;
		
		// Telly: look forward on the ground, right after jumping and just
		// before landing, so every jump is a sprint jump in the right
		// direction. Only turn around to place blocks in between.
		if(telly && !canTellyAim(player))
		{
			WURST.getRotationFaker().expireManagedRotation();
			if(debug.isChecked())
				LOGGER.info(String.format(Locale.ROOT,
					"t=%d telly: looking forward (air=%d, ground=%s)",
					player.tickCount, airTicks, player.onGround()));
			return;
		}
		
		// Stay: don't move the rotation as long as it still looks at the
		// clicked face
		boolean stay = aim.getSelected() == Aim.STAY && !randomAimOnce
			&& BlockChainPlacer.hitsFace(placement, range)
			&& WURST.getRotationFaker().holdManagedRotation(getHoldTicks());
		
		String aimMode = "stay";
		if(!stay)
		{
			Vec3 aimAt = null;
			if(aim.getSelected() == Aim.STAY && !randomAimOnce)
				aimAt = getBridgeAim(player, placement);
			aimMode = aimAt != null ? "bridge" : "face";
			
			if(aimAt == null)
			{
				aimAt = aim.getSelected() == Aim.RANDOM || randomAimOnce
					? aimPoint.get(placement.neighbor(), placement.side(),
						placement.hitVec())
					: placement.hitVec();
			}
			
			turnTo(aimAt);
		}
		rotationGuard.onTurn(WURST.getRotationFaker().getLastTurnYawDelta(),
			WURST.getRotationFaker().getLastTurnPitchDelta());
		
		// a real player couldn't place while moving a totem
		if(WURST.getHax().autoTotemHack.isBusy())
			return;
			
		// only place once the rotation that will be sent this tick actually
		// looks at the block, so the placement matches what the server sees
		BlockHitResult hit =
			BlockChainPlacer.getServerRotationHit(placement, range);
		if(debug.isChecked())
			LOGGER.info(String.format(Locale.ROOT,
				"t=%d pos=(%.3f, %.3f, %.3f) moved=(%.3f, %.3f) place=%s"
					+ " against=%s %s%s aim=%s rot=(%.2f, %.2f) => %s",
				player.tickCount, player.getX(), player.getY(), player.getZ(),
				movement.x, movement.z, placement.pos().toShortString(),
				placement.neighbor().toShortString(), placement.side(),
				placement.visible() ? "" : " (hidden)", aimMode,
				WURST.getRotationFaker().getServerYaw(),
				WURST.getRotationFaker().getServerPitch(),
				hit != null ? "CLICKABLE" : "NOT CLICKABLE"));
		if(hit == null)
			return;
			
		// Grim's DuplicateRotPlace flags placing twice after the same yaw
		// change, so aim at a different spot and try again next tick
		if(!rotationGuard.canPlace())
		{
			// in Stay mode, the same turn would just happen again, so aim at a
			// random point next time
			randomAimOnce = true;
			aimPoint.next();
			return;
		}
		
		selectSlot(player, blockSlot);
		if(!BlockChainPlacer.place(hit,
			() -> swingHand.swing(InteractionHand.MAIN_HAND)))
			return;
		
		rotationGuard.onPlaced();
		aimPoint.next();
		randomAimOnce = false;
		placedBlocks.add(placement.pos());
		
		if(placedBlocks.size() > 64)
			placedBlocks.clear();
	}
	
	/**
	 * For Stay: a rotation that looks back along the bridge and down at the
	 * clicked face. Moving along a straight bridge then barely changes it, so
	 * your head stays still and movement correction doesn't make you wiggle
	 * from side to side.
	 *
	 * <p>
	 * The yaw is the bridge direction (snapped to 45°) plus a small random
	 * offset, since sitting exactly on a 45° angle after turning is flagged by
	 * Intave's PlacementAnalysis (AngleSnap).
	 *
	 * <p>
	 * Returns a point to aim at, or null if the block isn't being placed
	 * along the bridge (e.g. sideways) and that rotation wouldn't hit it.
	 */
	private Vec3 getBridgeAim(LocalPlayer player, Placement placement)
	{
		if(movement.horizontalDistanceSqr() < 1e-4)
			return null;
		
		float moveYaw =
			(float)Math.toDegrees(Math.atan2(-movement.x, movement.z));
		float snappedYaw =
			Mth.wrapDegrees(Math.round((moveYaw + 180) / 45F) * 45F);
		if(snappedYaw != bridgeYaw)
		{
			bridgeYaw = snappedYaw;
			float offset = 1 + random.nextFloat() * 3;
			bridgeYawOffset = random.nextBoolean() ? offset : -offset;
		}
		float yaw = bridgeYaw + bridgeYawOffset;
		
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 face = placement.hitVec();
		Vec3 normal = Vec3.atLowerCornerOf(placement.side().getUnitVec3i());
		Vec3 moveDir = new Vec3(movement.x, 0, movement.z).normalize();
		double alignment = normal.dot(moveDir);
		
		// Diagonal bridges are built from faces at 45° to the bridge, e.g.
		// alternating east and north faces when going north-east. Looking
		// back along the diagonal points into both of them, but you walk
		// right through the corners between the blocks, so that ray would
		// cross the face right at its edge. The yaw is turned just enough
		// that the ray crosses the face's plane inside the face, and the
		// pitch so that it crosses at the middle of its height (going down
		// from above it would hit the top of the block first).
		boolean diagonal = alignment > 0.5 && alignment <= 0.9;
		float pitch;
		if(diagonal)
		{
			double outside = eyes.subtract(face).dot(normal);
			double horizontal = 0.05;
			if(outside > 0.02)
			{
				yaw = getDiagonalYaw(yaw, eyes, face, normal, outside);
				Vec3 lookH = new Rotation(yaw, 0).toLookVec();
				horizontal = outside / -lookH.dot(normal);
			}
			
			// not past the face yet, so look steeply down until you are
			pitch = (float)Math.toDegrees(
				Math.atan2(eyes.y - face.y, Math.max(horizontal, 0.05)));
			
		}else
		{
			// pitch towards the middle of the clicked face, not the middle of
			// the block, so the ray hits that face first (Intave checks this)
			double horizontal = Math.sqrt(
				Mth.square(face.x - eyes.x) + Mth.square(face.z - eyes.z));
			pitch =
				(float)Math.toDegrees(Math.atan2(eyes.y - face.y, horizontal));
		}
		Vec3 look = new Rotation(yaw, pitch).toLookVec();
		
		// Placing along the bridge (straight or diagonally): keep this
		// rotation even while the face isn't visible yet, instead of turning
		// around to it and back (jitter). It hits the face once you have
		// moved past it.
		boolean alongBridge = alignment > 0.9 || diagonal;
		
		if(!alongBridge && BlockChainPlacer.getHitFrom(eyes, look, placement,
			player.blockInteractionRange()) == null)
			return null;
		
		return eyes.add(look.scale(2));
	}
	
	/**
	 * Returns the yaw closest to the given one whose ray, once it has
	 * travelled the given distance outside the face (along its normal),
	 * crosses the face's plane at least 0.15 blocks inside its side edges.
	 */
	private float getDiagonalYaw(float yaw, Vec3 eyes, Vec3 face, Vec3 normal,
		double outside)
	{
		// the horizontal axis along the face
		Vec3 tangent = new Vec3(Math.abs(normal.z), 0, Math.abs(normal.x));
		double eyesT = eyes.dot(tangent);
		double faceT = face.dot(tangent);
		
		// angle of the look from straight into the face (-normal), towards
		// +tangent; tan(angle) = sideways distance / distance to the face
		double minAngle = Math.atan((faceT - 0.35 - eyesT) / outside);
		double maxAngle = Math.atan((faceT + 0.35 - eyesT) / outside);
		
		Vec3 look = new Rotation(yaw, 0).toLookVec();
		double angle = Math.atan2(look.dot(tangent), -look.dot(normal));
		angle = Mth.clamp(angle, minAngle, maxAngle);
		
		Vec3 newLook =
			normal.scale(-Math.cos(angle)).add(tangent.scale(Math.sin(angle)));
		return yaw + Mth.wrapDegrees(
			(float)Math.toDegrees(Math.atan2(-newLook.x, newLook.z)) - yaw);
	}
	
	private void turnTo(Vec3 aimAt)
	{
		// Telly turns back forward as fast as it turned around, since it has
		// to face forward again before landing
		boolean telly = mode.getSelected() == Mode.TELLY;
		float release =
			telly ? Math.max(turnSpeed.getValueF(), returnSpeed.getValueF())
				: returnSpeed.getValueF();
		
		WURST.getRotationFaker().faceVectorSmoothly(aimAt,
			turnSpeed.getValueF(), getHoldTicks(),
			movementCorrection.isChecked(), release);
	}
	
	private int getHoldTicks()
	{
		return mode.getSelected() == Mode.TELLY ? 1 : rotationHold.getValueI();
	}
	
	private boolean isTelly(boolean towering)
	{
		return mode.getSelected() == Mode.TELLY && !towering
			&& MC.options.keyUp.isDown();
	}
	
	/**
	 * Telly: jumps whenever you are on the ground, but only once the rotation
	 * looks forward again, so the jump's sprint boost goes forward.
	 */
	private void tellyJump(LocalPlayer player)
	{
		if(!player.onGround() || !isLookingForward(player))
			return;
		
		if(!WURST.getHax().invWalkHack.canPressKey(MC.options.keyJump))
			return;
		
		MC.options.keyJump.setDown(true);
		holdingJump = true;
	}
	
	/**
	 * Telly only turns around to place blocks while in the air, after the
	 * straight ticks and not right before landing.
	 */
	private boolean canTellyAim(LocalPlayer player)
	{
		if(player.onGround() || airTicks <= tellyStraightTicks.getValueI())
			return false;
		
		return !BlockChainPlacer.simulate(player, TELLY_LANDING_TICKS).landed();
	}
	
	private boolean isLookingForward(LocalPlayer player)
	{
		float serverYaw = WURST.getRotationFaker().getServerYaw();
		return Math.abs(Mth.wrapDegrees(serverYaw - player.getYRot())) < 15;
	}
	
	/**
	 * Looks at the top of the block you are towering on.
	 */
	private void aimBelowFeet(LocalPlayer player)
	{
		BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.5,
			player.getZ());
		if(BlockUtils.getState(below).canBeReplaced())
			below = below.below();
		if(BlockUtils.getState(below).canBeReplaced())
			return;
		
		// Stay: don't move the rotation if it already looks down there
		if(aim.getSelected() == Aim.STAY
			&& WURST.getRotationFaker().getServerPitch() > 80
			&& WURST.getRotationFaker()
				.holdManagedRotation(rotationHold.getValueI()))
			return;
		
		turnTo(Vec3.atCenterOf(below).add(0, 0.5, 0));
		rotationGuard.onTurn(WURST.getRotationFaker().getLastTurnYawDelta(),
			WURST.getRotationFaker().getLastTurnPitchDelta());
	}
	
	private boolean isTowering()
	{
		if(!tower.isChecked() || !MC.options.keyJump.isDown())
			return false;
		
		return !MC.options.keyUp.isDown() && !MC.options.keyDown.isDown()
			&& !MC.options.keyLeft.isDown() && !MC.options.keyRight.isDown();
	}
	
	private int getTargetY(LocalPlayer player, boolean towering, boolean telly)
	{
		// Telly always builds at the height you jumped from
		int feetLevel = Mth.floor(player.getY()) - 1;
		if(towering || !keepY.isChecked() && !telly
			|| Double.isNaN(lastGroundY))
			return feetLevel;
		
		// only while jumping, not e.g. when flying up high
		if(player.getY() > lastGroundY + 1.5)
			return feetLevel;
			
		// keep building at the height you jumped from, unless you have fallen
		// below it
		return Math.min(Mth.floor(lastGroundY) - 1, feetLevel);
	}
	
	/**
	 * Predicts where your hitbox will be horizontally, assuming you keep
	 * moving like you did during the last tick.
	 *
	 * <p>
	 * This uses how far you actually moved, not your velocity. On the
	 * ground, the velocity is what is left after friction, which is only
	 * about half of how far you really move each tick, so predictions based
	 * on it came way too late.
	 */
	private AABB predictBox(LocalPlayer player, int ticks)
	{
		double dx = movement.x * ticks;
		double dz = movement.z * ticks;
		return player.getBoundingBox().move(dx, 0, dz);
	}
	
	private void handleSprint(LocalPlayer player)
	{
		switch(sprint.getSelected())
		{
			case OFF ->
			{
				MC.options.keySprint.setDown(false);
				holdingSprint = true;
				player.setSprinting(false);
			}
			
			case ALWAYS ->
			{
				if(MC.options.keyUp.isDown() && !player.isShiftKeyDown())
					player.setSprinting(true);
			}
			
			case LEGIT -> releaseSprintKey();
		}
	}
	
	private void selectSlot(LocalPlayer player, int blockSlot)
	{
		int selected = player.getInventory().getSelectedSlot();
		if(selected != blockSlot)
		{
			if(originalSlot == -1)
				originalSlot = selected;
			
			// vanilla sends the slot to the server in useItemOn()
			player.getInventory().setSelectedSlot(blockSlot);
		}
		
		usedSlot = blockSlot;
	}
	
	/**
	 * Switches back to the original item once Scaffold hasn't built anything
	 * for a while. The rotation turns back to the camera by itself.
	 */
	private void goIdle()
	{
		if(++idleTicks < SWITCH_BACK_DELAY)
			return;
		
		placedBlocks.clear();
		
		if(originalSlot == -1)
			return;
		
		LocalPlayer player = MC.player;
		
		// don't switch back if you have switched to something else yourself
		if(player != null && switchBack.isChecked()
			&& player.getInventory().getSelectedSlot() == usedSlot)
			player.getInventory().setSelectedSlot(originalSlot);
		
		originalSlot = -1;
	}
	
	private void releaseSneak()
	{
		if(!holdingSneak)
			return;
		
		IKeyMapping.get(MC.options.keyShift).resetDownState();
		holdingSneak = false;
	}
	
	private void releaseJumpKey()
	{
		if(!holdingJump)
			return;
		
		IKeyMapping.get(MC.options.keyJump).resetDownState();
		holdingJump = false;
	}
	
	private void releaseSprintKey()
	{
		if(!holdingSprint)
			return;
		
		IKeyMapping.get(MC.options.keySprint).resetDownState();
		holdingSprint = false;
	}
	
	private enum Mode
	{
		NORMAL("Normal"),
		TELLY("Telly");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum Aim
	{
		STAY("Stay"),
		RANDOM("Random");
		
		private final String name;
		
		private Aim(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum Sprint
	{
		LEGIT("Legit"),
		OFF("Off"),
		ALWAYS("Always");
		
		private final String name;
		
		private Sprint(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
