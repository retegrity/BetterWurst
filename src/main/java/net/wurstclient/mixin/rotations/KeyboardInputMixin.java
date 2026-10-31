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

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.wurstclient.RotationFaker;
import net.wurstclient.WurstClient;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin
{
	/**
	 * Silent movement correction: when moving relative to the silent rotation
	 * instead of the camera, the pressed keys are converted so that the player
	 * still walks in the direction they intended (rounded to 8 directions).
	 */
	@ModifyExpressionValue(method = "tick()V",
		at = @At(value = "NEW",
			target = "(ZZZZZZZ)Lnet/minecraft/world/entity/player/Input;"))
	private Input onCreateInput(Input original)
	{
		RotationFaker faker = WurstClient.INSTANCE.getRotationFaker();
		LocalPlayer player = Minecraft.getInstance().player;
		if(player == null || !faker.isMovementCorrectionActive())
			return original;
		
		float forward =
			(original.forward() ? 1 : 0) - (original.backward() ? 1 : 0);
		float sideways = (original.left() ? 1 : 0) - (original.right() ? 1 : 0);
		
		float deltaYaw =
			(player.getYRot() - faker.getManagedYaw()) * Mth.DEG_TO_RAD;
		float sin = Mth.sin(deltaYaw);
		float cos = Mth.cos(deltaYaw);
		
		int newSideways = Math.round(sideways * cos - forward * sin);
		int newForward = Math.round(forward * cos + sideways * sin);
		
		return new Input(newForward > 0, newForward < 0, newSideways > 0,
			newSideways < 0, original.jump(), original.shift(),
			original.sprint());
	}
}
