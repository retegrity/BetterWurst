/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;

@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen
{
	@Shadow
	@Final
	private ServerData serverData;
	
	private ManageServerScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/**
	 * Replaces the "Server Resource Packs" button with one that also has
	 * PacksBypass's "Bypass" mode.
	 */
	@WrapOperation(method = "init()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/CycleButton$Builder;create(IIIILnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/CycleButton$OnValueChange;)Lnet/minecraft/client/gui/components/CycleButton;"))
	private CycleButton<?> wrapPackStatusButton(CycleButton.Builder<?> builder,
		int x, int y, int width, int height, Component name,
		CycleButton.OnValueChange<?> listener,
		Operation<CycleButton<?>> original)
	{
		return WurstClient.INSTANCE.getOtfs().packsBypassOtf
			.createPackStatusButton(serverData, x, y, width, height, name);
	}
}
