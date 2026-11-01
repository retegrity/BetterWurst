/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.nomoddetection;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import net.wurstclient.WurstClient;

@Mixin(KeybindContents.class)
public abstract class KeybindContentsMixin
{
	@Shadow
	@Final
	private String name;
	
	/**
	 * Shows mod keybinds the way a vanilla client would, which doesn't have
	 * them: as their translated (and so usually raw) name.
	 */
	@ModifyReturnValue(
		method = "getNestedComponent()Lnet/minecraft/network/chat/Component;",
		at = @At("RETURN"))
	private Component onGetNestedComponent(Component original)
	{
		if(!WurstClient.INSTANCE.getOtfs().noModDetectionOtf
			.shouldHideKeybind(name))
			return original;
		
		return Component.translatable(name);
	}
}
