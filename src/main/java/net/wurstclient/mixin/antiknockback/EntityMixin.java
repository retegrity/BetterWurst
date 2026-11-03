/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.antiknockback;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(Entity.class)
public abstract class EntityMixin
{
	/**
	 * Applies AntiKnockback's settings to regular knockback for the local
	 * player. Delay mode cancels it and applies it later.
	 */
	@Inject(method = "lerpMotion(Lnet/minecraft/world/phys/Vec3;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onLerpMotion(Vec3 vec, CallbackInfo ci,
		@Local(argsOnly = true) LocalRef<Vec3> vecRef)
	{
		if(!((Object)this instanceof LocalPlayer player)
			|| player != WurstClient.MC.player)
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		boolean fromHit = hax.jumpResetHack.isHitKnockback(player);
		if(hax.antiKnockbackHack.delayKnockback(vec, fromHit))
		{
			ci.cancel();
			return;
		}
		
		Vec3 knockback = hax.antiKnockbackHack.modifyKnockback(vec);
		vecRef.set(knockback);
		if(fromHit)
			hax.jumpResetHack.onKnockback(knockback);
	}
}
