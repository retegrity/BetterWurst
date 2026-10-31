/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.hitboxes;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.HitboxesHack;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin
{
	/**
	 * Makes Hitboxes' bigger hitboxes count when finding the entity under the
	 * crosshair.
	 */
	@WrapOperation(
		method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"))
	private static EntityHitResult onGetEntityHitResult(Entity camera,
		Vec3 from, Vec3 to, AABB searchBox, Predicate<Entity> matching,
		double maxDistanceSq, Operation<EntityHitResult> original)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || !hax.hitboxesHack.isEnabled())
			return original.call(camera, from, to, searchBox, matching,
				maxDistanceSq);
		
		HitboxesHack hitboxes = hax.hitboxesHack;
		AABB biggerSearch = searchBox.inflate(hitboxes.getSearchExpansion());
		return hitboxes.pick(() -> original.call(camera, from, to, biggerSearch,
			matching, maxDistanceSq));
	}
}
