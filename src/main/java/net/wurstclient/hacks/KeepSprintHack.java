/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;

/**
 * Keeps you sprinting at full speed when you hit something.
 *
 * <p>
 * A knockback hit (sprinting, or with Knockback) slows the attacker down to
 * 60% and stops their sprint. The client only does this itself for targets
 * that it can hurt on its own side (boats, minecarts, item frames, end
 * crystals, ...) and for spear attacks, since hurting a mob or player only
 * happens on the server. But the server does it too, and then sends you its
 * sprinting flag and your movement speed without the sprinting bonus. In
 * vanilla, that stops your sprint unless you hold the sprint key (or use
 * AutoSprint), which starts it again in the same tick.
 *
 * <p>
 * KeepSprint skips the client's slowdown and keeps your sprint when the
 * server turns it off. The only other way that the server turns it off is a
 * "stop sprinting" packet from your own client. It doesn't send any extra
 * packets, just like vanilla when you hold the sprint key, so the server
 * thinks you aren't sprinting until you start again (the next hit won't have
 * the sprinting knockback until then).
 */
@SearchTags({"keep sprint", "NoSprintReset", "no sprint reset", "NoHitSlowdown",
	"no hit slowdown"})
public final class KeepSprintHack extends Hack
{
	public KeepSprintHack()
	{
		super("KeepSprint");
		setCategory(Category.COMBAT);
	}
	
	/**
	 * Whether to skip the slowdown and sprint stop of a knockback hit by the
	 * given player.
	 */
	public boolean shouldKeepSprint(Player player)
	{
		return isEnabled() && player == MC.player;
	}
	
	/**
	 * Called after the server sent you its sprinting flag or your movement
	 * speed, if you were sprinting before.
	 */
	public void afterServerUpdate()
	{
		LocalPlayer player = MC.player;
		if(!isEnabled() || player == null)
			return;
		
		// also puts the sprinting bonus back onto your movement speed
		player.setSprinting(true);
	}
}
