/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.InventoryMenu;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.hack.Hack;

/**
 * Lets you keep items in the crafting grid of your inventory.
 *
 * <p>
 * Your own inventory has no "open" packet, and the server only moves the
 * items in its crafting grid back into your inventory when it gets the
 * "close" packet for it. So this just doesn't send that packet, and to the
 * server it looks like you never closed your inventory.
 *
 * <p>
 * The crafting grid isn't saved with your player, so like in vanilla,
 * anything left in it drops on the ground when you leave the server or die.
 * Turning this off puts the items back into your inventory (unless another
 * container is open, since the server would close that one instead).
 */
@SearchTags({"x carry", "ExtraCarry", "extra carry", "MoreCarry", "more carry",
	"CraftingCarry", "crafting carry"})
public final class XCarryHack extends Hack implements PacketOutputListener
{
	public XCarryHack()
	{
		super("XCarry");
		setCategory(Category.ITEMS);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		
		// put the items back into your inventory
		LocalPlayer player = MC.player;
		if(player == null || MC.getConnection() == null
			|| player.containerMenu != player.inventoryMenu
			|| !hasItemsInCraftingGrid(player))
			return;
		
		MC.getConnection().send(new ServerboundContainerClosePacket(
			player.inventoryMenu.containerId));
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(isInventoryClose(event.getPacket()))
			event.cancel();
	}
	
	/**
	 * Whether the given packet closes your own inventory, which XCarry
	 * doesn't send while enabled.
	 */
	public boolean isInventoryClose(Packet<?> packet)
	{
		LocalPlayer player = MC.player;
		return isEnabled() && player != null
			&& packet instanceof ServerboundContainerClosePacket close
			&& close.getContainerId() == player.inventoryMenu.containerId;
	}
	
	private boolean hasItemsInCraftingGrid(LocalPlayer player)
	{
		InventoryMenu menu = player.inventoryMenu;
		for(int i =
			InventoryMenu.CRAFT_SLOT_START; i < InventoryMenu.CRAFT_SLOT_END; i++)
			if(!menu.getSlot(i).getItem().isEmpty())
				return true;
			
		return false;
	}
}
