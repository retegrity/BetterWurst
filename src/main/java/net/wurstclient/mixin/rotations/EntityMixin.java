/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.rotations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.wurstclient.RotationFaker;
import net.wurstclient.WurstClient;

@Mixin(Entity.class)
public abstract class EntityMixin
{
	/**
	 * Movement correction: uses the silent rotation's yaw instead of the
	 * camera yaw when applying movement input.
	 */
	@ModifyExpressionValue(
		method = "moveRelative(FLnet/minecraft/world/phys/Vec3;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
	private float onMoveRelativeYaw(float original)
	{
		if(!((Object)this instanceof LocalPlayer))
			return original;
		
		RotationFaker faker = WurstClient.INSTANCE.getRotationFaker();
		return faker.isMovementCorrectionActive() ? faker.getManagedYaw()
			: original;
	}
}
