/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"server rotations", "silent rotations", "rotation viewer",
	"show rotations"})
public final class RotationsHack extends Hack
	implements UpdateListener, PacketOutputListener
{
	private float prevYaw, prevPitch;
	private float yaw, pitch;
	private float prevBodyYaw, bodyYaw;
	private double lastX, lastZ;
	private float targetYaw, targetPitch;
	private boolean initialized;
	
	public RotationsHack()
	{
		super("Rotations");
		setCategory(Category.RENDER);
	}
	
	@Override
	protected void onEnable()
	{
		initialized = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(event.isCancelled())
			return;
		
		Packet<?> packet = event.getPacket();
		if(!(packet instanceof ServerboundMovePlayerPacket move))
			return;
		
		if(!move.hasRotation())
			return;
		
		targetYaw = move.getYRot(targetYaw);
		targetPitch = move.getXRot(targetPitch);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		
		if(!initialized)
		{
			yaw = prevYaw = targetYaw = MC.player.getYRot();
			pitch = prevPitch = targetPitch = MC.player.getXRot();
			bodyYaw = prevBodyYaw = MC.player.yBodyRot;
			lastX = MC.player.getX();
			lastZ = MC.player.getZ();
			initialized = true;
			return;
		}
		
		prevYaw = yaw;
		prevPitch = pitch;
		
		// unwrap so interpolation takes the short way around 360°
		yaw = prevYaw + Mth.wrapDegrees(targetYaw - prevYaw);
		pitch = targetPitch;
		
		prevBodyYaw = bodyYaw;
		updateBodyYaw();
	}
	
	/**
	 * Turns the body the way vanilla does (LivingEntity.tick() and
	 * tickHeadTurn()), but following the server-side rotation instead of the
	 * camera. Otherwise the body follows the camera, and with a silent
	 * rotation (like Killaura's) the head ends up twisted far away from it,
	 * which isn't what other players see.
	 */
	private void updateBodyYaw()
	{
		LocalPlayer player = MC.player;
		
		// face the direction you're walking in (or away from it, when
		// walking backwards), or where you're looking while swinging
		// (how far you moved last tick, since xo/zo are already reset here)
		double xd = player.getX() - lastX;
		double zd = player.getZ() - lastZ;
		lastX = player.getX();
		lastZ = player.getZ();
		float target = bodyYaw;
		if(xd * xd + zd * zd > 0.0025F)
		{
			float walkDirection =
				(float)Mth.atan2(zd, xd) * Mth.RAD_TO_DEG - 90.0F;
			float diff = Mth.abs(Mth.wrapDegrees(yaw) - walkDirection);
			target = 95.0F < diff && diff < 265.0F ? walkDirection - 180.0F
				: walkDirection;
		}
		
		if(player.attackAnim > 0)
			target = yaw;
		
		bodyYaw += Mth.wrapDegrees(target - bodyYaw) * 0.3F;
		
		// the head can only turn this far away from the body
		float maxHeadRotation = player.isBlocking() ? 15.0F : 50.0F;
		float headDiff = Mth.wrapDegrees(yaw - bodyYaw);
		if(Math.abs(headDiff) > maxHeadRotation)
			bodyYaw += headDiff - Math.signum(headDiff) * maxHeadRotation;
		
		// unwrap so interpolation takes the short way around 360°
		bodyYaw = prevBodyYaw + Mth.wrapDegrees(bodyYaw - prevBodyYaw);
	}
	
	public float getRenderBodyYaw(float partialTicks)
	{
		return Mth.lerp(partialTicks, prevBodyYaw, bodyYaw);
	}
	
	public float getRenderYaw(float partialTicks)
	{
		return Mth.lerp(partialTicks, prevYaw, yaw);
	}
	
	public float getRenderPitch(float partialTicks)
	{
		return Mth.lerp(partialTicks, prevPitch, pitch);
	}
	
	public boolean isReady()
	{
		return isEnabled() && initialized;
	}
}
