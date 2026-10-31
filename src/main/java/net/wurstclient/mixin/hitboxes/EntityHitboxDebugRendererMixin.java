/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.hitboxes;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin
{
	/**
	 * Shows the bigger hitboxes from Hitboxes in F3+B, in place of the real
	 * ones.
	 */
	@ModifyExpressionValue(method = "showHitboxes",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
			ordinal = 0))
	private AABB onGetBoundingBox(AABB box,
		@Local(argsOnly = true) Entity entity)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.hitboxesHack.isEnabled())
			return box;
		
		return hax.hitboxesHack.getBiggerBox(entity, box);
	}
}
