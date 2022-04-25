package io.github.bloepiloepi.pvp.food;

import io.github.bloepiloepi.pvp.entities.CustomPlayer;
import io.github.bloepiloepi.pvp.entities.EntityUtils;
import io.github.bloepiloepi.pvp.entities.Tracker;
import io.github.bloepiloepi.pvp.utils.SoundManager;
import it.unimi.dsi.fastutil.Pair;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.*;
import net.minestom.server.event.player.*;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.potion.Potion;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public class FoodListener {
	
	public static EventNode<PlayerEvent> events(boolean legacy) {
		EventNode<PlayerEvent> node = EventNode.type("food-events", EventFilter.PLAYER);
		
		node.addListener(PlayerTickEvent.class, event -> {
			if (Tracker.hungerManager.containsKey(event.getPlayer().getUuid())) {
				Tracker.hungerManager.get(event.getPlayer().getUuid()).update(legacy);
			}
		});
		
		node.addListener(EventListener.builder(PlayerPreEatEvent.class).handler(event -> {
			FoodComponent foodComponent = FoodComponents.fromMaterial(event.getFoodItem().material());
			
			//If no food, or if the players hunger is full and the food is not always edible, cancel
			if (foodComponent == null || (!event.getPlayer().isCreative()
					&& !foodComponent.isAlwaysEdible() && event.getPlayer().getFood() == 20)) {
				event.setCancelled(true);
				return;
			}
			
			event.setEatingTime((long) getUseTime(foodComponent) * MinecraftServer.TICK_MS);
		}).filter(event -> event.getFoodItem().material().isFood()
				&& event.getFoodItem().material() != Material.POTION)
				.build());
		
		node.addListener(EventListener.builder(PlayerEatEvent.class).handler(event -> {
			Player player = event.getPlayer();
			ItemStack stack = event.getFoodItem();
			Tracker.hungerManager.get(player.getUuid()).eat(stack.material());
			
			FoodComponent component = FoodComponents.fromMaterial(stack.material());
			assert component != null;
			ThreadLocalRandom random = ThreadLocalRandom.current();
			
			triggerEatSounds(player, component);
			
			if (!component.isDrink() || event.getFoodItem().material() == Material.HONEY_BOTTLE) {
				SoundManager.sendToAround(player, SoundEvent.ENTITY_PLAYER_BURP, Sound.Source.PLAYER,
						0.5F, random.nextFloat() * 0.1F + 0.9F);
			}
			
			List<Pair<Potion, Float>> effectList = component.getStatusEffects();
			
			for (Pair<Potion, Float> pair : effectList) {
				if (pair.first() != null && random.nextFloat() < pair.second()) {
					player.addEffect(pair.first());
				}
			}
			
			component.onEat(player, stack);
			
			if (!player.isCreative()) {
				if (component.hasTurnsInto()) {
					if (stack.amount() == 1) {
						player.setItemInHand(event.getHand(), component.getTurnsInto());
					} else {
						player.setItemInHand(event.getHand(), stack.withAmount(stack.amount() - 1));
						player.getInventory().addItemStack(component.getTurnsInto());
					}
				} else {
					event.getPlayer().setItemInHand(event.getHand(), stack.withAmount(stack.amount() - 1));
				}
			}
		}).filter(event -> event.getFoodItem().material().isFood()
				&& event.getFoodItem().material() != Material.POTION).build()); //May also be a potion
		
		node.addListener(PlayerTickEvent.class, event -> {
			Player player = event.getPlayer();
			if (player.isSilent() || !player.isEating()) return;
			
			eatSounds(player);
		});
		
		node.addListener(EventListener.builder(PlayerBlockBreakEvent.class)
				.handler(event -> EntityUtils.addExhaustion(event.getPlayer(), legacy ? 0.025F : 0.005F))
				.build());
		
		node.addListener(EventListener.builder(PlayerMoveEvent.class).handler(event -> {
			Player player = event.getPlayer();
			
			double xDiff = event.getNewPosition().x() - player.getPosition().x();
			double yDiff = event.getNewPosition().y() - player.getPosition().y();
			double zDiff = event.getNewPosition().z() - player.getPosition().z();
			
			//Check if movement was a jump
			if (yDiff > 0.0D && player.isOnGround()) {
				if (player.isSprinting()) {
					EntityUtils.addExhaustion(player, legacy ? 0.8F : 0.2F);
				} else {
					EntityUtils.addExhaustion(player, legacy ? 0.2F : 0.05F);
				}
				
				if (player instanceof CustomPlayer custom)
					custom.jump(); //Velocity change
			}
			
			if (player.isOnGround()) {
				int l = (int) Math.round(Math.sqrt(xDiff * xDiff + zDiff * zDiff) * 100.0F);
				if (l > 0) {
					EntityUtils.addExhaustion(player, (player.isSprinting() ? 0.1F : 0.0F) * (float) l * 0.01F);
				}
			} else {
				if (Objects.requireNonNull(player.getInstance()).getBlock(player.getPosition()) == Block.WATER) {
					int l = (int) Math.round(Math.sqrt(xDiff * xDiff + yDiff * yDiff + zDiff * zDiff) * 100.0F);
					if (l > 0) {
						EntityUtils.addExhaustion(player, 0.01F * (float) l * 0.01F);
					}
				}
			}
		}).build());
		
		return node;
	}
	
	public static void eatSounds(Player player) {
		ItemStack stack = player.getItemInHand(Objects.requireNonNull(player.getEatingHand()));
		
		FoodComponent component = FoodComponents.fromMaterial(stack.material());
		
		long useTime = getUseTime(component);
		long usedDuration = System.currentTimeMillis() - Tracker.itemUseStartTime.get(player.getUuid());
		long usedTicks = usedDuration / MinecraftServer.TICK_MS;
		long remainingUseTicks = useTime - usedTicks;
		
		boolean canTrigger = (component != null && component.isSnack()) || remainingUseTicks <= useTime - 7;
		boolean shouldTrigger = canTrigger && remainingUseTicks % 4 == 0;
		if (!shouldTrigger) return;
		
		triggerEatSounds(player, component);
	}
	
	public static void triggerEatSounds(Player player, @Nullable FoodComponent component) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		
		if (component == null || component.isDrink()) { // null = potion
			SoundEvent soundEvent = component != null ? component.getMaterial() == Material.HONEY_BOTTLE ?
					SoundEvent.ITEM_HONEY_BOTTLE_DRINK : SoundEvent.ENTITY_GENERIC_DRINK : SoundEvent.ENTITY_GENERIC_DRINK;
			SoundManager.sendToAround(player, player, soundEvent, Sound.Source.PLAYER,
					0.5F, random.nextFloat() * 0.1F + 0.9F);
		} else {
			SoundManager.sendToAround(player, player, SoundEvent.ENTITY_GENERIC_EAT, Sound.Source.PLAYER,
					0.5F + 0.5F * random.nextInt(2),
					(random.nextFloat() - random.nextFloat()) * 0.2F + 1.0F);
		}
	}
	
	private static int getUseTime(@Nullable FoodComponent foodComponent) {
		if (foodComponent == null) return 32; // null = potion
		if (foodComponent.getMaterial() == Material.HONEY_BOTTLE) return 40;
		return foodComponent.isSnack() ? 16 : 32;
	}
}
