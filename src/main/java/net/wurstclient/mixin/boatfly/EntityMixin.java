/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.boatfly;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(Entity.class)
public abstract class EntityMixin
{
	/**
	 * Removes gravity from the vehicle that BoatFly keeps in the air.
	 */
	@Inject(method = "getGravity()D", at = @At("HEAD"), cancellable = true)
	private void onGetGravity(CallbackInfoReturnable<Double> cir)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax != null && hax.boatFlyHack.isHovering((Entity)(Object)this))
			cir.setReturnValue(0.0);
	}
}
