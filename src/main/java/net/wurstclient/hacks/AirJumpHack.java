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
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"air jump", "InfiniteJump", "infinite jump", "MultiJump",
	"multi jump", "DoubleJump", "double jump"})
public final class AirJumpHack extends Hack implements UpdateListener
{
	private boolean wasJumping;
	
	public AirJumpHack()
	{
		super("AirJump");
		setCategory(Category.MOVEMENT);
	}
	
	@Override
	protected void onEnable()
	{
		// so holding jump while enabling it doesn't jump right away
		wasJumping = true;
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
		LocalPlayer player = MC.player;
		boolean jumping = MC.options.keyJump.isDown();
		boolean pressed = jumping && !wasJumping;
		wasJumping = jumping;
		
		// on the ground, it's just a normal jump
		if(!pressed || player.onGround())
			return;
		
		// these have their own way of going up
		if(player.isInWater() || player.isInLava() || player.onClimbable()
			|| player.getAbilities().flying || player.isFallFlying()
			|| player.isPassenger())
			return;
		
		player.jumpFromGround();
	}
}
