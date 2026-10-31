/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.RotationFaker;
import net.wurstclient.WurstClient;

/**
 * Shared code for hacks that build chains of blocks with legit-looking
 * placements, like Clutch and Scaffold.
 */
public enum BlockChainPlacer
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	private static final int MAX_SEARCH_NODES = 512;
	
	public record Placement(BlockPos pos, BlockPos neighbor, Direction side,
		Vec3 hitVec, boolean visible)
	{}
	
	public record FallSimulation(AABB box, boolean landed)
	{}
	
	/**
	 * Simulates your movement with vanilla's air physics for the given number
	 * of ticks, stopping early if you would land on something.
	 */
	public static FallSimulation simulate(LocalPlayer player, int ticks)
	{
		AABB box = player.getBoundingBox();
		Vec3 v = player.getDeltaMovement();
		double vx = v.x;
		double vy = v.y;
		double vz = v.z;
		
		for(int i = 0; i < ticks; i++)
		{
			// horizontal movement, stopped by walls
			AABB moved = box.move(vx, 0, vz);
			if(collides(moved))
			{
				vx = 0;
				vz = 0;
			}else
				box = moved;
			
			// vertical movement
			if(vy < 0 && collides(box.expandTowards(0, vy, 0)))
				return new FallSimulation(box, true);
			
			if(vy > 0 && collides(box.expandTowards(0, vy, 0)))
				vy = 0;
			else
				box = box.move(0, vy, 0);
			
			if(box.minY < MC.level.getMinY())
				break;
			
			vx *= 0.91;
			vz *= 0.91;
			vy = (vy - 0.08) * 0.98;
		}
		
		return new FallSimulation(box, false);
	}
	
	public static boolean collides(AABB box)
	{
		return BlockUtils.getBlockCollisions(box).findAny().isPresent();
	}
	
	/**
	 * Returns the blocks at the given height that are under the given box.
	 */
	public static List<BlockPos> getBlocksUnder(AABB box, int y)
	{
		ArrayList<BlockPos> blocks = new ArrayList<>();
		int minX = Mth.floor(box.minX + 1e-3);
		int maxX = Mth.floor(box.maxX - 1e-3);
		int minZ = Mth.floor(box.minZ + 1e-3);
		int maxZ = Mth.floor(box.maxZ - 1e-3);
		
		for(int x = minX; x <= maxX; x++)
			for(int z = minZ; z <= maxZ; z++)
				blocks.add(new BlockPos(x, y, z));
			
		return blocks;
	}
	
	/**
	 * Searches outwards from the targets for the closest position that can be
	 * placed against an existing block. Placing there every tick builds a
	 * chain from that block to one of the targets.
	 *
	 * <p>
	 * Faces that aren't visible yet are allowed if they will be visible from
	 * <code>futureEyes</code>. Among equally close positions, visible faces
	 * are preferred.
	 */
	public static Placement findNextPlacement(List<BlockPos> targets, int maxY,
		AABB playerBox, double rangeSq, Predicate<BlockPos> supportFilter,
		int maxSteps, Vec3 futureEyes)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashMap<BlockPos, Integer> steps = new HashMap<>();
		for(BlockPos target : targets)
		{
			queue.add(target);
			steps.put(target, 0);
		}
		
		Placement best = null;
		int bestSteps = 0;
		
		while(!queue.isEmpty() && steps.size() < MAX_SEARCH_NODES)
		{
			BlockPos pos = queue.poll();
			int posSteps = steps.get(pos);
			
			// all positions that are this close have been checked, pick the
			// best of them
			if(best != null && posSteps > bestSteps)
				break;
				
			// can't place a block inside yourself (yet), but a chain can
			// still go through there
			if(!new AABB(pos).intersects(playerBox))
			{
				Placement placement =
					getPlacement(pos, eyes, rangeSq, supportFilter, futureEyes);
				if(placement != null
					&& (best == null || placement.visible() && !best.visible()))
				{
					best = placement;
					bestSteps = posSteps;
				}
			}
			
			if(posSteps >= maxSteps)
				continue;
			
			for(Direction dir : Direction.values())
			{
				BlockPos next = pos.relative(dir);
				
				// don't build chains above the given height
				if(next.getY() > maxY || steps.containsKey(next))
					continue;
				
				if(!BlockUtils.getState(next).canBeReplaced())
					continue;
				
				if(eyes.distanceToSqr(Vec3.atCenterOf(next)) > rangeSq)
					continue;
				
				steps.put(next, posSteps + 1);
				queue.add(next);
			}
		}
		
		return best;
	}
	
	/**
	 * Returns how to place a block at the given position against one of its
	 * neighbors, or null if no neighbor can be used.
	 */
	public static Placement getPlacement(BlockPos pos, Vec3 eyes,
		double rangeSq, Predicate<BlockPos> supportFilter, Vec3 futureEyes)
	{
		Placement best = null;
		double bestDistSq = Double.MAX_VALUE;
		
		for(Direction dir : Direction.values())
		{
			BlockPos neighbor = pos.relative(dir);
			if(!supportFilter.test(neighbor)
				|| !BlockUtils.canBeClicked(neighbor)
				|| BlockUtils.getState(neighbor).canBeReplaced()
				|| BlockUtils.isInteractive(BlockUtils.getState(neighbor)))
				continue;
			
			// the face of the neighbor that touches pos
			Direction side = dir.getOpposite();
			Vec3 normal = Vec3.atLowerCornerOf(side.getUnitVec3i());
			Vec3 hitVec = Vec3.atCenterOf(neighbor).add(normal.scale(0.5));
			
			// whether that face is facing you, i.e. can be clicked right now,
			// or will be soon
			boolean visible = eyes.subtract(hitVec).dot(normal) > 0;
			if(!visible && futureEyes.subtract(hitVec).dot(normal) <= 0)
				continue;
			
			double distSq = eyes.distanceToSqr(hitVec);
			if(distSq > rangeSq)
				continue;
			
			// prefer faces that are visible, then the closest one
			if(best != null && (best.visible() && !visible
				|| best.visible() == visible && distSq >= bestDistSq))
				continue;
			
			best = new Placement(pos, neighbor, side, hitVec, visible);
			bestDistSq = distSq;
		}
		
		return best;
	}
	
	/**
	 * Eye position at the center of the given (predicted) hitbox.
	 */
	public static Vec3 getEyesAt(AABB box, LocalPlayer player)
	{
		return new Vec3((box.minX + box.maxX) / 2,
			box.minY + player.getEyeHeight(), (box.minZ + box.maxZ) / 2);
	}
	
	/**
	 * Checks if a placement can be clicked right now without flagging, and
	 * returns the hit to click with, or null.
	 *
	 * <p>
	 * Grim (RotationPlace, PositionPlace) and Intave (InteractionRaytrace)
	 * both check block placements from where you were when you clicked, i.e.
	 * before this tick's movement, with the rotation that is sent this tick:
	 * <ul>
	 * <li>The clicked face has to face you.</li>
	 * <li>The first face that the ray along the rotation hits has to be the
	 * clicked face (Intave; Grim only needs the clicked block).</li>
	 * <li>The hit position has to be on the clicked face (vanilla and Grim's
	 * FabricatedPlace), so the point where that ray hits it is used.</li>
	 * </ul>
	 */
	public static BlockHitResult getServerRotationHit(Placement placement,
		double range)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 faceCenter = placement.hitVec();
		Vec3 normal = Vec3.atLowerCornerOf(placement.side().getUnitVec3i());
		if(eyes.subtract(faceCenter).dot(normal) <= 0)
			return null;
		
		return getHitFrom(eyes, getServerLook(), placement, range);
	}
	
	/**
	 * Whether the server-side rotation looks at the clicked face from where
	 * you are now.
	 */
	public static boolean hitsFace(Placement placement, double range)
	{
		return getHitFrom(RotationUtils.getEyesPos(), getServerLook(),
			placement, range) != null;
	}
	
	/**
	 * Raycasts along the server-side rotation that will be sent this tick.
	 */
	public static BlockHitResult raycastServerRotation(double range)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		return BlockUtils.raycast(eyes, eyes.add(getServerLook().scale(range)));
	}
	
	/**
	 * Raycasts from the given position in the given direction and returns
	 * the hit if the first thing it hits is the placement's clicked face.
	 */
	public static BlockHitResult getHitFrom(Vec3 from, Vec3 look,
		Placement placement, double range)
	{
		double reach = Math.min(range, MC.player.blockInteractionRange());
		BlockHitResult hit =
			BlockUtils.raycast(from, from.add(look.scale(reach)));
		return hits(hit, placement) ? hit : null;
	}
	
	private static Vec3 getServerLook()
	{
		RotationFaker rf = WurstClient.INSTANCE.getRotationFaker();
		return new Rotation(rf.getServerYaw(), rf.getServerPitch()).toLookVec();
	}
	
	public static boolean hits(BlockHitResult hit, Placement placement)
	{
		return hit != null && hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().equals(placement.neighbor())
			&& hit.getDirection() == placement.side();
	}
	
	/**
	 * Right-clicks the block like vanilla's startUseItem(), without the extra
	 * useItem() packet. Returns true if it worked.
	 */
	public static boolean place(BlockHitResult hit, Runnable swing)
	{
		InteractionResult result =
			MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		if(!(result instanceof InteractionResult.Success success))
			return false;
		
		if(success.swingSource() == InteractionResult.SwingSource.CLIENT)
			swing.run();
		
		return true;
	}
	
	/**
	 * Returns the hotbar slot of a block that can be built with, preferring
	 * the one you're already holding, or -1.
	 */
	public static int findBlockSlot(LocalPlayer player)
	{
		int selected = player.getInventory().getSelectedSlot();
		if(isUsableBlock(player.getInventory().getItem(selected)))
			return selected;
		
		for(int i = 0; i < 9; i++)
			if(isUsableBlock(player.getInventory().getItem(i)))
				return i;
			
		return -1;
	}
	
	public static boolean isUsableBlock(ItemStack stack)
	{
		if(stack.isEmpty() || !(stack.getItem() instanceof BlockItem item))
			return false;
		
		Block block = item.getBlock();
		if(block instanceof FallingBlock)
			return false;
		
		return block.defaultBlockState().isCollisionShapeFullBlock(
			EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}
}
