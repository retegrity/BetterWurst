/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.Objects;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Picks a random point on a block face to aim at, instead of always aiming at
 * the exact center. Aiming at the same spot on every block makes the rotation
 * change between placements repeat exactly (e.g. only one mouse step of pitch
 * each time), which Grim's DuplicateRotPlace check flags.
 *
 * <p>
 * The point stays the same while aiming at the same face, so the rotation
 * doesn't jitter, and changes after each click via {@link #next()}.
 */
public final class FaceAimPoint
{
	private static final double MAX_OFFSET = 0.3;
	
	private final Random random = new Random();
	private BlockPos pos;
	private Direction side;
	private double u;
	private double v;
	
	/**
	 * Returns the point to aim at on the given face. The face is given by
	 * its block, its side and the center of the face.
	 */
	public Vec3 get(BlockPos pos, Direction side, Vec3 faceCenter)
	{
		if(!Objects.equals(pos, this.pos) || side != this.side)
		{
			this.pos = pos;
			this.side = side;
			roll();
		}
		
		return switch(side.getAxis())
		{
			case X -> faceCenter.add(0, u, v);
			case Y -> faceCenter.add(u, 0, v);
			case Z -> faceCenter.add(u, v, 0);
		};
	}
	
	/**
	 * Picks a new point for the next click.
	 */
	public void next()
	{
		roll();
	}
	
	private void roll()
	{
		u = (random.nextDouble() * 2 - 1) * MAX_OFFSET;
		v = (random.nextDouble() * 2 - 1) * MAX_OFFSET;
	}
}
