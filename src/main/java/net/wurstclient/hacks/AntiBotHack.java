/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.FakePlayerEntity;

@SearchTags({"anti bot", "no npc", "ignore npc", "fake players", "npc filter"})
public final class AntiBotHack extends Hack
{
	/**
	 * Width of a normal player's hitbox. It stays the same for all poses
	 * except sleeping and dying.
	 */
	private static final float STANDARD_WIDTH = 0.6F;
	
	private final CheckboxSetting smallHitbox =
		new CheckboxSetting("Also filter players with smaller hitbox",
			"Also ignores players whose hitbox is smaller than the hitbox of a"
				+ " standard player. This catches fake players that are shrunk"
				+ " down on purpose.",
			false);
	
	public AntiBotHack()
	{
		super("AntiBot");
		setCategory(Category.OTHER);
		addSetting(smallHitbox);
	}
	
	/**
	 * Returns true if the given entity is an NPC/fake player that should be
	 * ignored. Real players always have an entry in the tab list, so any
	 * player entity without one is treated as a bot.
	 */
	public boolean isBot(Entity e)
	{
		if(!isEnabled())
			return false;
		
		if(!(e instanceof AbstractClientPlayer player)
			|| e instanceof FakePlayerEntity || e == MC.player)
			return false;
		
		if(smallHitbox.isChecked() && hasSmallHitbox(player))
			return true;
		
		if(MC.getConnection() == null)
			return false;
		
		return MC.getConnection().getPlayerInfo(player.getUUID()) == null;
	}
	
	private boolean hasSmallHitbox(AbstractClientPlayer player)
	{
		// sleeping and dying players legitimately have a much smaller hitbox
		Pose pose = player.getPose();
		if(pose == Pose.SLEEPING || pose == Pose.DYING)
			return false;
		
		return player.getBbWidth() < STANDARD_WIDTH - 0.01F;
	}
}
