/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"RapidFire", "BowSpam", "fast bow", "rapid fire", "bow spam"})
public final class FastBowHack extends Hack implements UpdateListener
{
	public FastBowHack()
	{
		super("FastBow");
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
		if(!MC.options.keyUse.isDown())
			return;
		
		// check fly-kick
		if(!MC.player.onGround() && !MC.player.getAbilities().instabuild)
			return;
		
		if(MC.player.getHealth() <= 0)
			return;
		
		if(!MC.player.getMainHandItem().is(Items.BOW))
			return;
			
		// start drawing the bow, let the 1.8 server tick it 20 times, then
		// shoot it at full power
		MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
		for(int i = 0; i < 20; i++)
			MC.player.connection
				.send(new ServerboundMovePlayerPacket.StatusOnly(false,
					MC.player.horizontalCollision));
		MC.gameMode.releaseUsingItem(MC.player);
	}
}
