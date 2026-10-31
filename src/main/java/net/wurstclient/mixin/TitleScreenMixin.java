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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.altmanager.screens.AltManagerScreen;
import net.wurstclient.util.WurstTitleScreen;
import net.minecraft.util.Util;
import net.minecraft.client.input.MouseButtonEvent;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen
{
	@Shadow
	@Final
	private static Component COPYRIGHT_TEXT;
	
	private AbstractWidget realmsButton = null;
	private Button altsButton;
	
	private TitleScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/**
	 * Adds the AltManager button to the title screen. This mixin must not
	 * run in demo mode, as the Realms button doesn't exist there.
	 */
	@Inject(method = "createNormalMenuOptions(II)I", at = @At("RETURN"))
	private void onCreateNormalMenuOptions(int y, int spacingY,
		CallbackInfoReturnable<Integer> cir)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		for(AbstractWidget button : Screens.getWidgets(this))
		{
			if(!button.getMessage().getString().equals(I18n.get("menu.online")))
				continue;
			
			realmsButton = button;
			break;
		}
		
		if(realmsButton == null)
			throw new IllegalStateException("Couldn't find realms button!");
		
		// make Realms button smaller
		realmsButton.setWidth(98);
		
		// add AltManager button
		addRenderableWidget(altsButton = Button
			.builder(Component.literal("Alt Manager"),
				b -> minecraft.setScreen(new AltManagerScreen(this,
					WurstClient.INSTANCE.getAltManager())))
			.bounds(width / 2 + 2, realmsButton.getY(), 98, 20).build());
	}
	
	@Inject(method = "tick()V", at = @At("RETURN"))
	private void onTick(CallbackInfo ci)
	{
		if(realmsButton == null || altsButton == null)
			return;
			
		// adjust AltManager button if Realms button has been moved
		// happens when ModMenu is installed
		altsButton.setY(realmsButton.getY());
	}
	
	/**
	 * Stops the multiplayer button being grayed out if the user's Microsoft
	 * account is parental-control'd or banned from online play.
	 */
	@Inject(
		method = "getMultiplayerDisabledReason()Lnet/minecraft/network/chat/Component;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetMultiplayerDisabledReason(
		CallbackInfoReturnable<Component> cir)
	{
		cir.setReturnValue(null);
	}
	
	/**
	 * Hides the vanilla copyright button, since the Wurst 6 style copyright
	 * text is drawn in the top right corner instead.
	 */
	@Inject(method = "init()V", at = @At("RETURN"))
	private void onInit(CallbackInfo ci)
	{
		if(!WurstTitleScreen.isEnabled())
			return;
		
		for(AbstractWidget widget : Screens.getWidgets(this))
			if(widget.getMessage().equals(COPYRIGHT_TEXT))
			{
				widget.visible = false;
				widget.active = false;
			}
	}
	
	/**
	 * Hides the vanilla version text in the bottom left corner.
	 */
	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void onDrawVersionText(GuiGraphicsExtractor graphics, Font font,
		String text, int x, int y, int color, Operation<Void> original)
	{
		if(!WurstTitleScreen.isEnabled())
			original.call(graphics, font, text, x, y, color);
	}
	
	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At("TAIL"))
	private void onExtractRenderState(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float partialTick, CallbackInfo ci)
	{
		if(!WurstTitleScreen.isEnabled())
			return;
		
		WurstTitleScreen.renderTexts(graphics, font, width, height);
	}
	
	@Inject(
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onMouseClicked(MouseButtonEvent event, boolean doubleClick,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(!WurstTitleScreen.isEnabled() || event.button() != 0)
			return;
		
		if(WurstTitleScreen.isWebsiteLink(font, width, height, event.x(),
			event.y()))
		{
			Util.getPlatform().openUri(WurstTitleScreen.WEBSITE_URL);
			cir.setReturnValue(true);
		}
	}
}
