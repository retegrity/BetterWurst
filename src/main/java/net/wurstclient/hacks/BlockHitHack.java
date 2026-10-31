/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.tags.ItemTags;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.minecraft.world.entity.Entity;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"block hit", "AutoBlock", "auto block", "sword block"})
public final class BlockHitHack extends Hack
	implements LeftClickListener, PlayerAttacksEntityListener, UpdateListener
{
	private int timer;
	private boolean running;
	
	// ticks until the block has been released (vanilla releases it in the
	// next tick's input handling, after the use key goes up)
	private int releaseTicks;
	
	public BlockHitHack()
	{
		super("BlockHit");
		setCategory(Category.RETRO);
	}
	
	@Override
	protected void onEnable()
	{
		timer = 0;
		running = false;
		releaseTicks = 0;
		EVENTS.add(LeftClickListener.class, this);
		EVENTS.add(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(LeftClickListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		
		// don't leave the use key stuck down
		if(running)
			MC.options.keyUse.setDown(false);
		timer = 0;
		running = false;
		releaseTicks = 0;
	}
	
	@Override
	public void onLeftClick(LeftClickEvent event)
	{
		startBlocking();
	}
	
	// also block after attacks from Killaura, TriggerBot, etc.
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		startBlocking();
	}
	
	private void startBlocking()
	{
		if(!MC.player.getMainHandItem().is(ItemTags.SWORDS))
			return;
		
		if(running || releaseTicks > 0)
			return;
		
		running = true;
		EVENTS.add(UpdateListener.class, this);
	}
	
	/**
	 * Returns true while BlockHit is blocking or releasing the block. Combat
	 * hacks wait during that time, since attacking while blocking gets
	 * flagged by anti-cheats.
	 */
	public boolean shouldDelayAttack()
	{
		return isEnabled() && (running || releaseTicks > 0);
	}
	
	/*
	 * Same as Wurst 6: release the use key, hold it for 2 ticks (vanilla and
	 * ViaFabricPlus then block with the sword), release it again.
	 */
	@Override
	public void onUpdate()
	{
		if(releaseTicks > 0)
		{
			if(--releaseTicks == 0 && !running)
				EVENTS.remove(UpdateListener.class, this);
			return;
		}
		
		switch(timer)
		{
			case 0:
			MC.options.keyUse.setDown(false);
			break;
			
			case 1:
			case 2:
			MC.options.keyUse.setDown(true);
			break;
			
			case 3:
			MC.options.keyUse.setDown(false);
			timer = -1;
			running = false;
			releaseTicks = 1;
			break;
		}
		
		timer++;
	}
}
