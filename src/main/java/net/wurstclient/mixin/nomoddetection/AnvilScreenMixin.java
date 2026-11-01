/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.nomoddetection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;

@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin
{
	/**
	 * The anvil copies the item's name into its text field and sends it back
	 * to the server, which servers use to find out which mods are installed.
	 */
	@WrapOperation(method = {
		"slotChanged(Lnet/minecraft/world/inventory/AbstractContainerMenu;ILnet/minecraft/world/item/ItemStack;)V",
		"onNameChanged(Ljava/lang/String;)V"},
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
	private String wrapGetString(Component component,
		Operation<String> original)
	{
		return WurstClient.INSTANCE.getOtfs().noModDetectionOtf
			.getStringForServer(component);
	}
}
