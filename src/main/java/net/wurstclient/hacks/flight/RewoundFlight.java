/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.flight;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.SpeedHackHack;

/**
 * Flight's Rewound mode: glides as long as NoCheatPlus (the Apr 2017 version
 * that Rewound's anticheat is based on) allows it.
 *
 * <p>
 * NoCheatPlus' in-air gravity check has an exemption for carpets and slime
 * blocks (MagicAir.oddGravity): within 1 block of the set back height, i.e.
 * where you last left the ground, any vertical move below 0.006 is allowed.
 * Moves of 0 or more still fail its air time check (maxphase) and moving up
 * again after falling fails its direction change check (ychincfly), so this
 * sinks very slowly instead. Its vertical accounting check would stop this
 * after a few moves, but Rewound has it turned off (Wurst's Glide with a fall
 * speed of 0.005 lasts exactly the 200 ticks that 1 block takes there).
 *
 * <p>
 * Jumping first makes the 1 block above the set back height usable too,
 * but only if the jump stays below it, so the rise after a jump is as low as
 * the gravity check allows: within its friction envelope while that is
 * strict, then capped at the top once moving up less is allowed (below 0.2
 * per move).
 */
public final class RewoundFlight
{
	// gravity limits from NoCheatPlus' Magic class
	private static final double GRAVITY_MIN = 0.0624;
	private static final double GRAVITY_SPAN = 0.0834 - GRAVITY_MIN;
	private static final double AIR_FRICTION = 0.98;
	
	// how close to the set back height counts as near (1, with a margin)
	private static final double NEAR_SET_BACK_RANGE = 0.995;
	
	// rising less than the gravity check expects is fine below this speed
	private static final double SLOW_RISE_SPEED = 0.2;
	
	// small safety margin against rounding differences
	private static final double MARGIN = 1e-4;
	
	// the server only reports moves of 1/16 block or more to NoCheatPlus,
	// and if it sees none for 85 ticks, it flags you for hovering, so when
	// not moving horizontally, sinking has to be faster than 1/16 per 80
	// ticks
	public static final double MIN_GLIDE_SPEED = 0.001;
	
	private boolean controlling;
	private double setBackY = Double.NaN;
	private double lastY;
	private boolean lastYValid;
	
	private double prevY;
	private boolean prevOnGround;
	
	public void reset()
	{
		controlling = false;
		setBackY = Double.NaN;
		lastYValid = false;
	}
	
	/**
	 * Whether this is controlling your movement this tick, in which case
	 * SpeedHack shouldn't.
	 */
	public boolean isControlling()
	{
		return controlling;
	}
	
	/**
	 * How far you can still glide before NoCheatPlus would stop you, or 0.
	 */
	public double getGlideLeft(LocalPlayer player)
	{
		if(Double.isNaN(setBackY))
			return 0;
		
		return Math.max(0, player.getY() - setBackY + NEAR_SET_BACK_RANGE);
	}
	
	/**
	 * Called right before you move. Returns the movement to use instead.
	 *
	 * @param glideSpeed
	 *            How fast to sink while gliding.
	 * @param sinkFaster
	 *            Whether to fall like normal instead of gliding.
	 */
	public Vec3 modifyMove(LocalPlayer player, Vec3 delta, double glideSpeed,
		boolean sinkFaster)
	{
		prevY = player.getY();
		prevOnGround = player.onGround();
		
		controlling = !player.onGround() && lastYValid
			&& SpeedHackHack.canControlMovement(player);
		if(!controlling)
			return delta;
			
		// horizontal: sprinting speed in the direction you're holding
		// (vanilla's air movement would end up slightly faster than that)
		double x = delta.x;
		double z = delta.z;
		double maxSpeed = SpeedHackHack.getNcpBaseSpeed(player) - MARGIN;
		Vec3 dir = SpeedHackHack.getInputDirection(player);
		if(dir != null)
		{
			x = dir.x * maxSpeed;
			z = dir.z * maxSpeed;
			
		}else if(delta.horizontalDistance() > maxSpeed)
		{
			double scale = maxSpeed / delta.horizontalDistance();
			x *= scale;
			z *= scale;
		}
		
		double y = getAirY(player, glideSpeed, sinkFaster);
		Vec3 move = new Vec3(x, y, z);
		player.setDeltaMovement(move);
		return move;
	}
	
	/**
	 * Called right after you moved. Keeps track of where NoCheatPlus would
	 * set you back to, which is where you left the ground or landed.
	 */
	public void onMoved(LocalPlayer player)
	{
		if(player.onGround())
			setBackY = player.getY();
		else if(prevOnGround)
			setBackY = prevY;
		
		lastY = player.getY() - prevY;
		lastYValid = true;
	}
	
	private double getAirY(LocalPlayer player, double glideSpeed,
		boolean sinkFaster)
	{
		double relY = player.getY() - setBackY;
		boolean nearSetBack =
			!Double.isNaN(setBackY) && Math.abs(relY) < NEAR_SET_BACK_RANGE;
		
		// the gravity check's friction envelope: from this down to
		// GRAVITY_SPAN below it
		double gravityY = lastY * AIR_FRICTION - GRAVITY_MIN;
		double lowestY = gravityY - GRAVITY_SPAN + MARGIN;
		
		// still rising after a jump
		if(lastY > 0 && gravityY > 0)
		{
			// rise as little as allowed, so the top stays near the set back
			if(gravityY > SLOW_RISE_SPEED)
				return lowestY;
			
			// rising less is allowed now, so stop just below the limit
			double room = NEAR_SET_BACK_RANGE - MARGIN - relY;
			if(nearSetBack && room > MARGIN)
				return Math.min(gravityY - MARGIN, room);
		}
		
		// glide
		if(nearSetBack && !sinkFaster)
			return -Math.max(glideSpeed, MIN_GLIDE_SPEED);
		
		// fall like normal, within the envelope
		double vanillaY = (lastY - 0.08) * AIR_FRICTION;
		return Math.max(Math.min(vanillaY, gravityY - MARGIN), lowestY);
	}
}
