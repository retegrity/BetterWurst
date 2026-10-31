/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.tpaura;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.PriorityQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;

/**
 * Finds a path of teleports to a spot from where the player can hit the
 * target. Every teleport is a straight line through free space, so that the
 * server's "moved wrongly" check accepts it, and at most {@link #MAX_STEP}
 * blocks long, so that its "moved too quickly" check accepts it too.
 */
public final class TeleportPathFinder
{
	private static final Minecraft MC = WurstClient.MC;
	
	// vanilla accepts at least 10 blocks per movement packet
	public static final double MAX_STEP = 9;
	
	private static final int MAX_NODES = 3000;
	
	// a search for an unreachable target would otherwise freeze the game
	private static final long TIME_BUDGET_NANOS = 15_000_000;
	private static final double AIR_COST = 0.5;
	private static final double HEURISTIC_WEIGHT = 1.5;
	
	private final LocalPlayer player = MC.player;
	private final EntityDimensions dimensions =
		player.getDimensions(player.getPose());
	private final double eyeHeight = player.getEyeHeight(player.getPose());
	private final AABB targetBox;
	private final double reach;
	
	private final HashMap<Long, Boolean> fitsCache = new HashMap<>();
	private final HashMap<Long, Boolean> groundCache = new HashMap<>();
	
	private boolean searched;
	private boolean timedOut;
	
	public TeleportPathFinder(AABB targetBox, double reach)
	{
		this.targetBox = targetBox;
		this.reach = reach;
	}
	
	/**
	 * Tries a straight line to the target first, which is enough in most
	 * arenas, and only searches for a path around obstacles if that fails and
	 * <code>allowSearch</code> is true.
	 *
	 * @return the teleports from the start to the spot to attack from, empty
	 *         if the target can already be hit from the start, or null if
	 *         there is no path
	 */
	public ArrayList<Vec3> find(Vec3 start, boolean allowSearch)
	{
		if(getDistanceLeft(start) <= 0)
			return new ArrayList<>();
		
		ArrayList<Vec3> straight = findStraight(start);
		if(straight != null || !allowSearch)
			return straight;
		
		searched = true;
		return search(start);
	}
	
	/**
	 * Whether {@link #find(Vec3, boolean)} had to search for a path around
	 * obstacles.
	 */
	public boolean hasSearched()
	{
		return searched;
	}
	
	/**
	 * Whether the search ran out of time, so the target might still be
	 * reachable.
	 */
	public boolean hasTimedOut()
	{
		return timedOut;
	}
	
	/**
	 * Teleports in a straight line to a spot right next to the target, on the
	 * side facing the start.
	 */
	private ArrayList<Vec3> findStraight(Vec3 start)
	{
		Vec3 center = targetBox.getBottomCenter();
		Vec3 away = new Vec3(start.x - center.x, 0, start.z - center.z);
		double length = away.length();
		Vec3 spot = center;
		if(length > 1e-4)
			spot = center.add(away.scale(
				Math.min(length, targetBox.getXsize() / 2 + 1) / length));
		
		if(getDistanceLeft(spot) > 0
			|| !MC.level.noBlockCollision(player, getBox(spot))
			|| !canMoveStraight(start, spot))
			return null;
		
		ArrayList<Vec3> teleports = new ArrayList<>();
		int steps = (int)Math.ceil(start.distanceTo(spot) / MAX_STEP);
		for(int i = 1; i <= steps; i++)
			teleports.add(start.lerp(spot, i / (double)steps));
		
		return teleports;
	}
	
	private ArrayList<Vec3> search(Vec3 start)
	{
		long startKey = BlockPos.containing(start).asLong();
		HashMap<Long, Double> costs = new HashMap<>();
		HashMap<Long, Long> cameFrom = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();
		costs.put(startKey, 0.0);
		open.add(new Node(startKey, 0, 0));
		
		Long goal = null;
		long deadline = System.nanoTime() + TIME_BUDGET_NANOS;
		for(int i = 0; i < MAX_NODES && !open.isEmpty(); i++)
		{
			if(System.nanoTime() > deadline)
			{
				timedOut = true;
				break;
			}
			
			Node node = open.poll();
			if(node.cost > costs.get(node.key))
				continue;
			
			Vec3 from = getPos(node.key, startKey, start);
			if(getDistanceLeft(from) <= 0)
			{
				goal = node.key;
				break;
			}
			
			BlockPos pos = BlockPos.of(node.key);
			AABB fromBox = getBox(from);
			for(int dx = -1; dx <= 1; dx++)
				for(int dy = -1; dy <= 1; dy++)
					for(int dz = -1; dz <= 1; dz++)
					{
						if(dx == 0 && dy == 0 && dz == 0)
							continue;
						
						BlockPos next = pos.offset(dx, dy, dz);
						long nextKey = next.asLong();
						if(nextKey == startKey || !fits(next))
							continue;
						
						Vec3 to = Vec3.atBottomCenterOf(next);
						if(!MC.level.noBlockCollision(player,
							fromBox.minmax(getBox(to))))
							continue;
						
						double cost = node.cost + from.distanceTo(to)
							+ (isOnGround(next) ? 0 : AIR_COST);
						if(cost >= costs.getOrDefault(nextKey,
							Double.POSITIVE_INFINITY))
							continue;
						
						costs.put(nextKey, cost);
						cameFrom.put(nextKey, node.key);
						open.add(new Node(nextKey, cost,
							cost + HEURISTIC_WEIGHT * getDistanceLeft(to)));
					}
		}
		
		if(goal == null)
			return null;
		
		ArrayList<Vec3> points = new ArrayList<>();
		for(Long key = goal; key != null; key = cameFrom.get(key))
			points.add(getPos(key, startKey, start));
		Collections.reverse(points);
		
		return shorten(points);
	}
	
	/**
	 * Merges the path into as few teleports as possible.
	 */
	private ArrayList<Vec3> shorten(ArrayList<Vec3> points)
	{
		ArrayList<Vec3> teleports = new ArrayList<>();
		int last = points.size() - 1;
		int i = 0;
		while(i < last)
		{
			int next = i + 1;
			for(int j = last; j > i + 1; j--)
				if(points.get(i).distanceTo(points.get(j)) <= MAX_STEP
					&& canMoveStraight(points.get(i), points.get(j)))
				{
					next = j;
					break;
				}
			
			teleports.add(points.get(next));
			i = next;
		}
		
		return teleports;
	}
	
	private boolean canMoveStraight(Vec3 from, Vec3 to)
	{
		int steps = Math.max(1, (int)Math.ceil(from.distanceTo(to) / 0.25));
		AABB prevBox = getBox(from);
		for(int i = 1; i <= steps; i++)
		{
			AABB box = getBox(from.lerp(to, i / (double)steps));
			if(!MC.level.noBlockCollision(player, prevBox.minmax(box)))
				return false;
			
			prevBox = box;
		}
		
		return true;
	}
	
	/**
	 * How much further the player's eyes are from the target than they can
	 * reach.
	 */
	private double getDistanceLeft(Vec3 pos)
	{
		Vec3 eyes = pos.add(0, eyeHeight, 0);
		double dx = Math.max(0,
			Math.max(targetBox.minX - eyes.x, eyes.x - targetBox.maxX));
		double dy = Math.max(0,
			Math.max(targetBox.minY - eyes.y, eyes.y - targetBox.maxY));
		double dz = Math.max(0,
			Math.max(targetBox.minZ - eyes.z, eyes.z - targetBox.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz) - reach;
	}
	
	private Vec3 getPos(long key, long startKey, Vec3 start)
	{
		return key == startKey ? start
			: Vec3.atBottomCenterOf(BlockPos.of(key));
	}
	
	private AABB getBox(Vec3 pos)
	{
		return dimensions.makeBoundingBox(pos);
	}
	
	private boolean fits(BlockPos pos)
	{
		return fitsCache.computeIfAbsent(pos.asLong(), k -> MC.level
			.noBlockCollision(player, getBox(Vec3.atBottomCenterOf(pos))));
	}
	
	private boolean isOnGround(BlockPos pos)
	{
		return groundCache.computeIfAbsent(pos.asLong(),
			k -> isOnGround(Vec3.atBottomCenterOf(pos)));
	}
	
	/**
	 * Whether the player would stand on something at this position.
	 */
	public static boolean isOnGround(Vec3 pos)
	{
		LocalPlayer player = MC.player;
		AABB box = player.getDimensions(player.getPose()).makeBoundingBox(pos);
		AABB below = new AABB(box.minX, box.minY - 0.05, box.minZ, box.maxX,
			box.minY, box.maxZ);
		return !MC.level.noBlockCollision(player, below);
	}
	
	private record Node(long key, double cost, double estimate)
		implements Comparable<Node>
	{
		@Override
		public int compareTo(Node other)
		{
			return Double.compare(estimate, other.estimate);
		}
	}
}
