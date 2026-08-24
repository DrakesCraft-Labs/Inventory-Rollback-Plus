package me.danjono.inventoryrollback.listeners;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import me.danjono.inventoryrollback.InventoryRollback;
import com.tcoded.lightlibs.bukkitversion.BukkitVersion;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.inventory.SaveInventory;
import me.danjono.inventoryrollback.inventory.WorldGroupPolicy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.scheduler.BukkitTask;

public class EventLogs implements Listener {

	private InventoryRollbackPlus main;
	private Map<UUID, SaveInventory.PlayerDataSnapshot> inventoryCache;
	private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();
	private final java.util.Set<UUID> approvedTransfers = ConcurrentHashMap.newKeySet();
	private final java.util.Set<UUID> preBackedUpTransfers = ConcurrentHashMap.newKeySet();

	public EventLogs() {
		this.main = InventoryRollbackPlus.getInstance();
		this.inventoryCache = new ConcurrentHashMap<>();
	}

	public static void patchLowestHandlers() {
		// Fix for LOWEST priority handlers.
		// We move the handlers to the end of the list such that it runs after our handler
		HandlerList deathEventHandlers = PlayerDeathEvent.getHandlerList();
		List<RegisteredListener> otherDeathHandlers = new ArrayList<>();

		for (RegisteredListener handler : deathEventHandlers.getRegisteredListeners()) {
			// Ignore and non-LOWEST priority handlers
			if (handler.getPriority() != EventPriority.LOWEST) continue;
			// Ignore our own listener
			if (handler.getListener().getClass() == EventLogs.class) continue;
			otherDeathHandlers.add(handler);
		}

		// Shift all the handlers to the end of the list, in order
		for (RegisteredListener handler : otherDeathHandlers) {
			deathEventHandlers.unregister(handler);
			deathEventHandlers.register(handler);
		}

		deathEventHandlers.bake();
	}

	@EventHandler
	private void playerJoin(PlayerJoinEvent e) {
		if (!ConfigData.isEnabled()) return;

		Player player = e.getPlayer();
		if (player.hasPermission("inventoryrollbackplus.joinsave")) {
			new SaveInventory(e.getPlayer(), LogType.JOIN, null, null)
					.snapshotAndSave(player.getInventory(), player.getEnderChest(), true);
		}
		if (player.hasPermission("inventoryrollbackplus.adminalerts")) {
			// can send info to admins here
		}
	}

	@EventHandler
	private void playerQuit(PlayerQuitEvent e) {
		if (!ConfigData.isEnabled()) return;

		Player player = e.getPlayer();
		cancelPendingTransfer(player.getUniqueId(), null);
		approvedTransfers.remove(player.getUniqueId());
		preBackedUpTransfers.remove(player.getUniqueId());

		if (player.hasPermission("inventoryrollbackplus.leavesave")) {
			new SaveInventory(e.getPlayer(), LogType.QUIT, null, null)
					.snapshotAndSave(player.getInventory(), player.getEnderChest(), true);
		}

		UUID uuid = player.getUniqueId();

		// Run the cleanup 1 tick later in case the rate limiter should need to provide debug data.
		// If the cleanup would run and the event is being spammed, this cleanup would delete the rate limiter's data
		// before it has a chance to act.
		main.getServer().getScheduler().runTaskLater(main, () -> {
			// Double check that the player is offline
			if (main.getServer().getPlayer(uuid) != null) return;
			// Cleanup the player's data
			SaveInventory.cleanup(uuid);
		}, 1);
	}

	/**
	 * Saves the source inventory before every world transition. Cross-modality transitions are
	 * delayed so the snapshot is durable before another inventory manager swaps player data.
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void beforeWorldTransfer(PlayerTeleportEvent event) {
		if (!ConfigData.isEnabled() || event.getTo() == null) return;
		Player player = event.getPlayer();
		if (event.getFrom().getWorld() == null || event.getTo().getWorld() == null
				|| event.getFrom().getWorld().equals(event.getTo().getWorld())) return;

		UUID uuid = player.getUniqueId();
		if (approvedTransfers.remove(uuid)) return;
		String sourceGroup = WorldGroupPolicy.groupOfWorld(event.getFrom().getWorld().getName());
		String destinationGroup = WorldGroupPolicy.groupOfWorld(event.getTo().getWorld().getName());

		if (sourceGroup.equals(destinationGroup)) {
			backupTransferSource(player, sourceGroup, destinationGroup);
			markPreBackedUp(uuid);
			return;
		}

		event.setCancelled(true);
		if (pendingTransfers.containsKey(uuid)) {
			player.sendMessage("§e[InventoryRollbackPlus] A modality transfer is already being prepared.");
			return;
		}

		int seconds = Math.max(1, InventoryRollback.getInstance().getConfig()
				.getInt("world-transfer-safety.delay-seconds", 5));
		Location origin = player.getLocation().clone();
		Location destination = event.getTo().clone();
		PlayerTeleportEvent.TeleportCause cause = event.getCause();
		player.sendMessage("§bStay still: taking you to §f" + destinationGroup
				+ "§b and backing up your §f" + sourceGroup + "§b inventory. Wait §f" + seconds + " seconds§b.");

		BukkitTask task = main.getServer().getScheduler().runTaskLater(main, () -> {
			PendingTransfer pending = pendingTransfers.remove(uuid);
			if (pending == null || !player.isOnline()) return;
			if (!samePosition(player.getLocation(), pending.origin)) {
				player.sendMessage("§cModality transfer cancelled because you moved.");
				return;
			}
			backupTransferSource(player, pending.sourceGroup, pending.destinationGroup);
			markPreBackedUp(uuid);
			approvedTransfers.add(uuid);
			boolean teleported = player.teleport(pending.destination, pending.cause);
			if (!teleported) {
				approvedTransfers.remove(uuid);
				player.sendMessage("§cThe modality transfer failed safely; your source inventory was backed up.");
			}
		}, seconds * 20L);
		pendingTransfers.put(uuid, new PendingTransfer(origin, destination, sourceGroup, destinationGroup, cause, task));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void cancelTransferOnMovement(PlayerMoveEvent event) {
		PendingTransfer pending = pendingTransfers.get(event.getPlayer().getUniqueId());
		if (pending == null || event.getTo() == null || samePosition(event.getTo(), pending.origin)) return;
		cancelPendingTransfer(event.getPlayer().getUniqueId(), "§cModality transfer cancelled because you moved.");
	}

	private void backupTransferSource(Player player, String sourceGroup, String destinationGroup) {
		if (!player.hasPermission("inventoryrollbackplus.worldchangesave")) return;
		new SaveInventory(player, LogType.WORLD_CHANGE, null,
				"TRANSFER_" + sourceGroup.toUpperCase() + "_TO_" + destinationGroup.toUpperCase())
				.snapshotAndSave(player.getInventory(), player.getEnderChest(), false);
	}

	private void markPreBackedUp(UUID uuid) {
		preBackedUpTransfers.add(uuid);
		main.getServer().getScheduler().runTaskLater(main, () -> preBackedUpTransfers.remove(uuid), 2L);
	}

	private void cancelPendingTransfer(UUID uuid, String message) {
		PendingTransfer pending = pendingTransfers.remove(uuid);
		if (pending == null) return;
		pending.task.cancel();
		Player player = main.getServer().getPlayer(uuid);
		if (message != null && player != null) player.sendMessage(message);
	}

	private static boolean samePosition(Location first, Location second) {
		return first.getWorld() != null && first.getWorld().equals(second.getWorld())
				&& first.distanceSquared(second) <= 0.01D;
	}

	private static final class PendingTransfer {
		private final Location origin;
		private final Location destination;
		private final String sourceGroup;
		private final String destinationGroup;
		private final PlayerTeleportEvent.TeleportCause cause;
		private final BukkitTask task;

		private PendingTransfer(Location origin, Location destination, String sourceGroup, String destinationGroup,
				PlayerTeleportEvent.TeleportCause cause, BukkitTask task) {
			this.origin = origin;
			this.destination = destination;
			this.sourceGroup = sourceGroup;
			this.destinationGroup = destinationGroup;
			this.cause = cause;
			this.task = task;
		}
	}

	/**
	 * Save the player's inventory before death.
	 * @param event Bukkit damage event
	 */
    @EventHandler(priority = EventPriority.LOWEST)
	public void playerPreDeath(EntityDamageEvent event) {
		// Only run if other plugins are not allowed to edit the death inventory (early event listen)
		if (ConfigData.isAllowOtherPluginEditDeathInventory()) return;

		if (!(event.getEntity() instanceof Player)) return;
		Player player = (Player) event.getEntity();
		UUID uuid = player.getUniqueId();

		// Not death? Don't make a snapshot & remove any old ones to prevent false-positives
		if (!isDeathDamage(event)) {
			this.inventoryCache.remove(uuid);
			return;
		}

		SaveInventory saveInventory = new SaveInventory(player, LogType.DEATH, event.getCause(), null);
		SaveInventory.PlayerDataSnapshot snapshot = saveInventory.createSnapshot(player.getInventory(), player.getEnderChest());

		this.inventoryCache.put(uuid, snapshot);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void playerPreDeathCheck(EntityDamageEvent event) {
		// Only run if other plugins are not allowed to edit the death inventory (early event listen)
		if (ConfigData.isAllowOtherPluginEditDeathInventory()) return;

		if (!(event.getEntity() instanceof Player)) return;
		Player player = (Player) event.getEntity();
		UUID uuid = player.getUniqueId();

		// Other plugins may cancel or edit the damage on the event between LOWEST and now MONITOR.
		// Let's make sure we don't keep our snapshot if that's the case.
		if (event.isCancelled() || event.getFinalDamage() == 0) {
			// Remove our temporary snapshot. This will also prevent further checks below from succeeding.
			this.inventoryCache.remove(uuid);
			return;
		}

		SaveInventory.PlayerDataSnapshot firstSnapshot = this.inventoryCache.get(uuid);
		if (firstSnapshot == null) return;

		SaveInventory saveInventory = new SaveInventory(player, LogType.DEATH, event.getCause(), null);
		SaveInventory.PlayerDataSnapshot lastSnapshot = saveInventory.createSnapshot(player.getInventory(), player.getEnderChest());

		// Inventory was not edited during a damage event, we don't need this hacky snapshot
		if (firstSnapshot.equals(lastSnapshot)) {
			this.inventoryCache.remove(uuid);
			return;
		}

		// If the inventory was edited, warn
		InventoryRollbackPlus.getInstance().getLogger().warning(
				player.getName() + "'s inventory was edited during damage handling (instead of death, this is bad). " +
						"Please find which plugin is doing this by disabling one plugin at the time " +
						"(or use \"binary search\" if you know how) until this message disappears!"
		);
	}

	/**
	 * Handle saving the player's inventory on death. (Early event listen)
	 * @param event Bukkit damage event
	 */
    @EventHandler(priority = EventPriority.LOWEST)
	public void playerDeathEarly(PlayerDeathEvent event) {
		// Only run if other plugins are not allowed to edit the death inventory (early event listen)
		if (ConfigData.isAllowOtherPluginEditDeathInventory()) return;

		playerDeathHandle(event);
	}

	/**
	 * Handle saving the player's inventory on death. (Late event listen)
	 * @param event Bukkit damage event
	 */
    @EventHandler(priority = EventPriority.MONITOR)
	public void playerDeathLate(PlayerDeathEvent event) {
		// Only run if other plugins are allowed to edit the death inventory (late event listen)
		if (!ConfigData.isAllowOtherPluginEditDeathInventory()) return;

		playerDeathHandle(event);
	}

	public void playerDeathHandle(PlayerDeathEvent event) {
        // Sanity checks to prevent unwanted saves
        if (!ConfigData.isEnabled()) return;

        Player player = event.getEntity();

		// Check that the player has the permission for inventory saves
        if (player.hasPermission("inventoryrollbackplus.deathsave")) {

            EntityDamageEvent damageEvent = event.getEntity().getLastDamageCause();
			DetailedReason detailedReason = getDetailedReason(damageEvent);

			// After all checks, create the save with data provided above
			SaveInventory saveInventory = new SaveInventory(player, LogType.DEATH, detailedReason.damageCause, detailedReason.reason);

			UUID uuid = player.getUniqueId();
			SaveInventory.PlayerDataSnapshot preSnapshot = this.inventoryCache.get(uuid);

			if (preSnapshot == null) {
				saveInventory.snapshotAndSave(player.getInventory(), player.getEnderChest(), true);
			} else {
				// Save the snapshot inventory instead of the current one. We apparently had an edit
				// during the damage event.
				saveInventory.save(preSnapshot, true);
				// Remove the snapshot from the cache
				this.inventoryCache.remove(uuid);
			}
        }
    }

	@EventHandler
	private void playerChangeWorld(PlayerChangedWorldEvent e) {
		if (!ConfigData.isEnabled()) return;

		Player player = e.getPlayer();
		if (preBackedUpTransfers.remove(player.getUniqueId())) return;

		if (player.hasPermission("inventoryrollbackplus.worldchangesave")) {
			new SaveInventory(e.getPlayer(), LogType.WORLD_CHANGE, null, null)
					.snapshotAndSave(player.getInventory(), player.getEnderChest(), true);
		}
	}

	public boolean isEntityCause(EntityDamageEvent.DamageCause cause) {
		if (cause.equals(EntityDamageEvent.DamageCause.ENTITY_ATTACK) ||
				cause.equals(EntityDamageEvent.DamageCause.PROJECTILE)) return true;
		if (this.main.getVersion().greaterOrEqThan(BukkitVersion.v1_11_R1)) {
			if (cause.equals(EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) return true;
		}
		return false;
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	private boolean isDeathDamage(EntityDamageEvent event) {
		// This only checks damage and doesn't take into account potential cancellation reasons such
		// as plugins or totems of undying. Useless SaveInventory objects will be created (not saved)
		// but this prevents other plugins from interfering with the death save.

		if (!(event.getEntity() instanceof LivingEntity)) return false;
		LivingEntity living = (LivingEntity) event.getEntity();

		return event.getFinalDamage() >= living.getHealth();
	}

	private @NotNull DetailedReason getDetailedReason(EntityDamageEvent damageEvent) {
		EntityDamageEvent.DamageCause damageCause;

		if (damageEvent == null) damageCause = EntityDamageEvent.DamageCause.CUSTOM;
		else damageCause = damageEvent.getCause();

		// Detailed reason for the death that can be applied given certain conditions
		String reason = null;

		// Handler the case where the death is caused by an entity
		if (isEntityCause(damageCause) && damageEvent instanceof EntityDamageByEntityEvent) {
			EntityDamageByEntityEvent damageByEntityEvent = (EntityDamageByEntityEvent) damageEvent;
			Entity damager = damageByEntityEvent.getDamager();

			// Get the shooter's name if the killing entity is a projectile
			String shooterName = "";
			if (damager instanceof Projectile) {

				Projectile proj = (Projectile) damager;
				ProjectileSource shooter = proj.getShooter();

				// Show shooter name if it's a living entity
				if (shooter instanceof LivingEntity) {
					LivingEntity shooterEntity = (LivingEntity) shooter;
					shooterName = ", " + shooterEntity.getName();
				}
				// Show shooter block type if it's a block projectile source
				else if (shooter instanceof BlockProjectileSource) {
					BlockProjectileSource shooterBlock = (BlockProjectileSource) shooter;
					shooterName = ", " + shooterBlock.getBlock().getType().name();

				}
				// In all other cases, don't show projectile detailed shooter info
			}

			// Create a more specific reason given the data above
			reason = damageCause.name() + " (" + damageByEntityEvent.getDamager().getName() + shooterName + ")";
		}
		DetailedReason detailedReason = new DetailedReason(damageCause, reason);
		return detailedReason;
	}

	private static class DetailedReason {
		public final EntityDamageEvent.DamageCause damageCause;
		public final String reason;

		public DetailedReason(EntityDamageEvent.DamageCause damageCause, String reason) {
			this.damageCause = damageCause;
			this.reason = reason;
		}
	}

}
