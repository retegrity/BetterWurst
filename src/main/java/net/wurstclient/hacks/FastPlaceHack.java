/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Random;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"fast place"})
public final class FastPlaceHack extends Hack implements UpdateListener
{
	// Minecraft.startUseItem() waits this many ticks before the next use, so
	// vanilla uses an item every 4th tick
	private static final int VANILLA_DELAY = 4;
	
	private final CheckboxSetting fastPlacement = new CheckboxSetting(
		"Fast placement speed", "Places blocks faster.", true);
	
	private final CheckboxSetting fastItemUse =
		new CheckboxSetting("Fast item use speed",
			"Uses items like XP bottles, potions and fireworks faster.", true);
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks to wait between uses. Vanilla waits 3 ticks.", 0, 0,
		VANILLA_DELAY - 2, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	// The server can't see whether you are holding right click, but some
	// anti-cheats might notice gaps between uses that never change.
	private final CheckboxSetting randomize =
		new CheckboxSetting("Randomize delay", "Sometimes waits an extra tick,"
			+ " so it looks more like clicking quickly.", false);
	
	private final SliderSetting startDelay = new SliderSetting("Start delay",
		"How long to hold right click before FastPlace kicks in. Prevents"
			+ " placing 2 blocks by accident.",
		0, 0, 5, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final Random random = new Random();
	private int heldTicks;
	
	public FastPlaceHack()
	{
		super("FastPlace");
		setCategory(Category.BLOCKS);
		addSetting(fastPlacement);
		addSetting(fastItemUse);
		addSetting(delay);
		addSetting(randomize);
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
		
		CheckboxSetting setting =
			isPlacingBlock() ? fastPlacement : fastItemUse;
		if(!setting.isChecked())
			return;
			
		// The game counts rightClickDelay down before it checks the use key,
		// which happens before this, so a value of N means waiting N - 1
		// ticks.
		int maxDelay = delay.getValueI() + 1;
		if(MC.rightClickDelay <= maxDelay)
			return;
		
		// only reached right after a use, so every use gets one roll
		if(randomize.isChecked() && random.nextBoolean())
			maxDelay++;
		
		MC.rightClickDelay = Math.min(MC.rightClickDelay, maxDelay);
	}
	
	/**
	 * Whether the next right click places a block, judging by the item that
	 * vanilla tries first (main hand, or off hand if the main hand is empty).
	 */
	private boolean isPlacingBlock()
	{
		ItemStack stack = MC.player.getMainHandItem();
		if(stack.isEmpty())
			stack = MC.player.getOffhandItem();
		
		return stack.getItem() instanceof BlockItem;
	}
}
