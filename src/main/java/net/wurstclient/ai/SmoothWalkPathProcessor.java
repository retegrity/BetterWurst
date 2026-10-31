/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.ai;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walks along a path more like a human than {@link WalkPathProcessor}: instead
 * of going from block center to block center, it walks straight towards the
 * furthest point of the path that it can reach in a straight line, and turns
 * the camera smoothly instead of snapping it.
 *
 * <p>
 * Climbing, swimming and pillaring up are left to {@link WalkPathProcessor}.
 */
public class SmoothWalkPathProcessor extends PathProcessor
{
	// how many path nodes ahead it looks for a straight line
	private static final int LOOK_AHEAD = 10;
	
	// natural pitch while walking
	private static final float WALKING_PITCH = 12;
	
	private static final Random RANDOM = new Random();
	
	private final WalkPathProcessor fallback;
	
	public SmoothWalkPathProcessor(ArrayList<PathPos> path)
	{
		super(path);
		fallback = new WalkPathProcessor(path);
	}
	
	@Override
	public void process()
	{
		LocalPlayer player = MC.player;
		BlockPos pos = player.onGround() ? BlockPos.containing(player.getX(),
			player.getY() + 0.5, player.getZ())
			: BlockPos.containing(player.position());
		
		updateIndex(pos);
		if(index >= path.size())
		{
			done = true;
			return;
		}
		
		PathPos next = path.get(index);
		boolean straightUp = isAbove(next, pos);
		
		// a jump onto the next block, where the path continues
		PathPos jumpTarget = null;
		if(straightUp && index + 1 < path.size())
		{
			PathPos after = path.get(index + 1);
			if(after.getY() == next.getY() && isNextTo(after, next))
				jumpTarget = after;
		}
		
		if(player.isInWater() || player.isInLava() || player.onClimbable()
			|| straightUp && jumpTarget == null)
		{
			useFallback();
			return;
		}
		
		lockControls();
		player.getAbilities().flying = false;
		
		// falling down: just let it happen
		if(!player.onGround() && isAbove(pos, next))
			return;
		
		PathPos target = jumpTarget != null ? jumpTarget : pickTarget(pos);
		float yawLeft = turnCameraTowards(Vec3.atBottomCenterOf(target));
		if(Math.abs(yawLeft) > 45)
			return;
		
		MC.options.keyUp.setDown(true);
		
		boolean needsJump =
			jumpTarget != null || index > 0 && path.get(index - 1).isJumping()
				|| next.getY() > pos.getY() || player.horizontalCollision;
		if(needsJump && player.onGround())
			MC.options.keyJump.setDown(true);
	}
	
	/**
	 * Moves the index past the nodes that the player has already reached.
	 */
	private void updateIndex(BlockPos pos)
	{
		int from = Math.max(0, index - 1);
		int to = Math.min(path.size(), index + LOOK_AHEAD);
		int reached = -1;
		for(int i = from; i < to; i++)
			if(isSameBlock(path.get(i), pos))
				reached = i;
			
		if(reached == -1)
		{
			ticksOffPath++;
			return;
		}
		
		ticksOffPath = 0;
		if(reached >= index)
			index = reached + 1;
	}
	
	/**
	 * The furthest node at the same height that the player can walk to in a
	 * straight line.
	 */
	private PathPos pickTarget(BlockPos pos)
	{
		LocalPlayer player = MC.player;
		int best = index;
		int end = Math.min(path.size(), index + LOOK_AHEAD);
		
		for(int i = index; i < end; i++)
		{
			PathPos node = path.get(i);
			if(node.getY() != pos.getY())
				break;
			
			if(i > index && !isNextTo(node, path.get(i - 1)))
				break;
			
			if(!canWalkStraight(player, Vec3.atBottomCenterOf(node)))
				break;
			
			best = i;
		}
		
		return path.get(best);
	}
	
	/**
	 * Whether the player's hitbox fits along the straight line to the target,
	 * with ground below it the whole way.
	 */
	private boolean canWalkStraight(LocalPlayer player, Vec3 target)
	{
		Vec3 start = player.position();
		Vec3 end = new Vec3(target.x, start.y, target.z);
		double distance = start.distanceTo(end);
		AABB box = player.getBoundingBox();
		
		int steps = Math.max(1, (int)Math.ceil(distance / 0.25));
		for(int i = 1; i <= steps; i++)
		{
			Vec3 offset = end.subtract(start).scale(i / (double)steps);
			AABB moved = box.move(offset);
			
			if(!MC.level.noCollision(player, moved))
				return false;
			
			// no ground below means it would fall
			if(MC.level.noCollision(player, moved.move(0, -0.6, 0)))
				return false;
		}
		
		return true;
	}
	
	/**
	 * Turns the camera smoothly towards the target, like a mouse would, and
	 * the pitch towards a natural walking angle.
	 *
	 * @return how far it still has to turn
	 */
	public static float turnCameraTowards(Vec3 target)
	{
		LocalPlayer player = MC.player;
		double dx = target.x - player.getX();
		double dz = target.z - player.getZ();
		float wantedYaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90;
		
		float yawDiff = Mth.wrapDegrees(wantedYaw - player.getYRot());
		float yawStep = Math.abs(yawDiff) < 2 ? yawDiff : Mth
			.clamp(yawDiff * (0.35F + RANDOM.nextFloat() * 0.15F), -35, 35);
		float pitchStep =
			Mth.clamp((WALKING_PITCH - player.getXRot()) * 0.2F, -8, 8);
		
		// steps that a real mouse could make
		float gcd = WURST.getRotationFaker().getMouseGcd();
		yawStep = Math.round(yawStep / gcd) * gcd;
		pitchStep = Math.round(pitchStep / gcd) * gcd;
		
		WURST.getRotationFaker().turnCamera(player.getYRot() + yawStep,
			Mth.clamp(player.getXRot() + pitchStep, -90, 90));
		return yawDiff - yawStep;
	}
	
	private void useFallback()
	{
		fallback.index = index;
		fallback.process();
		index = fallback.index;
		done = fallback.done;
		ticksOffPath = fallback.ticksOffPath;
	}
	
	private static boolean isSameBlock(BlockPos a, BlockPos b)
	{
		return a.getX() == b.getX() && a.getY() == b.getY()
			&& a.getZ() == b.getZ();
	}
	
	private static boolean isAbove(BlockPos upper, BlockPos lower)
	{
		return upper.getX() == lower.getX() && upper.getZ() == lower.getZ()
			&& upper.getY() > lower.getY();
	}
	
	private static boolean isNextTo(BlockPos a, BlockPos b)
	{
		int dx = Math.abs(a.getX() - b.getX());
		int dz = Math.abs(a.getZ() - b.getZ());
		return Math.max(dx, dz) == 1;
	}
	
	@Override
	public boolean canBreakBlocks()
	{
		return MC.player.onGround();
	}
}
