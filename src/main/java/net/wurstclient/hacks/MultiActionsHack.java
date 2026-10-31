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

/**
 * The actual logic lives in {@code MinecraftMixin}, which asks this hack
 * whether the vanilla "busy" checks should be ignored.
 */
@SearchTags({"multi actions", "multi use", "multitask", "multi tasking",
	"use and attack", "eat while mining", "attack while eating",
	"swing while blocking"})
public final class MultiActionsHack extends Hack
{
	public MultiActionsHack()
	{
		super("MultiActions");
		setCategory(Category.COMBAT);
	}
}
