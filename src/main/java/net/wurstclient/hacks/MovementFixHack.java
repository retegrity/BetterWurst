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
 * Only a toggle. The actual movement correction happens in RotationFaker and
 * the mixins in the rotations package, for every hack that sends a
 * server-side rotation through RotationFaker. Hacks that send extra rotation
 * packets instead (ClickAura, MultiAura, TP-Aura, "Packet spam" face target)
 * aren't covered.
 */
@SearchTags({"movement fix", "MoveFix", "move fix", "movement correction",
	"StrafeFix", "strafe fix"})
public final class MovementFixHack extends Hack
{
	public MovementFixHack()
	{
		super("MovementFix");
		setCategory(Category.MOVEMENT);
	}
}
