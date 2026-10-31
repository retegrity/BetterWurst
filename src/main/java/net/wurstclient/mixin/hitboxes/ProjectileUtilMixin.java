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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin
{
	/**
	 * Uses the bigger hitboxes from Hitboxes while the crosshair target is
	 * being searched.
	 */
	@WrapOperation(
		method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
	private static AABB onGetBoundingBox(Entity entity,
		Operation<AABB> original)
	{
		AABB box = original.call(entity);
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.hitboxesHack.isEnabled())
			return box;
		
		return hax.hitboxesHack.getPickBox(entity, box);
	}
}
