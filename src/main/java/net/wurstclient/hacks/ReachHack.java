/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"range"})
public final class ReachHack extends Hack
{
	// Vanilla servers check reach from your eyes to the hitbox, with some
	// leeway: 3 + 3 blocks for entities and 4.5 + 1 for blocks in survival.
	private final SliderSetting entityReach = new SliderSetting("Entity reach",
		"How far you can reach entities, to attack them or interact with"
			+ " them.\n\n"
			+ "Vanilla servers don't allow more than 6 blocks in survival.",
		6, 1, 6, 0.05, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting blockReach = new SliderSetting("Block reach",
		"How far you can reach blocks, to break, place or use them.\n\n"
			+ "Vanilla servers don't allow more than 5.5 blocks in survival.",
		5.5, 1, 5.5, 0.05, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	public ReachHack()
	{
		super("Reach");
		setCategory(Category.OTHER);
		addSetting(entityReach);
		addSetting(blockReach);
	}
	
	public double getEntityReach()
	{
		return entityReach.getValue();
	}
	
	public double getBlockReach()
	{
		return blockReach.getValue();
	}
	
	// See LocalPlayerMixin.blockInteractionRange() and
	// LocalPlayerMixin.entityInteractionRange()
}
