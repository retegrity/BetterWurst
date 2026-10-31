/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

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
			initialized = true;
			return;
		}
		
		prevYaw = yaw;
		prevPitch = pitch;
		
		// unwrap so interpolation takes the short way around 360°
		yaw = prevYaw + Mth.wrapDegrees(targetYaw - prevYaw);
		pitch = targetPitch;
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
