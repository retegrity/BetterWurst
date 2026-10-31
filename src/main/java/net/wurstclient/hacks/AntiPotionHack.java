/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"NoPotion", "Zoot", "anti potions", "no potions"})
public final class AntiPotionHack extends Hack implements UpdateListener
{
	private static final List<Holder<MobEffect>> BAD_EFFECTS = List.of(
		MobEffects.HUNGER, MobEffects.SLOWNESS, MobEffects.MINING_FATIGUE,
		MobEffects.INSTANT_DAMAGE, MobEffects.NAUSEA, MobEffects.BLINDNESS,
		MobEffects.WEAKNESS, MobEffects.WITHER, MobEffects.POISON);
	
	public AntiPotionHack()
	{
		super("AntiPotion");
		setCategory(Category.RETRO);
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
		if(MC.player.getAbilities().instabuild || !MC.player.onGround())
			return;
		
		if(BAD_EFFECTS.stream().noneMatch(MC.player::hasEffect))
			return;
		
		// 1.8 servers tick the player once per movement packet
		for(int i = 0; i < 1000; i++)
			MC.player.connection
				.send(new ServerboundMovePlayerPacket.StatusOnly(false,
					MC.player.horizontalCollision));
	}
}
