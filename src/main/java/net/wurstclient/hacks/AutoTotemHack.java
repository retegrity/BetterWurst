/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"auto totem", "offhand", "off-hand"})
public final class AutoTotemHack extends Hack implements UpdateListener
{
	private final CheckboxSetting showCounter = new CheckboxSetting(
		"Show totem counter", "Displays the number of totems you have.", true);
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"Amount of ticks to wait before equipping the next totem.", 0, 0, 20, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting health = new SliderSetting("Health",
		"Won't equip a totem until your health reaches this value or falls"
			+ " below it.\n" + "0 = always active",
		0, 0, 10, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts")
			.withLabel(1, "1 heart").withLabel(0, "ignore"));
	
	private final CheckboxSetting openInventory = new CheckboxSetting(
		"Open inventory",
		"When the only totems are in your main inventory (not the hotbar),"
			+ " visually opens your inventory while moving one to your"
			+ " offhand, like a real player would.\n\n"
			+ "Totems in your hotbar are swapped into your offhand with the"
			+ " swap key instead, which doesn't need the inventory.",
		false);
	
	private Stage stage = Stage.IDLE;
	private int swapSlot;
	private int originalSlot;
	private int retries;
	private boolean openedInventory;
	private int totems;
	private int timer;
	private boolean wasTotemInOffhand;
	
	public AutoTotemHack()
	{
		super("AutoTotem");
		setCategory(Category.COMBAT);
		addSetting(showCounter);
		addSetting(delay);
		addSetting(health);
		addSetting(openInventory);
	}
	
	@Override
	public String getRenderName()
	{
		if(!showCounter.isChecked())
			return getName();
		
		if(totems == 1)
			return getName() + " [1 totem]";
		
		return getName() + " [" + totems + " totems]";
	}
	
	@Override
	protected void onEnable()
	{
		stage = Stage.IDLE;
		totems = 0;
		timer = 0;
		wasTotemInOffhand = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(stage == Stage.INVENTORY_PREPARE)
			closeInventory();
		
		if(stage == Stage.INVENTORY_PREPARE || stage == Stage.INVENTORY_RESUME)
			resetMovementKeys();
		
		stage = Stage.IDLE;
	}
	
	/**
	 * Returns true while AutoTotem is moving a totem. Combat hacks wait
	 * during that time, since a real player couldn't attack or place blocks
	 * while doing this.
	 */
	public boolean isBusy()
	{
		return isEnabled() && stage != Stage.IDLE;
	}
	
	@Override
	public void onUpdate()
	{
		if(stage != Stage.IDLE)
		{
			continueSwap();
			return;
		}
		
		countTotems();
		
		if(isTotem(MC.player.getOffhandItem()))
		{
			wasTotemInOffhand = true;
			return;
		}
		
		if(wasTotemInOffhand)
		{
			timer = delay.getValueI();
			wasTotemInOffhand = false;
		}
		
		if(totems <= 0)
			return;
		
		float healthF = health.getValueF();
		if(healthF > 0 && MC.player.getHealth() > healthF * 2F)
			return;
		
		// don't move items while a container is open
		if(MC.gui.screen() instanceof AbstractContainerScreen
			&& !(MC.gui.screen() instanceof InventoryScreen
				|| MC.gui.screen() instanceof CreativeModeInventoryScreen))
			return;
		
		if(timer > 0)
		{
			timer--;
			return;
		}
		
		startSwap();
	}
	
	private void startSwap()
	{
		// totem in the hotbar: select it and press the swap key, which works
		// while moving and doesn't need the inventory
		int hotbarSlot = InventoryUtils.indexOf(this::isTotem, 9);
		if(hotbarSlot != -1)
		{
			swapSlot = hotbarSlot;
			originalSlot = MC.player.getInventory().getSelectedSlot();
			
			// vanilla sends the new slot to the server at the start of the
			// next tick
			MC.player.getInventory().setSelectedSlot(hotbarSlot);
			retries = 0;
			stage = Stage.HOTBAR_SWAP;
			return;
		}
		
		int invSlot = InventoryUtils.indexOf(this::isTotem, 36);
		if(invSlot == -1)
			return;
		
		swapSlot = InventoryUtils.toNetworkSlot(invSlot);
		
		// already in the inventory: just do the click
		if(MC.gui.screen() instanceof InventoryScreen)
		{
			IMC.getGameMode().windowClick_SWAP(swapSlot, 40);
			return;
		}
		
		// stop moving for a tick first, like a real player opening the
		// inventory
		openedInventory = false;
		if(openInventory.isChecked() && MC.gui.screen() == null)
		{
			MC.gui.setScreen(new InventoryScreen(MC.player));
			openedInventory = true;
		}
		
		releaseMovementKeys();
		stage = Stage.INVENTORY_PREPARE;
	}
	
	private void continueSwap()
	{
		switch(stage)
		{
			case HOTBAR_SWAP:
			// another hack (e.g. AutoSword) switched away from the totem,
			// so select it again and wait for vanilla to send the slot
			if(MC.player.getInventory().getSelectedSlot() != swapSlot
				|| !isTotem(MC.player.getInventory().getItem(swapSlot)))
			{
				if(++retries > 3
					|| !isTotem(MC.player.getInventory().getItem(swapSlot)))
				{
					MC.player.getInventory().setSelectedSlot(originalSlot);
					finishSwap();
					break;
				}
				
				MC.player.getInventory().setSelectedSlot(swapSlot);
				break;
			}
			
			// same packet as pressing the swap key
			MC.player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
				BlockPos.ZERO, Direction.DOWN));
			stage = Stage.HOTBAR_SWITCH_BACK;
			break;
			
			case HOTBAR_SWITCH_BACK:
			if(MC.player.getInventory().getSelectedSlot() == swapSlot)
				MC.player.getInventory().setSelectedSlot(originalSlot);
			finishSwap();
			break;
			
			case INVENTORY_PREPARE:
			// keys can get pressed again in the meantime
			releaseMovementKeys();
			IMC.getGameMode().windowClick_SWAP(swapSlot, 40);
			closeInventory();
			stage = Stage.INVENTORY_RESUME;
			break;
			
			case INVENTORY_RESUME:
			resetMovementKeys();
			finishSwap();
			break;
			
			default:
			stage = Stage.IDLE;
			break;
		}
	}
	
	private void finishSwap()
	{
		stage = Stage.IDLE;
		timer = delay.getValueI();
	}
	
	private void closeInventory()
	{
		if(openedInventory && MC.gui.screen() instanceof InventoryScreen screen)
			screen.onClose();
		else
			MC.player.connection.send(new ServerboundContainerClosePacket(
				MC.player.inventoryMenu.containerId));
		
		openedInventory = false;
	}
	
	private void releaseMovementKeys()
	{
		for(KeyMapping key : getMovementKeys())
			key.setDown(false);
		
		MC.player.setSprinting(false);
	}
	
	private void resetMovementKeys()
	{
		for(KeyMapping key : getMovementKeys())
			IKeyMapping.get(key).resetDownState();
	}
	
	private KeyMapping[] getMovementKeys()
	{
		return new KeyMapping[]{MC.options.keyUp, MC.options.keyDown,
			MC.options.keyLeft, MC.options.keyRight, MC.options.keyJump,
			MC.options.keySprint};
	}
	
	private void countTotems()
	{
		totems = InventoryUtils.count(this::isTotem, 40, true);
	}
	
	private boolean isTotem(ItemStack stack)
	{
		return stack.is(Items.TOTEM_OF_UNDYING);
	}
	
	private enum Stage
	{
		IDLE,
		HOTBAR_SWAP,
		HOTBAR_SWITCH_BACK,
		INVENTORY_PREPARE,
		INVENTORY_RESUME;
	}
}
