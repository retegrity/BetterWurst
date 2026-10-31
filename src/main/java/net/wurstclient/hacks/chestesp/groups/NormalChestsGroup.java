/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.chestesp.groups;

import java.awt.Color;
import java.util.stream.Stream;

import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.wurstclient.hacks.chestesp.ChestEspBlockGroup;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.LootrModCompat;

public final class NormalChestsGroup extends ChestEspBlockGroup
{
	private final CheckboxSetting onlyDouble =
		new CheckboxSetting("Only highlight double chests",
			"Stops single chests from being highlighted. Other storage blocks"
				+ " are not affected.",
			false);
	
	@Override
	protected Stream<Setting> getExtraSettings()
	{
		return Stream.of(onlyDouble);
	}
	
	@Override
	protected CheckboxSetting createIncludeSetting()
	{
		return new CheckboxSetting("Include normal chests", true);
	}
	
	@Override
	protected ColorSetting createColorSetting()
	{
		return new ColorSetting("Chest color",
			"Normal chests will be highlighted in this color.", Color.GREEN);
	}
	
	@Override
	public boolean matches(BlockEntity be)
	{
		if(!(be instanceof ChestBlockEntity)
			|| be instanceof TrappedChestBlockEntity
			|| LootrModCompat.isLootrTrappedChest(be))
			return false;
		
		if(onlyDouble.isChecked() && !isDoubleChest(be))
			return false;
		
		return true;
	}
	
	private boolean isDoubleChest(BlockEntity be)
	{
		return be.getBlockState().hasProperty(ChestBlock.TYPE)
			&& be.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE;
	}
}
