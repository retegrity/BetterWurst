/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TakeItemsFromSetting;
import net.wurstclient.settings.TakeItemsFromSetting.TakeItemsFrom;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"auto eat", "AutoFood", "auto food", "AutoFeeder", "auto feeder",
	"AutoFeeding", "auto feeding", "AutoSoup", "auto soup"})
public final class AutoEatHack extends Hack
	implements UpdateListener, HandleInputListener
{
	private final SliderSetting targetHunger = new SliderSetting(
		"Target hunger", "description.wurst.setting.autoeat.target_hunger", 10,
		0, 10, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting minHunger = new SliderSetting("Min hunger",
		"description.wurst.setting.autoeat.min_hunger", 6.5, 0, 10, 0.5,
		ValueDisplay.DECIMAL);
	
	private final SliderSetting injuredHunger = new SliderSetting(
		"Injured hunger", "description.wurst.setting.autoeat.injured_hunger",
		10, 0, 10, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting injuryThreshold =
		new SliderSetting("Injury threshold",
			"description.wurst.setting.autoeat.injury_threshold", 1.5, 0.5, 10,
			0.5, ValueDisplay.DECIMAL);
	
	private final TakeItemsFromSetting takeItemsFrom =
		TakeItemsFromSetting.withHands(this, TakeItemsFrom.HOTBAR);
	
	private final CheckboxSetting allowOffhand =
		new CheckboxSetting("Allow offhand", true);
	
	private final CheckboxSetting eatWhileWalking =
		new CheckboxSetting("Eat while walking",
			"description.wurst.setting.autoeat.eat_while_walking", false);
	
	private final CheckboxSetting allowHunger =
		new CheckboxSetting("Allow hunger effect",
			"description.wurst.setting.autoeat.allow_hunger", true);
	
	private final CheckboxSetting allowPoison =
		new CheckboxSetting("Allow poison effect",
			"description.wurst.setting.autoeat.allow_poison", false);
	
	private final CheckboxSetting allowChorus =
		new CheckboxSetting("Allow chorus fruit",
			"description.wurst.setting.autoeat.allow_chorus", false);
	
	private int oldSlot = -1;
	private boolean eatingOffhand;
	
	public AutoEatHack()
	{
		super("AutoEat");
		setCategory(Category.ITEMS);
		
		addSetting(targetHunger);
		addSetting(minHunger);
		addSetting(injuredHunger);
		addSetting(injuryThreshold);
		
		addSetting(takeItemsFrom);
		addSetting(allowOffhand);
		
		addSetting(eatWhileWalking);
		addSetting(allowHunger);
		addSetting(allowPoison);
		addSetting(allowChorus);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoSoupHack.setEnabled(false);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		
		if(isEating())
			stopEating();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		
		if(!shouldEat())
		{
			if(isEating())
				stopEating();
			
			return;
		}
		
		FoodData hungerManager = player.getFoodData();
		int foodLevel = hungerManager.getFoodLevel();
		int targetHungerI = (int)(targetHunger.getValue() * 2);
		int minHungerI = (int)(minHunger.getValue() * 2);
		int injuredHungerI = (int)(injuredHunger.getValue() * 2);
		
		if(isInjured(player) && foodLevel < injuredHungerI)
		{
			eat(-1);
			return;
		}
		
		if(foodLevel < minHungerI)
		{
			eat(-1);
			return;
		}
		
		if(foodLevel < targetHungerI)
		{
			int maxPoints = targetHungerI - foodLevel;
			eat(maxPoints);
		}
	}
	
	private void eat(int maxPoints)
	{
		Inventory inventory = MC.player.getInventory();
		int foodSlot = findBestFoodSlot(maxPoints);
		
		if(foodSlot == -1)
		{
			if(isEating())
				stopEating();
			
			return;
		}
		
		// select food
		if(foodSlot < 9)
		{
			if(!isEating())
				oldSlot = inventory.getSelectedSlot();
			
			inventory.setSelectedSlot(foodSlot);
			
		}else if(foodSlot == 40)
		{
			if(!isEating())
				oldSlot = inventory.getSelectedSlot();
			
			eatOffhand();
			return;
			
		}else
		{
			InventoryUtils.selectItem(foodSlot);
			return;
		}
		
		// eat food
		MC.options.keyUse.setDown(true);
		IMC.getGameMode().rightClickItem();
	}
	
	/**
	 * Starts eating with the off-hand directly. Holding the use key to start
	 * would make the game try the main hand first, which places the block
	 * you are holding there.
	 */
	private void eatOffhand()
	{
		LocalPlayer player = MC.player;
		if(!player.isUsingItem())
			MC.gameMode.useItem(player, InteractionHand.OFF_HAND);
		
		// the game stops eating when the use key isn't held
		eatingOffhand = player.isUsingItem();
		MC.options.keyUse.setDown(eatingOffhand);
	}
	
	/**
	 * Lets go of the use key once the off-hand food is eaten. The client only
	 * finishes eating when the server says so, and that packet is handled
	 * right before the game reads the keys. So this has to happen here, or
	 * the game would use the main hand with the key that is still held.
	 */
	@Override
	public void onHandleInput()
	{
		if(!eatingOffhand || MC.player.isUsingItem())
			return;
		
		MC.options.keyUse.setDown(false);
		eatingOffhand = false;
	}
	
	private int findBestFoodSlot(int maxPoints)
	{
		Inventory inventory = MC.player.getInventory();
		FoodProperties bestFood = null;
		int bestSlot = -1;
		
		int maxInvSlot = takeItemsFrom.getMaxInvSlot();
		
		ArrayList<Integer> slots = new ArrayList<>();
		if(maxInvSlot == 0)
			slots.add(inventory.getSelectedSlot());
		if(allowOffhand.isChecked())
			slots.add(40);
		Stream.iterate(0, i -> i < maxInvSlot, i -> i + 1)
			.forEach(i -> slots.add(i));
		
		Comparator<FoodProperties> comparator =
			Comparator.comparingDouble(FoodProperties::saturation);
		
		for(int slot : slots)
		{
			ItemStack stack = inventory.getItem(slot);
			
			// filter out non-food items
			if(!stack.has(DataComponents.FOOD))
				continue;
				
			// Some items (like fish buckets) have food properties but can't
			// actually be eaten, because they have no consumable component.
			Consumable consumable = stack.get(DataComponents.CONSUMABLE);
			if(consumable == null)
				continue;
			
			if(!isAllowedFood(consumable))
				continue;
			
			FoodProperties food = stack.get(DataComponents.FOOD);
			if(maxPoints >= 0 && food.nutrition() > maxPoints)
				continue;
			
			// compare to previously found food
			if(bestFood == null || comparator.compare(food, bestFood) > 0)
			{
				bestFood = food;
				bestSlot = slot;
			}
		}
		
		return bestSlot;
	}
	
	private boolean shouldEat()
	{
		if(MC.player.getAbilities().instabuild)
			return false;
		
		if(!MC.player.canEat(false))
			return false;
		
		if(!eatWhileWalking.isChecked()
			&& (MC.player.zza != 0 || MC.player.xxa != 0))
			return false;
		
		if(isClickable(MC.hitResult))
			return false;
		
		return true;
	}
	
	private void stopEating()
	{
		MC.options.keyUse.setDown(false);
		eatingOffhand = false;
		MC.player.getInventory().setSelectedSlot(oldSlot);
		oldSlot = -1;
	}
	
	private boolean isAllowedFood(Consumable consumable)
	{
		for(ConsumeEffect consumeEffect : consumable.onConsumeEffects())
		{
			if(!allowChorus.isChecked()
				&& consumeEffect instanceof TeleportRandomlyConsumeEffect)
				return false;
			
			if(!(consumeEffect instanceof ApplyStatusEffectsConsumeEffect applyEffectsConsumeEffect))
				continue;
			
			for(MobEffectInstance effect : applyEffectsConsumeEffect.effects())
			{
				Holder<MobEffect> entry = effect.getEffect();
				
				if(!allowHunger.isChecked() && entry == MobEffects.HUNGER)
					return false;
				
				if(!allowPoison.isChecked() && entry == MobEffects.POISON)
					return false;
			}
		}
		
		return true;
	}
	
	public boolean isEating()
	{
		return oldSlot != -1;
	}
	
	private boolean isClickable(HitResult hitResult)
	{
		if(hitResult == null)
			return false;
		
		if(hitResult instanceof EntityHitResult)
		{
			Entity entity = ((EntityHitResult)hitResult).getEntity();
			return entity instanceof Villager
				|| entity instanceof TamableAnimal;
		}
		
		if(hitResult instanceof BlockHitResult)
		{
			BlockPos pos = ((BlockHitResult)hitResult).getBlockPos();
			if(pos == null)
				return false;
			
			Block block = MC.level.getBlockState(pos).getBlock();
			return block instanceof BaseEntityBlock
				|| block instanceof CraftingTableBlock;
		}
		
		return false;
	}
	
	private boolean isInjured(LocalPlayer player)
	{
		int injuryThresholdI = (int)(injuryThreshold.getValue() * 2);
		return player.getHealth() < player.getMaxHealth() - injuryThresholdI;
	}
}
