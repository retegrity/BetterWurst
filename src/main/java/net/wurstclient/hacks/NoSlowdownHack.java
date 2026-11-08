/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

/**
 * Cancels slowness caused by honey, soul sand and using items.
 *
 * <p>
 * Grim mode: Grim simulates block slowdowns exactly, so those stay. For
 * item use, Grim's prediction also tries "not using an item" every tick,
 * since your item-use state can lag behind on the server, so a tick without
 * the slowdown passes its movement check. Only its NoSlow check notices,
 * and it only flags after two ticks in a row without the slowdown. So in
 * Grim mode, every other tick is slowed down, which averages about 60% of
 * walking speed instead of 20%. Grim also checks sprinting while using an
 * item, but only in water, so in Grim mode you only keep sprinting outside
 * of water.
 */
@SearchTags({"no slowdown", "no slow down"})
public final class NoSlowdownHack extends Hack
{
	// Grim mode: how many ticks before an item finishes by itself it stays
	// slowed
	private static final int END_TICKS = 3;
	
	private final CheckboxSetting grimMode = new CheckboxSetting("Grim mode",
		"description.wurst.setting.noslowdown.grim_mode", false);
	
	public NoSlowdownHack()
	{
		super("NoSlowdown");
		setCategory(Category.MOVEMENT);
		addSetting(grimMode);
	}
	
	/**
	 * Whether to cancel the slowdown from blocks like honey and soul sand.
	 */
	public boolean shouldCancelBlockSlowdown()
	{
		return isEnabled() && !grimMode.isChecked();
	}
	
	/**
	 * Whether to cancel the slowdown from using an item on this tick.
	 */
	public boolean shouldCancelItemSlowdown()
	{
		if(!isEnabled())
			return false;
		
		if(!grimMode.isChecked())
			return true;
		
		LocalPlayer player = MC.player;
		if(player == null || !player.isUsingItem())
			return true;
			
		// Every other tick, counted from when you started using the item, so
		// that the first tick is always slowed. Otherwise, an unslowed first
		// tick could follow the last tick before (also unslowed), which is
		// two in a row for Grim.
		if(player.getTicksUsingItem() % 2 != 0)
			return false;
			
		// Also slowed for the last ticks of items that finish by themselves
		// (like food), since you stop being slowed once your client finishes
		// it, a little before the server does.
		return player.getUseItemRemainingTicks() > END_TICKS;
	}
	
	/**
	 * Whether you can keep sprinting (or start sprinting) while using an
	 * item.
	 */
	public boolean shouldKeepSprinting()
	{
		if(!isEnabled())
			return false;
		
		if(!grimMode.isChecked())
			return true;
		
		LocalPlayer player = MC.player;
		return player != null && !player.isInWater();
	}
}
