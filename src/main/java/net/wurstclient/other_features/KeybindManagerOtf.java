/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.options.KeybindManagerScreen;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"KeybindManager", "keybind manager", "KeybindsManager",
	"keybinds manager"})
@DontBlock
public final class KeybindManagerOtf extends OtherFeature
{
	private final CheckboxSetting inGuis = new CheckboxSetting(
		"Keybinds in GUIs",
		"Lets you use your Wurst keybinds while a screen is open, like the"
			+ " death screen, your inventory, chests or the pause menu.\n\n"
			+ "They still don't work where you type text (chat, signs, books,"
			+ " anvils, the creative search, command blocks), in Minecraft's"
			+ " Controls screen, or in Wurst's own screens.\n\n"
			+ "If a GUI also uses the key (like E or the number keys in a"
			+ " chest), both the GUI and the keybind react to it.",
		false);
	
	public KeybindManagerOtf()
	{
		super("Keybinds",
			"This is just a shortcut to let you open the Keybind Manager from within the GUI. Normally you would go to Wurst Options > Keybinds.");
		addSetting(inGuis);
	}
	
	/**
	 * Whether Wurst keybinds should work while the given screen is open.
	 */
	public boolean canUseKeybindsIn(Screen screen)
	{
		if(screen == null || screen instanceof ClickGuiScreen)
			return true;
		
		if(!inGuis.isChecked() || MC.player == null)
			return false;
		
		// screens where you type, or press keys to bind them
		if(screen instanceof ChatScreen
			|| screen instanceof AbstractSignEditScreen
			|| screen instanceof BookEditScreen
			|| screen instanceof BookViewScreen
			|| screen instanceof AbstractCommandBlockEditScreen
			|| screen instanceof KeyBindsScreen)
			return false;
		
		// Wurst's own screens (Navigator, Keybind Manager, settings, etc.)
		if(screen.getClass().getName().startsWith("net.wurstclient."))
			return false;
		
		// the creative search box is always typed into on its tab
		if(screen instanceof CreativeModeInventoryScreen
			&& CreativeModeInventoryScreen.selectedTab
				.getType() == CreativeModeTab.Type.SEARCH)
			return false;
		
		// any other text box that is being typed into, like an anvil's
		return !isTyping(screen);
	}
	
	private boolean isTyping(ContainerEventHandler parent)
	{
		for(GuiEventListener child : parent.children())
		{
			if(child instanceof EditBox
				|| child instanceof AbstractTextAreaWidget)
			{
				if(child.isFocused())
					return true;
				
				continue;
			}
			
			if(child instanceof ContainerEventHandler container
				&& isTyping(container))
				return true;
		}
		
		return false;
	}
	
	@Override
	public String getPrimaryAction()
	{
		return "Open Keybind Manager";
	}
	
	@Override
	public void doPrimaryAction()
	{
		MC.gui.setScreen(new KeybindManagerScreen(MC.gui.screen()));
	}
}
