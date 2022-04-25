package io.github.bloepiloepi.pvp.test;

import io.github.bloepiloepi.pvp.PvpExtension;
import io.github.bloepiloepi.pvp.damage.CustomDamageType;
import io.github.bloepiloepi.pvp.events.EntityKnockbackEvent;
import io.github.bloepiloepi.pvp.events.LegacyKnockbackEvent;
import io.github.bloepiloepi.pvp.legacy.LegacyKnockbackSettings;
import io.github.bloepiloepi.pvp.listeners.DamageListener;
import io.github.bloepiloepi.pvp.potion.effect.CustomPotionEffect;
import io.github.bloepiloepi.pvp.test.commands.Commands;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.attribute.Attribute;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.*;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.*;
import net.minestom.server.extras.lan.OpenToLAN;
import net.minestom.server.instance.Instance;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.utils.time.TimeUnit;

import java.util.Optional;

public class PvpTest {
	public static void main(String[] args) {
		MinecraftServer server = MinecraftServer.init();
		PvpExtension.init();
		//VelocityProxy.enable("tj7MulOtnIDe");
		
		Instance instance = MinecraftServer.getInstanceManager().createInstanceContainer();
		instance.setChunkGenerator(new DemoGenerator());
		instance.enableAutoChunkLoad(true);
		
		Pos spawn = new Pos(0, 60, 0);
		MinecraftServer.getGlobalEventHandler().addListener(PlayerLoginEvent.class, event -> {
			event.setSpawningInstance(instance);
			event.getPlayer().setRespawnPoint(spawn);
			
			EntityCreature entity = new EntityCreature(EntityType.ZOMBIE);
			entity.setInstance(instance, spawn);
			entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(500);
			entity.heal();
			
			MinecraftServer.getSchedulerManager().buildTask(() -> {
				Optional<Player> player = MinecraftServer.getConnectionManager()
						.getOnlinePlayers().stream()
						.filter(p -> p.getDistanceSquared(entity) < 6)
						.findAny();
				
				if (player.isPresent()) {
					if (!player.get().damage(CustomDamageType.mob(entity), 1.0F))
						return;
					
					LegacyKnockbackEvent legacyKnockbackEvent = new LegacyKnockbackEvent(player.get(), entity, true);
					EventDispatcher.callCancellable(legacyKnockbackEvent, () -> {
						LegacyKnockbackSettings settings = legacyKnockbackEvent.getSettings();

						player.get().setVelocity(player.get().getVelocity().add(
								-Math.sin(entity.getPosition().yaw() * 3.1415927F / 180.0F) * 1 * settings.extraHorizontal(),
								settings.extraVertical(),
								Math.cos(entity.getPosition().yaw() * 3.1415927F / 180.0F) * 1 * settings.extraHorizontal()
						));
					});
//					EntityKnockbackEvent entityKnockbackEvent = new EntityKnockbackEvent(player.get(), entity, true, false, 1 * 0.5F);
//					EventDispatcher.callCancellable(entityKnockbackEvent, () -> {
//						float strength = entityKnockbackEvent.getStrength();
//						player.get().takeKnockback(strength, Math.sin(Math.toRadians(entity.getPosition().yaw())), -Math.cos(Math.toRadians(entity.getPosition().yaw())));
//					});
				}
				
				event.getPlayer().setFood(20);
			}).repeat(3, TimeUnit.SERVER_TICK).schedule();
		});
		
		MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, event -> {
			event.getPlayer().setGameMode(GameMode.CREATIVE);
			PvpExtension.setLegacyAttack(event.getPlayer(), true);
			
			event.getPlayer().setPermissionLevel(4);
			event.getPlayer().addEffect(new Potion(PotionEffect.REGENERATION, (byte) 10, CustomPotionEffect.PERMANENT));
		});
		
//		LegacyKnockbackSettings settings = LegacyKnockbackSettings.builder()
//				.horizontal(0.35)
//				.vertical(0.4)
//				.verticalLimit(0.4)
//				.extraHorizontal(0.45)
//				.extraVertical(0.1)
//				.build();
//		MinecraftServer.getGlobalEventHandler().addListener(LegacyKnockbackEvent.class,
//				event -> event.setSettings(settings));
		
		GlobalEventHandler eventHandler = MinecraftServer.getGlobalEventHandler();
		eventHandler.addChild(PvpExtension.legacyEvents());
		
		eventHandler.addListener(PlayerTickEvent.class, event -> {
			event.getPlayer().sendActionBar(Component.text(event.getPlayer().getVelocity().toString()));
		});
		
		Commands.init();
		
		OpenToLAN.open();
		
		server.start("localhost", 25565);
	}
}
