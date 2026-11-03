/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.reach;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.player.Player;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.ReachHack;

@Mixin(Player.class)
public abstract class PlayerMixin
{
	@ModifyReturnValue(method = "blockInteractionRange()D", at = @At("RETURN"))
	private double modifyBlockInteractionRange(double original)
	{
		ReachHack reach = getReach();
		return reach == null ? original : reach.getBlockReach();
	}
	
	@ModifyReturnValue(method = "entityInteractionRange()D", at = @At("RETURN"))
	private double modifyEntityInteractionRange(double original)
	{
		ReachHack reach = getReach();
		return reach == null ? original : reach.getEntityReach();
	}
	
	/**
	 * Returns Reach if it should change this player's range, otherwise null.
	 */
	@Unique
	private ReachHack getReach()
	{
		if((Object)this != WurstClient.MC.player)
			return null;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.reachHack.isEnabled())
			return null;
		
		return hax.reachHack;
	}
}
