/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"anti knockback", "AntiVelocity", "anti velocity", "NoKnockback",
	"no knockback", "AntiKB", "anti kb", "delay knockback"})
public final class AntiKnockbackHack extends Hack
	implements PacketOutputListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lReduce\u00a7r mode makes knockback weaker.\n"
			+ "\u00a7lDelay\u00a7r mode makes knockback hit you later.",
		Mode.values(), Mode.REDUCE);
	
	private final SliderSetting hStrength =
		new SliderSetting("Horizontal Strength",
			"How far to reduce horizontal knockback.\n"
				+ "-100% = double knockback\n" + "0% = normal knockback\n"
				+ "100% = no knockback\n" + ">100% = reverse knockback",
			1, -1, 2, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting vStrength =
		new SliderSetting("Vertical Strength",
			"How far to reduce vertical knockback.\n"
				+ "-100% = double knockback\n" + "0% = normal knockback\n"
				+ "100% = no knockback\n" + ">100% = reverse knockback",
			1, -1, 2, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"How long \u00a7lDelay\u00a7r mode delays knockback.", 4, 1, 20, 1,
		v -> (int)v + (v == 1 ? " tick" : " ticks") + " (" + (int)v * 50
			+ "ms)");
	
	// Delayed knockback and the pongs sent after it, in the order they
	// happened. Anti-cheats like Grim send a ping right after knockback and
	// expect it to be applied once the pong comes back, so the pongs are
	// held back until the knockback is applied. To the server, it looks like
	// the knockback packet just arrived later.
	private final ArrayDeque<Delayed> queue = new ArrayDeque<>();
	private ClientPacketListener queueConnection;
	private int ticks;
	
	public AntiKnockbackHack()
	{
		super("AntiKnockback");
		setCategory(Category.COMBAT);
		addSetting(mode);
		addSetting(hStrength);
		addSetting(vStrength);
		addSetting(delay);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + mode.getSelected() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		releaseDelayedKnockback();
	}
	
	public Vec3 modifyKnockback(Vec3 original)
	{
		if(!isEnabled() || mode.getSelected() != Mode.REDUCE)
			return original;
		
		double verticalMultiplier = 1 - vStrength.getValue();
		double horizontalMultiplier = 1 - hStrength.getValue();
		
		return original.multiply(horizontalMultiplier, verticalMultiplier,
			horizontalMultiplier);
	}
	
	/**
	 * Holds back the given knockback in Delay mode. Returns false if it
	 * should be applied right away.
	 */
	public boolean delayKnockback(Vec3 velocity, boolean fromHit)
	{
		if(!isEnabled() || mode.getSelected() != Mode.DELAY)
			return false;
		
		if(queue.isEmpty())
			queueConnection = MC.getConnection();
			
		// Knockback that arrives between ticks is normally used by the next
		// tick, which is when this gets released with a delay of 0.
		queue.add(new Delayed(velocity, fromHit, ticks + delay.getValueI() + 1,
			null));
		return true;
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(queue.isEmpty()
			|| !(event.getPacket() instanceof ServerboundPongPacket pong))
			return;
		
		event.cancel();
		queue.add(new Delayed(null, false, 0, pong));
	}
	
	/**
	 * Applies delayed knockback that is due and sends the pongs after it.
	 * Called at the start of every tick, before UpdateEvent, so that
	 * JumpReset can react to it in the same tick.
	 */
	public void releaseDelayedKnockback()
	{
		ticks++;
		if(queue.isEmpty())
			return;
		
		// don't send old pongs to a new server
		if(MC.player == null || MC.getConnection() != queueConnection)
		{
			queue.clear();
			return;
		}
		
		boolean releaseAll = !isEnabled() || mode.getSelected() != Mode.DELAY;
		while(!queue.isEmpty())
		{
			Delayed next = queue.peek();
			if(next.pong() != null)
			{
				// skips the PacketOutputEvent, so it isn't held back again
				queueConnection.getConnection().send(next.pong());
				queue.poll();
				continue;
			}
			
			if(!releaseAll && ticks < next.releaseTick())
				break;
			
			MC.player.setDeltaMovement(next.velocity());
			if(next.fromHit())
				WURST.getHax().jumpResetHack.onKnockback(next.velocity());
			queue.poll();
		}
	}
	
	private record Delayed(Vec3 velocity, boolean fromHit, int releaseTick,
		ServerboundPongPacket pong)
	{}
	
	private enum Mode
	{
		REDUCE("Reduce"),
		DELAY("Delay");
		
		private final String name;
		
		private Mode(String name)
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
