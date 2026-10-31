/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"force push", "paralyze"})
public final class ForcePushHack extends Hack implements UpdateListener
{
	public ForcePushHack()
	{
		super("ForcePush");
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
		if(!MC.player.onGround() || !isMobNearby())
			return;
		
		// 1.8 servers tick the player once per movement packet
		for(int i = 0; i < 1000; i++)
			MC.player.connection
				.send(new ServerboundMovePlayerPacket.StatusOnly(true,
					MC.player.horizontalCollision));
	}
	
	private boolean isMobNearby()
	{
		return !MC.level
			.getEntities(MC.player, MC.player.getBoundingBox().inflate(1),
				e -> e instanceof LivingEntity && e.isAlive()
					&& !WURST.getFriends().isFriend(e)
					&& MC.player.distanceToSqr(e) <= 1)
			.isEmpty();
	}
}
