/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

/**
 * Releases W for a moment after hitting something, so that you don't run into
 * the target after knocking it back. In this version, you start sprinting
 * again on the next tick anyway if you hold the sprint key, so this is about
 * spacing, not extra knockback.
 */
@SearchTags({"w tap", "W-Tap", "WTapping", "w tapping", "sprint reset",
	"SprintReset"})
public final class WTapHack extends Hack implements PlayerAttacksEntityListener
{
	private final SliderSetting ticks = new SliderSetting("Ticks",
		"How long to release W after a hit.", 2, 1, 5, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final CheckboxSetting onlySprintHits =
		new CheckboxSetting("Only sprint hits",
			"Only releases W after hits that you land while sprinting with a"
				+ " full attack cooldown, which are the ones that knock the"
				+ " target back further and stop your sprint.",
			true);
	
	private int releaseTicks;
	
	public WTapHack()
	{
		super("WTap");
		setCategory(Category.COMBAT);
		addSetting(ticks);
		addSetting(onlySprintHits);
	}
	
	@Override
	protected void onEnable()
	{
		releaseTicks = 0;
		EVENTS.add(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		releaseTicks = 0;
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		LocalPlayer player = MC.player;
		if(!(target instanceof LivingEntity) || !MC.options.keyUp.isDown())
			return;
		
		// checked before the hit, which stops the sprint
		if(onlySprintHits.isChecked() && (!player.isSprinting()
			|| player.getAttackStrengthScale(0.5F) <= 0.9F))
			return;
		
		releaseTicks = ticks.getValueI();
	}
	
	/**
	 * Decides whether W is released in this tick. Called from LocalPlayerMixin
	 * right after the game reads the keys.
	 */
	public boolean shouldReleaseForward()
	{
		if(releaseTicks <= 0)
			return false;
		
		releaseTicks--;
		
		// JumpReset needs the sprint, which releasing W would stop
		return !WURST.getHax().jumpResetHack.isResettingThisTick();
	}
}
