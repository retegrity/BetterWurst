/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto soup", "AutoStew", "auto stew"})
public final class AutoSoupHack extends Hack implements HandleInputListener
{
	private static final List<Item> STEWS =
		List.of(Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP);
	
	private final SliderSetting health = new SliderSetting("Health",
		"Eats a soup when your health reaches this value or falls below it.",
		6.5, 0.5, 9.5, 0.5, ValueDisplay.DECIMAL);
	
	private int oldSlot = -1;
	private int lastActionTick = -1;
	
	public AutoSoupHack()
	{
		super("AutoSoup");
		
		setCategory(Category.COMBAT);
		addSetting(health);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoEatHack.setEnabled(false);
		EVENTS.add(HandleInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(HandleInputListener.class, this);
		stopIfEating();
	}
	
	/**
	 * Runs right before the game handles the use key, so a finished soup is
	 * replaced before vanilla can right-click the empty bowl. That click and
	 * then switching slots in the same tick would flag Grim's PacketOrderE.
	 */
	@Override
	public void onHandleInput()
	{
		LocalPlayer player = MC.player;
		
		if(canClickInventory())
			sortBowls();
		
		// search soup in hotbar
		int soupInHotbar = findSoup(0, 9);
		
		// check if any soup was found
		if(soupInHotbar != -1)
		{
			// check if player should eat soup
			if(!shouldEatSoup())
			{
				stopIfEating();
				return;
			}
			
			// save old slot
			if(oldSlot == -1)
				oldSlot = player.getInventory().getSelectedSlot();
			
			// keep holding the use key, or vanilla stops eating
			MC.options.keyUse.setDown(true);
			
			// vanilla would also stop using an item that was replaced,
			// e.g. by a server that makes soups instant, later this tick
			if(player.isUsingItem() && !ItemStack.isSameItem(
				player.getItemInHand(player.getUsedItemHand()),
				player.getUseItem()))
				player.stopUsingItem();
			
			if(player.isUsingItem())
			{
				// already eating, so don't send another use packet
				if(player.getUsedItemHand() == InteractionHand.MAIN_HAND
					&& isSoup(player.getMainHandItem()))
					return;
					
				// using something else, e.g. a shield, so let go of it
				// first. Using right after that in the same tick would
				// flag Grim's PacketOrderI.
				MC.gameMode.releaseUsingItem(player);
				lastActionTick = player.tickCount;
				return;
			}
			
			// set slot and eat soup, which sends the slot first
			player.getInventory().setSelectedSlot(soupInHotbar);
			IMC.getGameMode().rightClickItem();
			lastActionTick = player.tickCount;
			
			// like a vanilla right-click, so vanilla doesn't send another
			// one this tick if the soup can't be eaten (e.g. full hunger)
			MC.rightClickDelay = 4;
			return;
		}
		
		stopIfEating();
		
		// search soup in inventory
		int soupInInventory = findSoup(9, 36);
		
		// move soup in inventory to hotbar
		if(soupInInventory != -1 && canClickInventory())
			IMC.getGameMode().windowClick_QUICK_MOVE(soupInInventory);
	}
	
	/**
	 * Returns true while AutoSoup is eating or about to eat, so combat hacks
	 * don't attack in the same tick. Attacking while eating flags Grim's
	 * MultiActionsA and switching slots after an attack flags PacketOrderE.
	 */
	public boolean isBusy()
	{
		if(!isEnabled() || MC.player == null)
			return false;
		
		return oldSlot != -1 || lastActionTick == MC.player.tickCount
			|| findSoup(0, 9) != -1 && shouldEatSoup();
	}
	
	private void sortBowls()
	{
		for(int i = 0; i < 36; i++)
		{
			// filter out non-bowl items and empty bowl slot
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack == null || stack.getItem() != Items.BOWL || i == 9)
				continue;
			
			// check if empty bowl slot contains a non-bowl item
			ItemStack emptyBowlStack = MC.player.getInventory().getItem(9);
			boolean swap = !emptyBowlStack.isEmpty()
				&& emptyBowlStack.getItem() != Items.BOWL;
			
			// place bowl in empty bowl slot
			IMC.getGameMode().windowClick_PICKUP(i < 9 ? 36 + i : i);
			IMC.getGameMode().windowClick_PICKUP(9);
			
			// place non-bowl item from empty bowl slot in current slot
			if(swap)
				IMC.getGameMode().windowClick_PICKUP(i < 9 ? 36 + i : i);
		}
	}
	
	/**
	 * Grim's MultiActionsC flags (and cancels) inventory clicks while the
	 * server thinks you're moving, jumping, sprinting or sneaking, since you
	 * can't do that with your inventory open. The server knows the keys from
	 * the last input packet.
	 */
	private boolean canClickInventory()
	{
		Input input = MC.player.getLastSentInput();
		if(input.forward() || input.backward() || input.left() || input.right()
			|| input.jump() || input.shift())
			return false;
		
		return !MC.player.isSprinting()
			&& !WURST.getHax().sneakHack.isPacketSneaking();
	}
	
	private int findSoup(int startSlot, int endSlot)
	{
		for(int i = startSlot; i < endSlot; i++)
			if(isSoup(MC.player.getInventory().getItem(i)))
				return i;
			
		return -1;
	}
	
	private boolean isSoup(ItemStack stack)
	{
		return stack != null && STEWS.contains(stack.getItem());
	}
	
	private boolean shouldEatSoup()
	{
		// check health
		if(MC.player.getHealth() > health.getValueF() * 2F)
			return false;
		
		// check for clickable objects
		if(isClickable(MC.hitResult))
			return false;
		
		return true;
	}
	
	private boolean isClickable(HitResult hitResult)
	{
		if(hitResult instanceof EntityHitResult entityHitResult)
		{
			Entity entity = entityHitResult.getEntity();
			return entity instanceof Villager
				|| entity instanceof TamableAnimal;
		}
		
		if(hitResult instanceof BlockHitResult blockHitResult)
		{
			BlockPos pos = blockHitResult.getBlockPos();
			if(pos == null)
				return false;
			
			Block block = MC.level.getBlockState(pos).getBlock();
			return block instanceof BaseEntityBlock
				|| block instanceof CraftingTableBlock;
		}
		
		return false;
	}
	
	private void stopIfEating()
	{
		// check if eating
		if(oldSlot == -1)
			return;
		
		// stop eating, vanilla lets go of the soup this tick
		MC.options.keyUse.setDown(false);
		
		// reset slot
		MC.player.getInventory().setSelectedSlot(oldSlot);
		oldSlot = -1;
		lastActionTick = MC.player.tickCount;
	}
}
