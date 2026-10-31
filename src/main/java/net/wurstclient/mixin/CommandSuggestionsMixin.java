/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.command.CmdSuggestions;

@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin
{
	@Shadow
	@Final
	private Minecraft minecraft;
	
	@Shadow
	@Final
	private EditBox input;
	
	@Shadow
	@Final
	private boolean commandsOnly;
	
	@Shadow
	@Final
	private List<FormattedCharSequence> commandUsage;
	
	@Shadow
	private int commandUsagePosition;
	
	@Shadow
	private CompletableFuture<Suggestions> pendingSuggestions;
	
	@Shadow
	private CommandSuggestions.SuggestionsList suggestions;
	
	@Shadow
	private boolean keepSuggestions;
	
	/**
	 * Suggests Wurst commands and their arguments when the chat input starts
	 * with ".". This runs right after vanilla has cleared the old suggestions
	 * and before it would treat the input as a chat message.
	 */
	@Inject(method = "updateCommandInfo()V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/brigadier/StringReader;canRead()Z",
			ordinal = 0,
			remap = false),
		cancellable = true)
	private void onUpdateCommandInfo(CallbackInfo ci)
	{
		// not in command blocks
		if(commandsOnly)
			return;
		
		String text = input.getValue().substring(0, input.getCursorPosition());
		CompletableFuture<Suggestions> future =
			CmdSuggestions.getSuggestions(text);
		if(future == null)
			return;
		
		ci.cancel();
		if(suggestions != null && keepSuggestions)
			return;
		
		pendingSuggestions = future;
		future.thenAccept(result -> {
			if(pendingSuggestions != future)
				return;
			
			if(result.isEmpty())
			{
				// nothing to suggest, so show the command's syntax instead
				for(String line : CmdSuggestions.getUsage(text))
					commandUsage.add(Component.literal(line)
						.withStyle(CommandSuggestions.USAGE_FORMAT)
						.getVisualOrderText());
				commandUsagePosition = 0;
				recomputeUsageBoxWidth();
				
			}else if(minecraft.options.autoSuggestions().get())
				showSuggestions(false);
		});
	}
	
	@Shadow
	public abstract void showSuggestions(boolean narrateFirstSuggestion);
	
	@Shadow
	protected abstract void recomputeUsageBoxWidth();
}
