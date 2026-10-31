/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.BlockBreakingProgressListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;

@SearchTags({"FastMine", "SpeedMine", "SpeedyGonzales", "fast break",
	"fast mine", "speed mine", "speedy gonzales", "NoBreakDelay",
	"no break delay"})
public final class FastBreakHack extends Hack
	implements UpdateListener, BlockBreakingProgressListener
{
	private final SliderSetting speed =
		new SliderSetting("Speed", "Speed multiplier for Normal mode.", 2, 1, 5,
			0.05, ValueDisplay.DECIMAL);
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lNormal\u00a7r mode breaks blocks"
			+ " faster by the multiplier set with the Speed slider.\n"
			+ "\u00a7lInstant\u00a7r mode breaks blocks instantly.\n"
			+ "\u00a7lLegit\u00a7r mode only removes the delay between"
			+ " breaking blocks, without speeding up the breaking process"
			+ " itself. This is much slower, but great at bypassing"
			+ " anti-cheat plugins.",
		Mode.values(), Mode.INSTANT)
	{
		@Override
		public void setSelected(Mode selected)
		{
			super.setSelected(selected);
			speed.setDisabled(selected != Mode.NORMAL);
		}
	};
	
	public FastBreakHack()
	{
		super("FastBreak");
		setCategory(Category.BLOCKS);
		addSetting(mode);
		addSetting(speed);
		speed.setDisabled(mode.getSelected() != Mode.NORMAL);
	}
	
	@Override
	public String getRenderName()
	{
		if(mode.getSelected() == Mode.LEGIT)
			return getName() + "Legit";
		return getName();
	}
	
	/**
	 * Returns the factor that the block breaking progress per tick should be
	 * multiplied with. Only used in Normal mode.
	 */
	public float getHardnessModifier()
	{
		if(!isEnabled() || mode.getSelected() != Mode.NORMAL)
			return 1;
		
		return speed.getValueF();
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(BlockBreakingProgressListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(BlockBreakingProgressListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		MC.gameMode.destroyDelay = 0;
	}
	
	@Override
	public void onBlockBreakingProgress(BlockBreakingProgressEvent event)
	{
		if(mode.getSelected() != Mode.INSTANT)
			return;
		
		if(MC.gameMode.destroyProgress >= 1)
			return;
		
		BlockPos blockPos = event.getBlockPos();
		
		// Ignore unbreakable blocks to avoid slowdown issue
		if(BlockUtils.isUnbreakable(blockPos))
			return;
		
		Action action = ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
		Direction direction = event.getDirection();
		IMC.getGameMode().sendPlayerActionC2SPacket(action, blockPos,
			direction);
	}
	
	private enum Mode
	{
		NORMAL("Normal"),
		INSTANT("Instant"),
		LEGIT("Legit");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
