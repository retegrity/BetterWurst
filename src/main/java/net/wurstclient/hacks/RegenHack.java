/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"regen", "regeneration"})
public final class RegenHack extends Hack implements UpdateListener
{
	private final SliderSetting speed =
		new SliderSetting("Speed", 100, 10, 1000, 10, ValueDisplay.INTEGER);
	
	private final CheckboxSetting pauseInMidAir =
		new CheckboxSetting("Pause in mid-air", true);
	
	public RegenHack()
	{
		super("Regen");
		setCategory(Category.RETRO);
		addSetting(speed);
		addSetting(pauseInMidAir);
	}
	
	@Override
	protected void onEnable()
	{
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
		if(MC.player.getAbilities().instabuild || MC.player.getHealth() <= 0)
			return;
		
		if(pauseInMidAir.isChecked() && !MC.player.onGround())
			return;
		
		if(MC.player.getFoodData().getFoodLevel() < 18)
			return;
		
		if(MC.player.getHealth() >= MC.player.getMaxHealth())
			return;
		
		// 1.8 servers tick the player once per movement packet
		for(int i = 0; i < speed.getValueI(); i++)
			MC.player.connection
				.send(new ServerboundMovePlayerPacket.StatusOnly(false,
					MC.player.horizontalCollision));
	}
}
