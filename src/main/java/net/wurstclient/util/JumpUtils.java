/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.mixin.LivingEntityAccessor;

/**
 * Jumping by changing the jump key that the game reads, instead of calling
 * {@code jumpFromGround()}. That way the game jumps by itself and the server
 * sees the jump key in the player's input, like with a real player.
 * Anti-cheats like Grim don't allow jumps without it.
 */
public enum JumpUtils
{
	;
	
	private static boolean jumpRequested;
	private static boolean skipDelayRequested;
	
	/**
	 * Presses jump in the next tick. Call it in onUpdate().
	 */
	public static void jump()
	{
		jumpRequested = true;
	}
	
	/**
	 * Lets the next jump happen as soon as possible, without vanilla's
	 * 10-tick jump delay. Call it in onUpdate() in every tick where it
	 * should apply.
	 */
	public static void skipJumpDelay()
	{
		skipDelayRequested = true;
	}
	
	/**
	 * Decides whether the jump key is down in this tick. Called from
	 * LocalPlayerMixin right after the game reads the keys.
	 */
	public static boolean modifyJumpKey(LocalPlayer player, boolean jump)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		boolean skipDelay =
			skipDelayRequested || hax.noJumpDelayHack.isEnabled();
		boolean requested = jumpRequested;
		jumpRequested = false;
		skipDelayRequested = false;
		
		if(requested || hax.bunnyHopHack.shouldJump(player))
			jump = true;
		
		if(jump && skipDelay && shouldReleaseJump(player))
			return false;
		
		return jump;
	}
	
	/**
	 * Vanilla only resets the jump delay when the jump key isn't held, so a
	 * player who spams space can jump again right when landing, but not one
	 * who holds it. Letting go for one tick in the air resets it the same
	 * way, so the next jump comes just as early, but the input looks like
	 * someone spamming space.
	 */
	private static boolean shouldReleaseJump(LocalPlayer player)
	{
		if(((LivingEntityAccessor)player).wurst_getNoJumpDelay() <= 0)
			return false;
		
		// letting go would stop swimming up for a tick
		if(player.isInWater() || player.isInLava())
			return false;
		
		// pressing jump again would toggle creative flight
		if(player.getAbilities().mayfly)
			return false;
			
		// pressing jump again in the air would open an elytra, so wait for
		// the ground (that's one tick later)
		if(!player.onGround() && canGlide(player))
			return false;
		
		return true;
	}
	
	private static boolean canGlide(LocalPlayer player)
	{
		for(EquipmentSlot slot : EquipmentSlot.VALUES)
			if(LivingEntity.canGlideUsing(player.getItemBySlot(slot), slot))
				return true;
			
		return false;
	}
}
