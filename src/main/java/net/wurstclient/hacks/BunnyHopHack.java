/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;

@SearchTags({"AutoJump", "BHop", "bunny hop", "auto jump"})
public final class BunnyHopHack extends Hack
{
	private final EnumSetting<JumpIf> jumpIf =
		new EnumSetting<>("Jump if", JumpIf.values(), JumpIf.SPRINTING);
	
	public BunnyHopHack()
	{
		super("BunnyHop");
		setCategory(Category.MOVEMENT);
		addSetting(jumpIf);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + jumpIf.getSelected().name + "]";
	}
	
	/**
	 * Whether BunnyHop holds the jump key in this tick, so the game jumps by
	 * itself and the server sees the jump key, like with a real player.
	 * Called from JumpUtils.
	 */
	public boolean shouldJump(LocalPlayer player)
	{
		if(!isEnabled() || !player.onGround() || player.isShiftKeyDown())
			return false;
		
		return jumpIf.getSelected().condition.test(player);
	}
	
	private enum JumpIf
	{
		SPRINTING("Sprinting",
			p -> p.isSprinting() && (p.zza != 0 || p.xxa != 0)),
		
		WALKING("Walking", p -> p.zza != 0 || p.xxa != 0),
		
		ALWAYS("Always", p -> true);
		
		private final String name;
		private final Predicate<LocalPlayer> condition;
		
		private JumpIf(String name, Predicate<LocalPlayer> condition)
		{
			this.name = name;
			this.condition = condition;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
