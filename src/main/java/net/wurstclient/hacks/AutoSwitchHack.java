/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.world.entity.player.Inventory;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto switch"})
public final class AutoSwitchHack extends Hack implements UpdateListener
{
	private final SliderSetting speed = new SliderSetting("Speed",
		"How many times per second to switch to the next slot.", 20, 1, 20, 1,
		ValueDisplay.INTEGER.withSuffix("/s"));
	
	// how many switches are due, so speeds that don't fit evenly into 20
	// ticks are still spread out evenly
	private double progress;
	
	public AutoSwitchHack()
	{
		super("AutoSwitch");
		setCategory(Category.ITEMS);
		addSetting(speed);
	}
	
	@Override
	protected void onEnable()
	{
		progress = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		progress += speed.getValue() / 20;
		if(progress < 1)
			return;
		progress--;
		
		Inventory inventory = MC.player.getInventory();
		int prevSlot = inventory.getSelectedSlot();
		
		if(prevSlot == 8)
			inventory.setSelectedSlot(0);
		else
			inventory.setSelectedSlot(prevSlot + 1);
	}
}
