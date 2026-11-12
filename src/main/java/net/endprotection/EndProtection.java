package net.endprotection;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Protects a circular region (X/Z, all heights) around the centre of every End world.
 * Runs at HIGH so it cancels before other plugins that act at HIGHEST (e.g. the 3x3 pickaxe).
 */
public final class EndProtection implements Listener {

    private static final String BYPASS = "endprotection.bypass";
    private static final long MESSAGE_COOLDOWN_MS = 2000L;

    private final long centerX;
    private final long centerZ;
    private final long radiusSq;
    private final Component denyMessage;

    /** Last time each player was shown the deny message (main thread only). */
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public EndProtection(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        long radius = Math.max(0L, cfg.getLong("end-protection.radius", 600L));
        this.centerX = cfg.getLong("end-protection.center-x", 0L);
        this.centerZ = cfg.getLong("end-protection.center-z", 0L);
        this.radiusSq = radius * radius;
        this.denyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                cfg.getString("end-protection.message",
                        "&cYou can't build or break blocks on the main End island."));
    }

    // ---------- region helpers ----------

    private static boolean isEnd(World world) {
        return world.getEnvironment() == World.Environment.THE_END;
    }

    /** X/Z circle check only: no Y limit, so the whole world height is covered. */
    private boolean inRegion(int x, int z) {
        long dx = x - centerX;
        long dz = z - centerZ;
        return dx * dx + dz * dz <= radiusSq;
    }

    private boolean inRegion(Block block) {
        return inRegion(block.getX(), block.getZ());
    }

    private boolean isProtected(Block block) {
        return isEnd(block.getWorld()) && inRegion(block);
    }

    private static boolean canBypass(Player player) {
        return player.hasPermission(BYPASS);
    }

    private void deny(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendActionBar(denyMessage);
    }

    /** Cancels the event if the block is protected and the actor (may be null) can't bypass. */
    private void guard(Player player, Block block, Cancellable event) {
        if (!isProtected(block)) return;
        if (player != null && canBypass(player)) return;
        event.setCancelled(true);
        if (player != null) deny(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastMessage.remove(e.getPlayer().getUniqueId());
    }

    // ---------- players: break / place / buckets ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        guard(e.getPlayer(), e.getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { // also covers multi-block placing (beds, doors)
        guard(e.getPlayer(), e.getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        guard(e.getPlayer(), e.getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        guard(e.getPlayer(), e.getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        guard(e.getPlayer(), e.getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        guard(e.getPlayer(), e.getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        guard(null, e.getBlock(), e);
    }

    // ---------- item frames / paintings ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        guard(e.getPlayer(), e.getEntity().getLocation().getBlock(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        Player player = null;
        if (e instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player remover) {
            player = remover;
        }
        guard(player, e.getEntity().getLocation().getBlock(), e);
    }

    // ---------- explosions (TNT, creepers, crystals, beds, anchors, dragon, wither) ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!isEnd(e.getLocation().getWorld())) return;
        // keep the explosion (and its damage to entities) but stop it from destroying protected blocks
        e.blockList().removeIf(this::inRegion);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!isEnd(e.getBlock().getWorld())) return;
        e.blockList().removeIf(this::inRegion);
    }

    // ---------- mobs / falling blocks (endermen, wither, falling sand, ...) ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Player player = e.getEntity() instanceof Player p ? p : null;
        guard(player, e.getBlock(), e);
    }

    // ---------- pistons ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonTouchesRegion(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonTouchesRegion(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    /** True if the piston, its head position, or any moved block (or where it ends up) is protected. */
    private boolean pistonTouchesRegion(Block piston, List<Block> moved, BlockFace dir) {
        if (!isEnd(piston.getWorld())) return false;
        if (inRegion(piston) || inRegion(piston.getRelative(dir))) return true;
        BlockFace opposite = dir.getOppositeFace();
        for (Block b : moved) {
            // checking both directions covers extend (pushes along dir) and retract (pulls back)
            if (inRegion(b) || inRegion(b.getRelative(dir)) || inRegion(b.getRelative(opposite))) return true;
        }
        return false;
    }

    // ---------- dispensers / droppers aimed into the region ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Block dispenser = e.getBlock();
        if (!isEnd(dispenser.getWorld())) return;
        if (dispenser.getBlockData() instanceof Directional directional) {
            if (inRegion(dispenser.getRelative(directional.getFacing()))) e.setCancelled(true);
        }
    }

    // ---------- liquids flowing in / dragon egg teleporting ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent e) {
        Block from = e.getBlock();
        if (!isEnd(from.getWorld())) return;

        boolean fromIn = inRegion(from);
        boolean toIn = inRegion(e.getToBlock());
        if (!fromIn && !toIn) return;

        // the egg teleports by "flowing", block it if it touches the region at either end
        if (from.getType() == Material.DRAGON_EGG) {
            e.setCancelled(true);
            return;
        }
        // water/lava from outside must not spread into the region
        if (toIn && !fromIn) e.setCancelled(true);
    }
                  }
