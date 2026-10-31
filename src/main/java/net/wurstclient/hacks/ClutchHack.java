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
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockChainPlacer;
import net.wurstclient.util.BlockChainPlacer.Placement;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.FaceAimPoint;
import net.wurstclient.util.PlaceRotationGuard;

@SearchTags({"auto clutch", "AutoClutch", "void save"})
public final class ClutchHack extends Hack implements UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"§lVoid§r mode only clutches when there is nothing below you"
			+ " but the void.\n\n"
			+ "§lFall distance§r mode also clutches when the ground"
			+ " below you is further away than the minimum fall distance.",
		Mode.values(), Mode.VOID);
	
	private final SliderSetting minFallDistance =
		new SliderSetting("Min fall distance",
			"Only used in §lFall distance§r mode. Clutches when the"
				+ " ground below you is further away than this.",
			10, 3, 40, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final SliderSetting prediction = new SliderSetting("Prediction",
		"How many ticks ahead Clutch predicts where you will be falling.", 3, 1,
		10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"How fast your server-side rotation turns towards the blocks that"
			+ " Clutch places.",
		60, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final SliderSetting returnSpeed = new SliderSetting("Return speed",
		"How fast your server-side rotation turns back to your real rotation"
			+ " once Clutch is done.",
		30, 10, 180, 1, ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final CheckboxSetting movementCorrection =
		new CheckboxSetting("Movement correction",
			"Moves you in the direction of your server-side rotation while"
				+ " clutching, the way the server expects it. Needed for"
				+ " anti-cheats like Grim.",
			true);
	
	private final CheckboxSetting switchBack =
		new CheckboxSetting("Switch back",
			"Switches back to the item you were holding once Clutch is done.",
			true);
	
	// ticks without clutching before switching back to the original item
	private static final int SWITCH_BACK_DELAY = 10;
	
	// how far a chain can be extended before Clutch starts a new one instead
	private static final int MAX_EXTEND_STEPS = 3;
	
	// how far ahead to check if you are about to land on something
	private static final int LANDING_TICKS = 10;
	
	private boolean clutching;
	private final FaceAimPoint aimPoint = new FaceAimPoint();
	private final PlaceRotationGuard rotationGuard = new PlaceRotationGuard();
	private int originalSlot = -1;
	private int usedSlot = -1;
	private int idleTicks;
	
	// blocks placed during the current clutch
	private final HashSet<BlockPos> placedBlocks = new HashSet<>();
	private double lastGroundY = Double.NaN;
	
	public ClutchHack()
	{
		super("Clutch");
		setCategory(Category.BLOCKS);
		addSetting(mode);
		addSetting(minFallDistance);
		addSetting(prediction);
		addSetting(turnSpeed);
		addSetting(returnSpeed);
		addSetting(movementCorrection);
		addSetting(switchBack);
	}
	
	@Override
	protected void onEnable()
	{
		clutching = false;
		originalSlot = -1;
		placedBlocks.clear();
		lastGroundY = Double.NaN;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		forceSwitchBack();
	}
	
	public boolean isClutching()
	{
		return isEnabled() && clutching;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player.onGround())
		{
			lastGroundY = player.getY();
			
			// a new jump or fall starts a new chain
			placedBlocks.clear();
		}
		
		int blockSlot = BlockChainPlacer.findBlockSlot(player);
		if(blockSlot == -1 || !isAboutToFall(player))
		{
			stopClutching();
			return;
		}
		
		// Build at the height of the block you were last standing on, so the
		// chain can start right at the edge you fell off. Once you have fallen
		// below that height, build right under your feet instead.
		int feetLevel = Mth.floor(player.getY()) - 1;
		int maxY =
			Double.isNaN(lastGroundY) ? feetLevel : Mth.floor(lastGroundY) - 1;
		int targetY = Math.min(maxY, feetLevel);
		
		// Target every block under your predicted hitbox, not just the one
		// under its center. When you fall along a block boundary, the center
		// keeps switching between two columns, which used to start a second
		// chain next to the first one.
		AABB predicted =
			BlockChainPlacer.simulate(player, prediction.getValueI()).box();
		List<BlockPos> targets =
			BlockChainPlacer.getBlocksUnder(predicted, targetY);
		for(BlockPos target : targets)
			if(!BlockUtils.getState(target).canBeReplaced())
			{
				// you will land on that block
				stopClutching();
				return;
			}
			
		// Prefer extending the chain that is already being built. Otherwise,
		// when the target moves sideways (along a long edge or when falling
		// diagonally), the closest support could be the edge again, which
		// would start a second chain next to the first one.
		// The face to place on doesn't have to be visible yet, as long as it
		// will be once you get to the predicted position (e.g. once you have
		// moved past the last block of the chain). Clutch then aims at it and
		// waits instead of building somewhere else.
		Vec3 futureEyes = BlockChainPlacer.getEyesAt(predicted, player);
		// vanilla block placing range
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
			stopClutching();
			return;
		}
		
		clutching = true;
		idleTicks = 0;
		
		WURST.getRotationFaker().faceVectorSmoothly(
			aimPoint.get(placement.neighbor(), placement.side(),
				placement.hitVec()),
			turnSpeed.getValueF(), 2, movementCorrection.isChecked(),
			returnSpeed.getValueF());
		rotationGuard.onTurn(WURST.getRotationFaker().getLastTurnYawDelta(),
			WURST.getRotationFaker().getLastTurnPitchDelta());
		
		// only place once the rotation that will be sent this tick actually
		// looks at the block, so the placement matches what the server sees
		BlockHitResult hit =
			BlockChainPlacer.getServerRotationHit(placement, range);
		if(hit == null)
			return;
			
		// Grim's DuplicateRotPlace flags placing twice after the same yaw
		// change, so aim at a different spot and try again next tick
		if(!rotationGuard.canPlace())
		{
			aimPoint.next();
			return;
		}
		
		if(place(player, blockSlot, hit))
		{
			rotationGuard.onPlaced();
			placedBlocks.add(placement.pos());
			aimPoint.next();
		}
	}
	
	private void stopClutching()
	{
		// the rotation turns back to the camera by itself once it expires
		clutching = false;
		
		// Don't switch back right away. Clutch often pauses for a tick or two
		// in the middle of a clutch, which used to switch back and forth
		// between the blocks and the original item. Wait until you land or
		// Clutch has been idle for a while instead.
		if(originalSlot == -1)
			return;
		
		LocalPlayer player = MC.player;
		if(player == null)
		{
			originalSlot = -1;
			return;
		}
		
		if(++idleTicks < SWITCH_BACK_DELAY && !player.onGround())
			return;
		
		// don't switch back if you have switched to something else yourself
		if(switchBack.isChecked()
			&& player.getInventory().getSelectedSlot() == usedSlot)
			player.getInventory().setSelectedSlot(originalSlot);
		
		originalSlot = -1;
	}
	
	private void forceSwitchBack()
	{
		idleTicks = SWITCH_BACK_DELAY;
		stopClutching();
	}
	
	private boolean isAboutToFall(LocalPlayer player)
	{
		if(player.onGround() || player.getAbilities().flying
			|| player.isFallFlying() || player.isInWater() || player.isInLava()
			|| player.isPassenger() || player.onClimbable())
			return false;
		
		// about to land on something (e.g. the chain that Clutch just built)
		if(BlockChainPlacer.simulate(player, LANDING_TICKS).landed())
			return false;
		
		Vec3 predicted = predictPosition(player);
		AABB box = player.getBoundingBox().move(predicted.x - player.getX(), 0,
			predicted.z - player.getZ());
		
		double depth = mode.getSelected() == Mode.VOID
			? box.minY - MC.level.getMinY() + 1 : minFallDistance.getValue();
		
		AABB below = box.expandTowards(0, -depth, 0);
		return BlockUtils.getBlockCollisions(below).findAny().isEmpty();
	}
	
	/**
	 * Predicts the horizontal position a few ticks ahead.
	 */
	private Vec3 predictPosition(LocalPlayer player)
	{
		AABB box =
			BlockChainPlacer.simulate(player, prediction.getValueI()).box();
		return new Vec3((box.minX + box.maxX) / 2, player.getY(),
			(box.minZ + box.maxZ) / 2);
	}
	
	private boolean place(LocalPlayer player, int blockSlot, BlockHitResult hit)
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
		
		return BlockChainPlacer.place(hit,
			() -> player.swing(InteractionHand.MAIN_HAND));
	}
	
	private enum Mode
	{
		VOID("Void"),
		FALL_DISTANCE("Fall distance");
		
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
}
