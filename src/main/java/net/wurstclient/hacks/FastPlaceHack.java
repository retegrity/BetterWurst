/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"fast place"})
public final class FastPlaceHack extends Hack implements UpdateListener
{
	// Minecraft.startUseItem() waits this many ticks before the next use, so
	// vanilla uses an item every 4th tick
	private static final int VANILLA_DELAY = 4;
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"How many ticks to wait between placing blocks.\n\n"
			+ "0 places a block every tick. Vanilla waits 3 ticks (one block"
			+ " every 4 ticks).",
		0, 0, VANILLA_DELAY - 2, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting startDelay = new SliderSetting("Start delay",
		"How long you have to hold right click before FastPlace starts placing"
			+ " faster. Until then, blocks are placed at the vanilla speed.\n\n"
			+ "Prevents low delays from placing 2 blocks when you only wanted"
			+ " to place one.",
		0, 0, 5, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private int heldTicks;
	
	public FastPlaceHack()
	{
		super("FastPlace");
		setCategory(Category.BLOCKS);
		addSetting(delay);
		addSetting(startDelay);
	}
	
	@Override
	protected void onEnable()
	{
		heldTicks = 0;
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
		if(!MC.options.keyUse.isDown())
		{
			heldTicks = 0;
			return;
		}
		
		heldTicks++;
		if(heldTicks <= startDelay.getValueI())
			return;
			
		// The game counts rightClickDelay down before it checks the use key,
		// which happens before this, so a value of N means waiting N - 1
		// ticks.
		int maxDelay = delay.getValueI() + 1;
		if(MC.rightClickDelay > maxDelay)
			MC.rightClickDelay = maxDelay;
	}
}
