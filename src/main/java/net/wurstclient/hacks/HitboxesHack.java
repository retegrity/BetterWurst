/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.EntityUtils;

@SearchTags({"hit boxes", "HitboxExpand", "hitbox expand", "BiggerHitboxes",
	"bigger hitboxes", "Expand"})
public final class HitboxesHack extends Hack
{
	private final SliderSetting size = new SliderSetting("Size",
		"How many times bigger the hitboxes of entities are when aiming at"
			+ " them.\n\n"
			+ "The server still checks your hits against the real hitbox, so"
			+ " entities further away than your reach can't be hit.",
		1.5, 1, 10, 0.1, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericMeleeCombat();
	
	private boolean picking;
	
	public HitboxesHack()
	{
		super("Hitboxes");
		setCategory(Category.COMBAT);
		addSetting(size);
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + " [" + size.getValueString() + "]";
	}
	
	/**
	 * Runs vanilla's search for the entity under the crosshair, with the
	 * bigger hitboxes. Called from hitboxes.LocalPlayerMixin.
	 */
	public <T> T pick(Supplier<T> search)
	{
		picking = true;
		try
		{
			return search.get();
			
		}finally
		{
			picking = false;
		}
	}
	
	/**
	 * How much further than vanilla to look for entities, so that ones whose
	 * bigger hitbox reaches the crosshair are found. Enough for hitboxes up
	 * to 4 blocks wide.
	 */
	public double getSearchExpansion()
	{
		return 2 * (size.getValue() - 1);
	}
	
	/**
	 * The hitbox to aim at for this entity: bigger while picking, otherwise
	 * the real one. Called from hitboxes.ProjectileUtilMixin.
	 */
	public AABB getPickBox(Entity entity, AABB box)
	{
		// singleplayer's server uses the same code for projectiles
		if(!picking || !MC.isSameThread())
			return box;
		
		return getBiggerBox(entity, box);
	}
	
	/**
	 * The bigger hitbox of this entity, or the real one if Hitboxes doesn't
	 * affect it. Also shown with F3+B, see
	 * hitboxes.EntityHitboxDebugRendererMixin.
	 */
	public AABB getBiggerBox(Entity entity, AABB box)
	{
		if(!shouldExpand(entity))
			return box;
		
		double extra = size.getValue() - 1;
		return box.inflate(box.getXsize() * extra / 2,
			box.getYsize() * extra / 2, box.getZsize() * extra / 2);
	}
	
	private boolean shouldExpand(Entity entity)
	{
		return EntityUtils.IS_ATTACKABLE_MELEE.test(entity)
			&& entityFilters.testOne(entity);
	}
	
	// See hitboxes.LocalPlayerMixin, hitboxes.ProjectileUtilMixin and
	// hitboxes.EntityHitboxDebugRendererMixin
}
