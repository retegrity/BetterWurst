/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.IntConsumer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.FireworkRocketEntityAccessor;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"long firework", "firework duration", "elytra boost",
	"rocket boost"})
public final class LongFireworkHack extends Hack implements UpdateListener
{
	private final SliderSetting length = new SliderSetting("Length",
		"How many times longer than normal your fireworks should boost you.\n"
			+ "1x is the normal duration.",
		2, 1, 10, 0.1, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final Map<Integer, Pending> pending = new HashMap<>();
	
	public LongFireworkHack()
	{
		super("LongFirework");
		setCategory(Category.MOVEMENT);
		addSetting(length);
	}
	
	@Override
	protected void onEnable()
	{
		pending.clear();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		// let go of everything that was being kept around
		pending.forEach((id, p) -> p.remover.accept(id));
		pending.clear();
	}
	
	/**
	 * Called when the server removes an entity. Returns true if the removal
	 * should be postponed because it is one of your own fireworks.
	 */
	public boolean delayRemoval(int id, IntConsumer remover)
	{
		if(!isEnabled() || MC.level == null || MC.player == null)
			return false;
		
		Entity entity = MC.level.getEntity(id);
		if(!(entity instanceof FireworkRocketEntity))
			return false;
		
		FireworkRocketEntityAccessor rocket =
			(FireworkRocketEntityAccessor)entity;
		if(rocket.wurst_getAttachedToEntity() != MC.player)
			return false;
		
		// the rocket already lived for one full duration at this point
		int extraTicks =
			Math.round((length.getValueF() - 1) * rocket.wurst_getLife());
		if(extraTicks <= 0)
			return false;
		
		pending.put(id, new Pending(extraTicks, remover));
		return true;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null)
		{
			pending.clear();
			return;
		}
		
		for(Iterator<Map.Entry<Integer, Pending>> it =
			pending.entrySet().iterator(); it.hasNext();)
		{
			Map.Entry<Integer, Pending> entry = it.next();
			Pending p = entry.getValue();
			
			// stop early if the rocket is gone or you stopped flying
			boolean done = --p.ticksLeft <= 0 || MC.player == null
				|| !MC.player.isFallFlying();
			if(!done)
				continue;
			
			p.remover.accept(entry.getKey());
			it.remove();
		}
	}
	
	private static final class Pending
	{
		private int ticksLeft;
		private final IntConsumer remover;
		
		private Pending(int ticksLeft, IntConsumer remover)
		{
			this.ticksLeft = ticksLeft;
			this.remover = remover;
		}
	}
}
