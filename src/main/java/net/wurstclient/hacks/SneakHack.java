/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;

@SearchTags({"AutoSneaking"})
public final class SneakHack extends Hack
	implements PreMotionListener, UpdateListener, PacketOutputListener
{
	private final EnumSetting<SneakMode> mode = new EnumSetting<>("Mode",
		"\u00a7lPacket\u00a7r mode makes it look like you're sneaking without slowing you down.\n"
			+ "\u00a7lLegit\u00a7r mode actually makes you sneak.",
		SneakMode.values(), SneakMode.LEGIT);
	
	private final CheckboxSetting offWhileFlying =
		new CheckboxSetting("Turn off while flying",
			"Automatically disables Legit Sneak while you are flying or using"
				+ " Freecam, so that it doesn't force you to fly down.\n\n"
				+ "Keep in mind that this also means you won't be hidden from"
				+ " other players while doing these things.",
			false);
	
	// whether the input sent to the server says that sneak is pressed
	private boolean packetSneaking;
	
	// Ticks until telling the server again that you are sneaking, after it
	// forgot. Waits a bit after right-clicking an entity, in case it was a
	// vehicle that you are about to get into.
	private int resyncTicks;
	private LocalPlayer lastPlayer;
	
	public SneakHack()
	{
		super("Sneak");
		setCategory(Category.MOVEMENT);
		addSetting(mode);
		addSetting(offWhileFlying);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + mode.getSelected() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		packetSneaking = false;
		resyncTicks = 0;
		lastPlayer = MC.player;
		EVENTS.add(PreMotionListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PreMotionListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		
		switch(mode.getSelected())
		{
			case LEGIT:
			IKeyMapping.get(MC.options.keyShift).resetDownState();
			break;
			
			case PACKET:
			break;
		}
		
		// tell the server the real keys again
		if(packetSneaking)
		{
			packetSneaking = false;
			sendInput();
		}
	}
	
	@Override
	public void onPreMotion()
	{
		IKeyMapping sneakKey = IKeyMapping.get(MC.options.keyShift);
		
		switch(mode.getSelected())
		{
			case LEGIT:
			if(offWhileFlying.isChecked() && isFlying())
				sneakKey.resetDownState();
			else
				sneakKey.setDown(true);
			break;
			
			case PACKET:
			sneakKey.resetDownState();
			break;
		}
	}
	
	/**
	 * Unlike PreMotion, this also runs while you are in a vehicle.
	 */
	@Override
	public void onUpdate()
	{
		// a new player after respawning, which the server doesn't see
		// sneaking yet
		if(MC.player != lastPlayer)
		{
			lastPlayer = MC.player;
			resyncTicks = 1;
		}
		
		// Sneaking in a vehicle makes the server dismount you, so don't
		// tell it that you are sneaking there. Telling it the real keys
		// again also makes sure that pressing sneak is a change that gets
		// sent (and that ViaFabricPlus turns into a sneak packet).
		boolean shouldPacketSneak =
			mode.getSelected() == SneakMode.PACKET && !MC.player.isPassenger();
		
		// Re-sending the same input wouldn't be a change for
		// ViaFabricPlus, so send the real keys first and start sneaking
		// again on the next tick.
		if(resyncTicks > 0 && --resyncTicks == 0)
			shouldPacketSneak = false;
		
		if(shouldPacketSneak != packetSneaking)
		{
			packetSneaking = shouldPacketSneak;
			sendInput();
		}
	}
	
	/**
	 * Since 1.21.2, the server knows that you are sneaking from the sneak
	 * key in the input packet. In Packet mode, that key is pressed in every
	 * input packet, but not in your actual controls, so you sneak for
	 * everyone else without slowing down.
	 */
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!packetSneaking)
			return;
			
		// Right-clicking an entity sets your sneaking on the server to
		// whether you are really sneaking, which is false here.
		if(event.getPacket() instanceof ServerboundInteractPacket)
		{
			resyncTicks = 10;
			return;
		}
		
		if(!(event.getPacket() instanceof ServerboundPlayerInputPacket packet))
			return;
		
		Input input = packet.input();
		if(!input.shift())
			event.setPacket(new ServerboundPlayerInputPacket(withShift(input)));
	}
	
	/**
	 * Sends the current keys right away. Vanilla only sends them when they
	 * change.
	 */
	private void sendInput()
	{
		if(MC.player == null || MC.getConnection() == null)
			return;
		
		MC.getConnection()
			.send(new ServerboundPlayerInputPacket(MC.player.input.keyPresses));
	}
	
	private static Input withShift(Input input)
	{
		return new Input(input.forward(), input.backward(), input.left(),
			input.right(), input.jump(), true, input.sprint());
	}
	
	/**
	 * Whether Legit mode should sneak in this tick. Called from
	 * LocalPlayerMixin right after the game reads the keys. Pressing the
	 * sneak key alone isn't enough, since opening or closing a screen
	 * releases all keys right before the game reads them, which made you
	 * stop sneaking for a tick.
	 */
	public boolean shouldForceSneak()
	{
		if(!isEnabled() || mode.getSelected() != SneakMode.LEGIT)
			return false;
		
		return !offWhileFlying.isChecked() || !isFlying();
	}
	
	private boolean isFlying()
	{
		if(MC.player.getAbilities().flying)
			return true;
		
		if(WURST.getHax().flightHack.isEnabled())
			return true;
		
		if(WURST.getHax().freecamHack.isEnabled())
			return true;
		
		return false;
	}
	
	private enum SneakMode
	{
		PACKET("Packet"),
		LEGIT("Legit");
		
		private final String name;
		
		private SneakMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
