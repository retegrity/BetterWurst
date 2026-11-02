/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Arrays;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ItemUtils;

@SearchTags({"fast lunge", "spear", "lunge", "LongJump", "long jump",
	"SpearBoost", "spear boost"})
public final class FastLungeHack extends Hack implements UpdateListener
{
	// 2 is the fastest that doesn't flag Grim's PacketOrderE (see below).
	// Higher values miss fewer lunges on laggy connections.
	private final SliderSetting interval = new SliderSetting("Interval",
		"Ticks between lunges. 2 is the fastest that doesn't flag Grim.", 2, 2,
		10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	// Every lunge costs 1 durability. Hunger only builds up to 10 food points'
	// worth at a time (FoodData), so big bursts use less food per lunge.
	// Bursts need a rest item without an attack speed modifier, see
	// isRestItem().
	private final SliderSetting burst = new SliderSetting("Lunges per burst",
		"How many times to lunge at once, for a bigger boost.", 1, 1, 10, 1,
		ValueDisplay.INTEGER);
	
	// also turns off once it can't lunge anymore (not enough food, spear
	// about to break, etc.)
	private final SliderSetting maxLunges = new SliderSetting("Max lunges",
		"Turns FastLunge off after this many lunges.", 0, 0, 50, 1,
		ValueDisplay.INTEGER.withLabel(0, "unlimited"));
	
	// the server allows stabbing this many ticks early (cannotAttackWithItem)
	private static final int SERVER_LENIENCY = 5;
	
	// Lunge needs at least this much food, unless you are in creative mode
	private static final int MIN_FOOD = 7;
	
	// how long to wait for the server to confirm durability changes before
	// assuming that Unbreaking prevented them
	private static final int DAMAGE_SYNC_TICKS = 20;
	
	private int spearSlot;
	private int restSlot;
	private int originalSlot;
	private int waitTicks;
	private int lunges;
	private int lungeLevel;
	
	// The server only sends food updates after a while, so predict them.
	// Every lunge adds 4 exhaustion per Lunge level (capped at 40), and every
	// tick, 4 of it use up one saturation or food point.
	private int predictedFood;
	private float predictedSaturation;
	private float predictedExhaustion;
	
	// Durability used up by recent lunges that the server hasn't confirmed
	// yet, one entry per tick. Without this, a big burst could break the
	// spear before its durability updates.
	private final int[] unconfirmedDamage = new int[DAMAGE_SYNC_TICKS];
	private int tick;
	private int lastDamageValue;
	
	public FastLungeHack()
	{
		super("FastLunge");
		setCategory(Category.MOVEMENT);
		addSetting(interval);
		addSetting(burst);
		addSetting(maxLunges);
	}
	
	/*
	 * How it works: the server only lets you stab with a spear once it's fully
	 * charged, but it reads the cooldown length from your attack speed
	 * attribute, which only updates when the server ticks you. So FastLunge
	 * holds an item without an attack speed modifier (cooldown: 5 ticks, which
	 * the server's leniency already covers), switches to the spear, stabs and
	 * switches back before the spear's attack speed ever applies.
	 *
	 * The switch back is sent the next tick, because Grim's PacketOrderE
	 * flags switching slots in the same tick as an attack. That's why it can
	 * lunge every 2 ticks at most.
	 *
	 * Bursts work because the server also doesn't update your attack speed or
	 * food level between packets. Each stab resets the attack cooldown, but
	 * the leniency of 5 ticks still covers the rest item's cooldown, and the
	 * Lunge food check sees the same food level every time.
	 */
	@Override
	protected void onEnable()
	{
		LocalPlayer player = MC.player;
		originalSlot = player.getInventory().getSelectedSlot();
		lunges = 0;
		
		spearSlot = findSpear(player);
		if(spearSlot == -1)
		{
			ChatUtils
				.error("FastLunge needs a spear with Lunge in your hotbar.");
			setEnabled(false);
			return;
		}
		
		restSlot = findRestSlot(player);
		if(restSlot == -1)
		{
			ChatUtils.error("FastLunge needs a free hotbar slot or an item that"
				+ " isn't a weapon or tool.");
			setEnabled(false);
			return;
		}
		
		lungeLevel = getLungeLevel(player.getInventory().getItem(spearSlot));
		FoodData food = player.getFoodData();
		predictedFood = food.getFoodLevel();
		predictedSaturation = food.getSaturationLevel();
		predictedExhaustion = 0;
		Arrays.fill(unconfirmedDamage, 0);
		tick = 0;
		lastDamageValue =
			player.getInventory().getItem(spearSlot).getDamageValue();
		
		// If you aren't already holding the rest item, the server needs a
		// tick with it in your hand first, so your attack speed updates.
		if(originalSlot != restSlot)
		{
			player.getInventory().setSelectedSlot(restSlot);
			waitTicks = 2;
		}else
			waitTicks = 0;
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		LocalPlayer player = MC.player;
		if(player != null)
			player.getInventory().setSelectedSlot(originalSlot);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		tickFood(player.getFoodData());
		ItemStack spear = player.getInventory().getItem(spearSlot);
		confirmDamage(spear);
		
		if(waitTicks > 0)
		{
			waitTicks--;
			return;
		}
		
		PiercingWeapon weapon = spear.get(DataComponents.PIERCING_WEAPON);
		int count = getLungeCount(player, spear, weapon);
		if(count <= 0)
		{
			setEnabled(false);
			return;
		}
		
		// vanilla sends the spear slot right before the stab
		// (MultiPlayerGameMode.ensureHasSentCarriedItem)
		player.getInventory().setSelectedSlot(spearSlot);
		// also swings the hand
		for(int i = 0; i < count; i++)
			MC.gameMode.piercingAttack(spear.getAttackAnimation(), weapon);
		
		// sent at the start of the next tick, before the movement packet
		player.getInventory().setSelectedSlot(restSlot);
		
		lunges += count;
		unconfirmedDamage[tick] = count;
		useFood(count);
		waitTicks = interval.getValueI() - 1;
	}
	
	/**
	 * Returns how many times FastLunge can lunge right now, or 0 if it can't
	 * lunge anymore.
	 */
	private int getLungeCount(LocalPlayer player, ItemStack spear,
		PiercingWeapon weapon)
	{
		if(weapon == null || getLungeLevel(spear) <= 0)
			return 0;
		
		// requirements of the Lunge enchantment
		if(player.isPassenger() || player.isFallFlying() || player.isInWater())
			return 0;
		
		if(!player.getAbilities().instabuild && predictedFood < MIN_FOOD)
			return 0;
		
		int count = burst.getValueI();
		
		int max = maxLunges.getValueI();
		if(max > 0)
			count = Math.min(count, max - lunges);
		
		// don't break the spear
		if(spear.isDamageableItem())
		{
			int durability = spear.getMaxDamage() - spear.getDamageValue()
				- Arrays.stream(unconfirmedDamage).sum();
			count = Math.min(count, durability - 1);
		}
		
		return count;
	}
	
	private void confirmDamage(ItemStack spear)
	{
		tick = (tick + 1) % DAMAGE_SYNC_TICKS;
		unconfirmedDamage[tick] = 0;
		
		int confirmed = spear.getDamageValue() - lastDamageValue;
		lastDamageValue = spear.getDamageValue();
		
		// oldest lunges first
		for(int i = 1; i < DAMAGE_SYNC_TICKS && confirmed > 0; i++)
		{
			int j = (tick + i) % DAMAGE_SYNC_TICKS;
			int amount = Math.min(confirmed, unconfirmedDamage[j]);
			unconfirmedDamage[j] -= amount;
			confirmed -= amount;
		}
	}
	
	private void useFood(int count)
	{
		if(MC.player.getAbilities().instabuild)
			return;
		
		predictedExhaustion =
			Math.min(predictedExhaustion + 4 * lungeLevel * count, 40);
	}
	
	// like FoodData.tick()
	private void tickFood(FoodData food)
	{
		if(predictedExhaustion > 4)
		{
			predictedExhaustion -= 4;
			if(predictedSaturation > 0)
				predictedSaturation = Math.max(predictedSaturation - 1, 0);
			else
				predictedFood--;
		}
		
		predictedFood = Math.min(predictedFood, food.getFoodLevel());
		predictedSaturation =
			Math.min(predictedSaturation, food.getSaturationLevel());
	}
	
	private int findSpear(LocalPlayer player)
	{
		int best = -1;
		int bestLevel = 0;
		for(int slot = 0; slot < 9; slot++)
		{
			ItemStack stack = player.getInventory().getItem(slot);
			if(!stack.is(ItemTags.SPEARS)
				|| !stack.has(DataComponents.PIERCING_WEAPON))
				continue;
			
			if(stack.isDamageableItem()
				&& stack.getMaxDamage() - stack.getDamageValue() <= 1)
				continue;
			
			int level = getLungeLevel(stack);
			if(level > bestLevel)
			{
				best = slot;
				bestLevel = level;
			}
		}
		
		return best;
	}
	
	/**
	 * Finds a hotbar slot whose item has a short enough attack cooldown that
	 * the server lets you stab right after switching from it. Prefers the
	 * item you are holding, then empty slots.
	 */
	private int findRestSlot(LocalPlayer player)
	{
		int selected = player.getInventory().getSelectedSlot();
		if(selected != spearSlot && isRestItem(player, selected))
			return selected;
		
		for(int slot = 0; slot < 9; slot++)
			if(slot != spearSlot
				&& player.getInventory().getItem(slot).isEmpty())
				return slot;
			
		for(int slot = 0; slot < 9; slot++)
			if(slot != spearSlot && isRestItem(player, slot))
				return slot;
			
		return -1;
	}
	
	private boolean isRestItem(LocalPlayer player, int slot)
	{
		ItemStack stack = player.getInventory().getItem(slot);
		double base = player.getAttributeBaseValue(Attributes.ATTACK_SPEED);
		double attackSpeed = stack.isEmpty() ? base
			: ItemUtils.calculateModifiedAttribute(stack.getItem(),
				Attributes.ATTACK_SPEED, base, EquipmentSlot.MAINHAND);
		if(attackSpeed <= 0)
			return false;
			
		// the cooldown ticks the server has counted by the next stab, which
		// is none for the stabs after the first one in a burst
		int ticks = SERVER_LENIENCY;
		if(burst.getValueI() == 1)
			ticks += interval.getValueI() - 1;
		double delay = 20 / attackSpeed;
		return ticks / delay >= 1;
	}
	
	private int getLungeLevel(ItemStack stack)
	{
		if(MC.level == null)
			return 0;
		
		return MC.level.registryAccess().lookup(Registries.ENCHANTMENT)
			.flatMap(reg -> reg.get(Enchantments.LUNGE))
			.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder,
				stack))
			.orElse(0);
	}
}
