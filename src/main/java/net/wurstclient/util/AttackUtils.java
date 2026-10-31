/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.wurstclient.WurstClient;

public enum AttackUtils
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	
	/**
	 * Attacks the given entity and swings. 1.8 clients swing before
	 * attacking, newer ones after attacking, and 1.8 anti-cheats (Grim's
	 * PacketOrderB, Vulcan's BadPackets) flag the wrong order. ViaFabricPlus
	 * only fixes this for normal mouse clicks, so hacks have to do it here.
	 */
	public static void attackAndSwing(Entity target, Runnable swing)
	{
		if(ViaFabricPlusCompat.isOneEightOrOlder())
		{
			swing.run();
			MC.gameMode.attack(MC.player, target);
			return;
		}
		
		MC.gameMode.attack(MC.player, target);
		swing.run();
	}
	
	/**
	 * Returns true if combat hacks should wait this tick, e.g. because
	 * BlockHit is blocking or AutoTotem is moving a totem.
	 */
	public static boolean shouldDelayAttack()
	{
		return WurstClient.INSTANCE.getHax().blockHitHack.shouldDelayAttack()
			|| WurstClient.INSTANCE.getHax().autoTotemHack.isBusy();
	}
	
	/**
	 * For hacks that attack several entities and only swing once afterwards.
	 * Returns true if they should swing before each attack instead (1.8).
	 */
	public static boolean swingBeforeEachAttack()
	{
		return ViaFabricPlusCompat.isOneEightOrOlder();
	}
}
