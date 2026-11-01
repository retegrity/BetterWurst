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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.NoModDetectionOtf;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin extends Screen
{
	@Shadow
	private SignText text;
	
	@Shadow
	@Final
	private String[] messages;
	
	private AbstractSignEditScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/**
	 * The sign editor starts with the sign's current text and sends it back
	 * to the server when closed, which servers use to find out which mods are
	 * installed.
	 */
	@Inject(
		method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;ZZLnet/minecraft/network/chat/Component;)V",
		at = @At("RETURN"))
	private void onInit(SignBlockEntity sign, boolean isFrontText,
		boolean shouldFilter, Component title, CallbackInfo ci)
	{
		NoModDetectionOtf noModDetection =
			WurstClient.INSTANCE.getOtfs().noModDetectionOtf;
		
		for(int i = 0; i < messages.length; i++)
			messages[i] = noModDetection
				.getStringForServer(text.getMessage(i, shouldFilter));
	}
}
