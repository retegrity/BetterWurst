/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import java.util.Random;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.events.PostMotionListener;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

public final class RotationFaker
	implements PreMotionListener, PostMotionListener
{
	private boolean fakeRotation;
	private float serverYaw;
	private float serverPitch;
	private float realYaw;
	private float realPitch;
	private boolean applied;
	
	// a camera rotation to apply after this tick's packet, see turnCamera()
	private boolean cameraTurnQueued;
	private float queuedYaw;
	private float queuedPitch;
	
	// Rotation that stays active for a few ticks, so other players see a
	// stable head direction instead of a rotation that snaps back every tick.
	private boolean managed;
	private float managedYaw;
	private float managedPitch;
	private int managedTicksLeft;
	private boolean movementCorrection;
	
	// when > 0, an expired managed rotation turns back to the camera rotation
	// at this speed (degrees per tick) instead of snapping back
	private float releaseSpeed;
	private boolean releasing;
	
	private final Random random = new Random();
	private float lastTurnYawDelta;
	private float lastTurnPitchDelta;
	
	// Human-like turning (Killaura). The rotation accelerates and slows down
	// smoothly instead of jumping to the target in one tick.
	private boolean human;
	private float humanMaxSpeed;
	private float humanAcceleration;
	
	// the rotation sent in the last movement packets and how much it changed
	private float sentYaw;
	private float sentPitch;
	private float sentYawDelta;
	private float sentPitchDelta;
	
	@Override
	public void onPreMotion()
	{
		LocalPlayer player = WurstClient.MC.player;
		
		float yaw;
		float pitch;
		if(fakeRotation)
		{
			yaw = serverYaw;
			pitch = serverPitch;
			
		}else if(managed)
		{
			yaw = managedYaw;
			pitch = managedPitch;
			
		}else
		{
			trackSentRotation(player.getYRot(), player.getXRot());
			return;
		}
		
		trackSentRotation(yaw, pitch);
		applied = true;
		realYaw = player.getYRot();
		realPitch = player.getXRot();
		player.setYRot(yaw);
		player.setXRot(pitch);
	}
	
	private void trackSentRotation(float yaw, float pitch)
	{
		sentYawDelta = yaw - sentYaw;
		sentPitchDelta = pitch - sentPitch;
		sentYaw = yaw;
		sentPitch = pitch;
	}
	
	@Override
	public void onPostMotion()
	{
		LocalPlayer player = WurstClient.MC.player;
		if(applied)
		{
			player.setYRot(realYaw);
			player.setXRot(realPitch);
			fakeRotation = false;
			applied = false;
		}
		
		if(cameraTurnQueued)
		{
			player.setYRot(queuedYaw);
			player.setXRot(queuedPitch);
			cameraTurnQueued = false;
		}
		
		// Update the managed rotation for the next tick here, after this
		// tick's packet was sent, so that next tick's movement (which happens
		// before PreMotion) and next tick's packet use the same rotation.
		// Otherwise the anti-cheat's movement simulation doesn't match.
		if(!managed)
			return;
		
		if(!releasing && --managedTicksLeft < 0)
		{
			if(human)
			{
				releasing = true;
				updateHumanRelease(player);
				return;
			}
			
			if(releaseSpeed <= 0)
			{
				clearManagedRotation();
				return;
			}
			
			releasing = true;
		}
		
		if(releasing)
			if(human)
				updateHumanRelease(player);
			else
				updateRelease(player);
	}
	
	/**
	 * Turns the managed rotation back towards the camera rotation the same
	 * human-like way and clears it once it is close enough that the last
	 * small step back isn't noticeable.
	 */
	private void updateHumanRelease(LocalPlayer player)
	{
		// not wrapped, see updateRelease()
		float realYaw = player.getYRot();
		float realPitch = player.getXRot();
		float threshold = Math.max(getMouseGcd() * 3, 1);
		if(Math.abs(realYaw - managedYaw) <= threshold
			&& Math.abs(realPitch - managedPitch) <= threshold
			&& Math.abs(sentYawDelta) <= humanAcceleration)
		{
			clearManagedRotation();
			return;
		}
		
		humanTurn(managedYaw, managedPitch, realYaw, realPitch);
	}
	
	/**
	 * Turns the camera at the end of this tick instead of right away. The
	 * game draws the camera (and the hand) between the rotations of the last
	 * tick and this one, but saves the last one at the start of the tick.
	 * Turning before that would make the camera jump once per tick instead of
	 * turning smoothly.
	 */
	public void turnCamera(float yaw, float pitch)
	{
		queuedYaw = yaw;
		queuedPitch = pitch;
		cameraTurnQueued = true;
	}
	
	/**
	 * Lets a human-like managed rotation expire right away, so it turns back
	 * to the camera smoothly. Other managed rotations are cleared instantly.
	 */
	public void releaseManagedRotation()
	{
		if(managed && human)
			managedTicksLeft = 0;
		else
			clearManagedRotation();
	}
	
	/**
	 * Silently turns towards the given point, the way LiquidBounce's linear
	 * rotations do with both turn speeds at 180°: the rotation goes straight
	 * to the target and is corrected for the mouse sensitivity grid. It stays
	 * active for the given number of ticks after the last call.
	 *
	 * @param movementCorrection
	 *            whether the player's movement should be calculated from the
	 *            silent rotation instead of the camera rotation
	 */
	public void faceVectorSilently(Vec3 vec, int ticksUntilReset,
		boolean movementCorrection)
	{
		turnTowards(vec, 180, ticksUntilReset, movementCorrection, 0);
	}
	
	/**
	 * Like {@link #faceVectorSilently(Vec3, int, boolean)}, but turns by at
	 * most the given number of degrees per tick. Once the rotation expires,
	 * it turns back to the camera rotation at the given release speed instead
	 * of snapping back.
	 */
	public void faceVectorSmoothly(Vec3 vec, float maxTurn, int ticksUntilReset,
		boolean movementCorrection, float releaseSpeed)
	{
		turnTowards(vec, maxTurn, ticksUntilReset, movementCorrection,
			releaseSpeed);
	}
	
	/**
	 * Turns towards the given point like a human moving a mouse: the rotation
	 * lags behind the target (it only covers part of the remaining angle each
	 * tick), and its speed changes by at most the given acceleration per tick,
	 * so it never jumps by a large angle in a single tick (Intave's rotation
	 * snap heuristic). Once the rotation expires, it turns back to the camera
	 * the same way.
	 *
	 * @param maxSpeed
	 *            the maximum turn speed in degrees per tick
	 * @param acceleration
	 *            how much the turn speed can change per tick, at most 20
	 */
	public void faceVectorHumanly(Vec3 vec, float maxSpeed, float acceleration,
		int ticksUntilReset, boolean movementCorrection)
	{
		LocalPlayer player = WurstClient.MC.player;
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		float fromYaw = managed ? managedYaw : player.getYRot();
		float fromPitch = managed ? managedPitch : player.getXRot();
		float toYaw = fromYaw + Mth.wrapDegrees(needed.yaw() - fromYaw);
		
		humanMaxSpeed = maxSpeed;
		humanAcceleration = Math.min(acceleration, MAX_HUMAN_ACCELERATION);
		humanTurn(fromYaw, fromPitch, toYaw, needed.pitch());
		
		lastTurnYawDelta = managedYaw - fromYaw;
		lastTurnPitchDelta = managedPitch - fromPitch;
		managedTicksLeft = ticksUntilReset;
		this.movementCorrection = movementCorrection;
		releaseSpeed = 0;
		releasing = false;
		human = true;
		managed = true;
	}
	
	// Intave flags a yaw step of more than 25° right after a tick without
	// any rotation, so the turn speed must never change by more than this.
	private static final float MAX_HUMAN_ACCELERATION = 20;
	
	/**
	 * One tick of the human-like turn. Stores the result in
	 * managedYaw/managedPitch.
	 */
	private void humanTurn(float fromYaw, float fromPitch, float toYaw,
		float toPitch)
	{
		// only cover part of the remaining angle, with a different fraction
		// every tick, so the rotation lags behind the target by a varying
		// amount like a real hand does
		float diffYaw = (toYaw - fromYaw) * (0.3F + random.nextFloat() * 0.2F);
		float diffPitch =
			(toPitch - fromPitch) * (0.2F + random.nextFloat() * 0.2F);
		
		diffYaw = Mth.clamp(diffYaw, -humanMaxSpeed, humanMaxSpeed);
		diffPitch = Mth.clamp(diffPitch, -humanMaxSpeed, humanMaxSpeed);
		
		// limit how much the turn speed changes compared to what the server
		// saw last tick
		// (the last delta can be huge after a teleport, so it's limited too)
		float acc = humanAcceleration * (0.75F + random.nextFloat() * 0.25F);
		float lastYaw = Mth.clamp(sentYawDelta, -humanMaxSpeed, humanMaxSpeed);
		float lastPitch =
			Mth.clamp(sentPitchDelta, -humanMaxSpeed, humanMaxSpeed);
		diffYaw = Mth.clamp(diffYaw, lastYaw - acc, lastYaw + acc);
		diffPitch = Mth.clamp(diffPitch, lastPitch - acc, lastPitch + acc);
		
		float gcd = getMouseGcd();
		diffYaw = Math.round(diffYaw / gcd) * gcd;
		diffPitch = Math.round(diffPitch / gcd) * gcd;
		
		managedYaw = fromYaw + diffYaw;
		managedPitch = Mth.clamp(fromPitch + diffPitch, -90, 90);
	}
	
	private void turnTowards(Vec3 vec, float maxTurn, int ticksUntilReset,
		boolean movementCorrection, float releaseSpeed)
	{
		human = false;
		LocalPlayer player = WurstClient.MC.player;
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		float fromYaw = managed ? managedYaw : player.getYRot();
		float fromPitch = managed ? managedPitch : player.getXRot();
		
		// Vary limited turn speeds a little on every tick. With a fixed speed,
		// every long turn moves by exactly the same amount per tick, and
		// blocks placed in the middle of such turns get flagged by Grim's
		// DuplicateRotPlace check.
		if(maxTurn < 180)
			maxTurn *= 0.85F + random.nextFloat() * 0.3F;
		
		// wrapped, so it takes the shortest way to the target
		float toYaw = fromYaw + Mth.wrapDegrees(needed.yaw() - fromYaw);
		turnFrom(fromYaw, fromPitch, toYaw, needed.pitch(), maxTurn);
		lastTurnYawDelta = managedYaw - fromYaw;
		lastTurnPitchDelta = managedPitch - fromPitch;
		managedTicksLeft = ticksUntilReset;
		this.movementCorrection = movementCorrection;
		this.releaseSpeed = releaseSpeed;
		releasing = false;
		managed = true;
	}
	
	/**
	 * Linear turn with the given limit on each axis, corrected for the mouse
	 * sensitivity grid. Stores the result in managedYaw/managedPitch.
	 */
	private void turnFrom(float fromYaw, float fromPitch, float toYaw,
		float toPitch, float maxTurn)
	{
		float diffYaw = toYaw - fromYaw;
		float diffPitch = toPitch - fromPitch;
		float length = (float)Math.hypot(diffYaw, diffPitch);
		if(length > 1e-5F)
		{
			float maxYaw = Math.abs(diffYaw / length) * maxTurn;
			float maxPitch = Math.abs(diffPitch / length) * maxTurn;
			diffYaw = Mth.clamp(diffYaw, -maxYaw, maxYaw);
			diffPitch = Mth.clamp(diffPitch, -maxPitch, maxPitch);
		}
		
		// fix the GCD of the mouse sensitivity, like a real mouse would
		float gcd = getMouseGcd();
		diffYaw = Math.round(diffYaw / gcd) * gcd;
		diffPitch = Math.round(diffPitch / gcd) * gcd;
		
		managedYaw = fromYaw + diffYaw;
		managedPitch = Mth.clamp(fromPitch + diffPitch, -90, 90);
	}
	
	/**
	 * Turns the managed rotation back towards the camera rotation and clears
	 * it once it has arrived.
	 */
	private void updateRelease(LocalPlayer player)
	{
		// not wrapped, so the yaw ends up at exactly the camera's yaw value
		// and doesn't jump by 360° when the managed rotation is cleared
		// (Grim's AimModulo360 check)
		float gcd = getMouseGcd();
		float realYaw = player.getYRot();
		float realPitch = player.getXRot();
		if(Math.abs(realYaw - managedYaw) <= gcd
			&& Math.abs(realPitch - managedPitch) <= gcd)
		{
			clearManagedRotation();
			return;
		}
		
		turnFrom(managedYaw, managedPitch, realYaw, realPitch, releaseSpeed);
	}
	
	/**
	 * Ends the managed rotation's hold time right away, so it starts turning
	 * back to the camera rotation (at its release speed) after this tick.
	 */
	public void expireManagedRotation()
	{
		if(managed && !releasing)
			managedTicksLeft = 0;
	}
	
	public void clearManagedRotation()
	{
		managed = false;
		human = false;
		releasing = false;
		movementCorrection = false;
	}
	
	/**
	 * The smallest rotation step that vanilla mouse movement can produce with
	 * the current sensitivity setting.
	 */
	public float getMouseGcd()
	{
		double sensitivity =
			WurstClient.MC.options.sensitivity().get() * 0.6 + 0.2;
		float gcd =
			(float)(sensitivity * sensitivity * sensitivity * 8.0) * 0.15F;
		return Math.max(gcd, 1e-4F);
	}
	
	public boolean isMovementCorrectionActive()
	{
		return managed && movementCorrection;
	}
	
	/**
	 * How much the last call to one of the face methods changed the yaw.
	 */
	public float getLastTurnYawDelta()
	{
		return lastTurnYawDelta;
	}
	
	/**
	 * How much the last call to one of the face methods changed the pitch.
	 */
	public float getLastTurnPitchDelta()
	{
		return lastTurnPitchDelta;
	}
	
	public float getManagedYaw()
	{
		return managedYaw;
	}
	
	public void faceVectorPacket(Vec3 vec)
	{
		Rotation needed = RotationUtils.getNeededRotations(vec);
		LocalPlayer player = WurstClient.MC.player;
		
		fakeRotation = true;
		serverYaw =
			RotationUtils.limitAngleChange(player.getYRot(), needed.yaw());
		serverPitch = needed.pitch();
	}
	
	public void faceVectorClient(Vec3 vec)
	{
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		LocalPlayer player = WurstClient.MC.player;
		player.setYRot(
			RotationUtils.limitAngleChange(player.getYRot(), needed.yaw()));
		player.setXRot(needed.pitch());
	}
	
	public void faceVectorClientIgnorePitch(Vec3 vec)
	{
		Rotation needed = RotationUtils.getNeededRotations(vec);
		
		LocalPlayer player = WurstClient.MC.player;
		player.setYRot(
			RotationUtils.limitAngleChange(player.getYRot(), needed.yaw()));
		player.setXRot(0);
	}
	
	/**
	 * Keeps the current managed rotation exactly where it is for the given
	 * number of ticks. Returns false if there is no managed rotation.
	 */
	public boolean holdManagedRotation(int ticksUntilReset)
	{
		if(!managed)
			return false;
		
		managedTicksLeft = ticksUntilReset;
		releasing = false;
		lastTurnYawDelta = 0;
		lastTurnPitchDelta = 0;
		return true;
	}
	
	/**
	 * How much the rotation sent in the last movement packet changed compared
	 * to the one before.
	 */
	/**
	 * The rotation sent in the last movement packet, which is the one the
	 * server checks block breaking and attacks against.
	 */
	public float getSentYaw()
	{
		return sentYaw;
	}
	
	public float getSentPitch()
	{
		return sentPitch;
	}
	
	public float getSentYawDelta()
	{
		return sentYawDelta;
	}
	
	public float getSentPitchDelta()
	{
		return sentPitchDelta;
	}
	
	public float getServerYaw()
	{
		if(fakeRotation)
			return serverYaw;
		
		return managed ? managedYaw : WurstClient.MC.player.getYRot();
	}
	
	public float getServerPitch()
	{
		if(fakeRotation)
			return serverPitch;
		
		return managed ? managedPitch : WurstClient.MC.player.getXRot();
	}
}
