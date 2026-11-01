/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.lang.reflect.Field;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Probes the client like mod-detecting servers do: an anvil with an item
 * named after a translation key, whose translated name the client copies into
 * the anvil and sends back as a rename.
 */
public final class NoModDetectionTest extends SingleplayerTest
{
	// only exists in the test mod's language file
	private static final String MOD_KEY = "wurst_testmod.nomoddetection.probe";
	private static final String VANILLA_KEY = "container.repair";
	
	public NoModDetectionTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing NoModDetection");
		
		String modText = context
			.computeOnClient(mc -> Component.translatable(MOD_KEY).getString());
		if(modText.equals(MOD_KEY))
			throw new AssertionError(
				"The test mod's translation isn't loaded: " + MOD_KEY);
		
		ServerPlayer player =
			server.computeOnServer(s -> connection.getServerPlayer());
		server.runOnServer(s -> player.openMenu(new SimpleMenuProvider(
			(id, inventory, p) -> new AnvilMenu(id, inventory),
			Component.translatable(VANILLA_KEY))));
		context.waitForScreen(AnvilScreen.class);
		
		// mod translations must stay as raw keys, like on a vanilla client
		String modRename = probe(player, "probe1|", MOD_KEY);
		if(!modRename.equals("probe1|" + MOD_KEY))
			throw new AssertionError(
				"NoModDetection leaked a mod translation through the anvil: "
					+ modRename);
		
		// vanilla translations must still work
		String vanillaText = context.computeOnClient(
			mc -> Component.translatable(VANILLA_KEY).getString());
		String vanillaRename = probe(player, "probe2|", VANILLA_KEY);
		if(!vanillaRename.equals("probe2|" + vanillaText))
			throw new AssertionError(
				"NoModDetection broke vanilla translations in the anvil: "
					+ vanillaRename);
		
		// Clean up
		server.runOnServer(s -> {
			player.containerMenu.getSlot(0).set(ItemStack.EMPTY);
			player.closeContainer();
		});
		context.waitForScreen(null);
	}
	
	/**
	 * Puts an item named <code>prefix + translation of key</code> into the
	 * anvil and returns the name that the client sends back.
	 */
	private String probe(ServerPlayer player, String prefix, String key)
	{
		ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
		stack.set(DataComponents.CUSTOM_NAME,
			Component.literal(prefix).append(Component.translatable(key)));
		server.runOnServer(s -> player.containerMenu.getSlot(0).set(stack));
		
		for(int i = 0; i < 100; i++)
		{
			String name = server.computeOnServer(s -> getItemName(player));
			if(name != null && name.startsWith(prefix))
				return name;
			
			context.waitTick();
		}
		
		throw new AssertionError(
			"The client didn't send an anvil rename for " + prefix);
	}
	
	private static String getItemName(ServerPlayer player)
	{
		if(!(player.containerMenu instanceof AnvilMenu menu))
			return null;
		
		try
		{
			Field field = AnvilMenu.class.getDeclaredField("itemName");
			field.setAccessible(true);
			return (String)field.get(menu);
			
		}catch(ReflectiveOperationException e)
		{
			throw new RuntimeException(e);
		}
	}
}
