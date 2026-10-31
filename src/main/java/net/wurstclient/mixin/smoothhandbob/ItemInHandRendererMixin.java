/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.smoothhandbob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin
{
	/**
	 * Skips the bobbing of food and drinks while eating or drinking, when
	 * SmoothHandBob's "Also include consuming animation" is enabled.
	 */
	@WrapOperation(
		method = "applyEatTransform(Lcom/mojang/blaze3d/vertex/PoseStack;FLnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V",
			ordinal = 0))
	private void onEatBob(PoseStack poseStack, float x, float y, float z,
		Operation<Void> original)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax != null && hax.smoothHandBobHack.isConsumingBobRemoved())
			return;
		
		original.call(poseStack, x, y, z);
	}
}
