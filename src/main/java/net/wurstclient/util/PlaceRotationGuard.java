/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

/**
 * Mirrors Grim's DuplicateRotPlace check, so that hacks can avoid placing a
 * block when it would flag.
 *
 * <p>
 * Grim remembers the yaw change of the last rotation the player sent. When a
 * block is placed after any rotation, and that yaw change is above 2° and
 * exactly the same as at the previous placement, it flags.
 *
 * <p>
 * Call {@link #onTurn(float, float)} every tick after turning,
 * {@link #canPlace()} before placing and {@link #onPlaced()} after placing.
 */
public final class PlaceRotationGuard
{
	private float deltaYaw;
	private boolean rotated;
	private float lastPlacedDeltaYaw = Float.NaN;
	
	public void onTurn(float yawChange, float pitchChange)
	{
		// rotations that didn't change anything aren't sent
		if(yawChange == 0 && pitchChange == 0)
			return;
		
		deltaYaw = Math.abs(yawChange);
		rotated = true;
	}
	
	public boolean canPlace()
	{
		return !rotated || deltaYaw <= 2
			|| Math.abs(deltaYaw - lastPlacedDeltaYaw) >= 0.001F;
	}
	
	public void onPlaced()
	{
		if(!rotated)
			return;
		
		lastPlacedDeltaYaw = deltaYaw;
		rotated = false;
	}
}
