/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"air stuck", "AirFreeze", "air freeze", "Freeze", "AntiFall",
	"anti fall"})
@DontSaveState
public final class AirStuckHack extends Hack
	implements UpdateListener, PreMotionListener, PacketOutputListener
{
	private final CheckboxSetting sendRotations = new CheckboxSetting(
		"Send rotations",
		"Keeps telling the server where you are looking while you are frozen,"
			+ " so you can still aim, place blocks and clutch. Your position"
			+ " still isn't sent.\n\n"
			+ "Grim flags this. Vanilla servers kick you for flying after about"
			+ " 4 seconds, unless flying is allowed there.",
		false);
	
	private boolean frozen;
	private Vec3 position;
	private Vec3 velocity;
	
	public AirStuckHack()
	{
		super("AirStuck");
		setCategory(Category.MOVEMENT);
		addSetting(sendRotations);
	}
	
	@Override
	protected void onEnable()
	{
		frozen = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PreMotionListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PreMotionListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		
		// keep falling as if nothing happened
		if(frozen && MC.player != null)
			MC.player.setDeltaMovement(velocity);
		
		frozen = false;
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(!frozen)
		{
			// waits until you are in the air
			if(player.onGround() || player.isPassenger()
				|| player.getAbilities().flying)
				return;
			
			frozen = true;
			position = player.position();
			velocity = player.getDeltaMovement();
		}
		
		// no gravity or movement keys for this tick
		player.setDeltaMovement(Vec3.ZERO);
	}
	
	@Override
	public void onPreMotion()
	{
		if(!frozen)
			return;
		
		// undo whatever moved the player this tick
		LocalPlayer player = MC.player;
		player.setPos(position);
		player.setDeltaMovement(Vec3.ZERO);
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		// The server only moves you when you tell it to. Hovering in place
		// would get you kicked for flying after a few seconds, but sending
		// nothing doesn't, since the last position it got was still falling.
		if(!frozen || !(event
			.getPacket() instanceof ServerboundMovePlayerPacket packet))
			return;
		
		if(!sendRotations.isChecked() || !packet.hasRotation())
		{
			event.cancel();
			return;
		}
		
		// only the rotation, also when vanilla sends the position along
		if(packet.hasPosition())
			event.setPacket(new ServerboundMovePlayerPacket.Rot(
				packet.getYRot(0), packet.getXRot(0), packet.isOnGround(),
				packet.horizontalCollision()));
	}
}
