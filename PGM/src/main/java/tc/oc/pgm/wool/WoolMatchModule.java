package tc.oc.pgm.wool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.inject.Inject;

import com.google.common.collect.ImmutableList;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import tc.oc.commons.bukkit.util.BlockUtils;
import tc.oc.commons.bukkit.util.BukkitUtils;
import tc.oc.commons.core.stream.Collectors;
import tc.oc.time.Time;
import tc.oc.pgm.Config;
import tc.oc.pgm.PGMTranslations;
import tc.oc.pgm.events.BlockTransformEvent;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.events.ParticipantBlockTransformEvent;
import tc.oc.pgm.goals.Contribution;
import tc.oc.pgm.goals.events.GoalCompleteEvent;
import tc.oc.pgm.goals.events.GoalStatusChangeEvent;
import tc.oc.pgm.match.Match;
import tc.oc.pgm.match.MatchModule;
import tc.oc.pgm.match.MatchPlayer;
import tc.oc.pgm.match.MatchScope;
import tc.oc.pgm.match.ParticipantState;
import tc.oc.pgm.match.Repeatable;

@ListenerScope(MatchScope.RUNNING)
public class WoolMatchModule extends MatchModule implements Listener {
    private final List<MonumentWool> wools;

    // Map of containers to a flag indicating whether they contained objective wool when the match started.
    // For this to work, containers have to be checked for wool before their contents can be changed. To ensure this,
    // containers are registered in this map the first time they are opened or accessed by a hopper or dispenser.
    protected final Map<Inventory, Boolean> chests = new HashMap<>();

    // Containers that did contain wool when the match started have an entry in this map representing the exact
    // layout of the wools in the inventory. This is used to refill the container with wools.
    protected final Map<Inventory, Map<Integer, ItemStack>> woolChests = new HashMap<>();

    @Inject private WoolMatchModule(Match match, List<MonumentWoolFactory> wools) {
        this.wools = wools.stream()
                          .map(def -> def.getGoal(match))
                          .collect(Collectors.toImmutableList());
    }

    private boolean isObjectiveWool(ItemStack stack) {
        if(stack.getType() == Material.WOOL) {
            for(MonumentWool wool : this.wools) {
                if(wool.getDefinition().isObjectiveWool(stack)) return true;
            }
        }
        return false;
    }

    private boolean containsObjectiveWool(Inventory inventory) {
        for(MonumentWool wool : this.wools) {
            if(wool.getDefinition().isHolding(inventory)) return true;
        }
        return false;
    }

    private void registerContainer(Inventory inv) {
        // When a chest (or other block inventory) is accessed, check if it's a wool chest
        Boolean isWoolChest = this.chests.get(inv);
        if(isWoolChest == null) {
            // If we haven't seen this chest yet, check it for wool
            isWoolChest = this.containsObjectiveWool(inv);
            this.chests.put(inv, isWoolChest);

            if(isWoolChest) {
                // If it is a wool chest, take a snapshot of the wools
                Map<Integer, ItemStack> contents = new HashMap<>();
                this.woolChests.put(inv, contents);
                for(int slot = 0; slot < inv.getSize(); ++slot) {
                    ItemStack stack = inv.getItem(slot);
                    if(stack != null && this.isObjectiveWool(stack)) {
                        contents.put(slot, stack.clone());
                    }
                }
            }
        }
    }

    @Repeatable(interval = @Time(seconds = 30), scope = MatchScope.RUNNING)
    public void refillOneWoolPerContainer() {
        if(!Config.Wool.autoRefillWoolChests()) return;

        for(Entry<Inventory, Map<Integer, ItemStack>> chest : this.woolChests.entrySet()) {
            Inventory inv = chest.getKey();
            for(Entry<Integer, ItemStack> slotEntry : chest.getValue().entrySet()) {
                int slot = slotEntry.getKey();
                ItemStack wool = slotEntry.getValue();
                ItemStack stack = inv.getItem(slotEntry.getKey());

                if(stack == null) {
                    stack = wool.clone();
                    stack.setAmount(1);
                    inv.setItem(slot, stack);
                    break;
                } else if(stack.isSimilar(wool) && stack.getAmount() < wool.getAmount()) {
                    stack.setAmount(stack.getAmount() + 1);
                    inv.setItem(slot, stack);
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        // Register container blocks when they are opened
        this.registerContainer(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemTransfer(InventoryMoveItemEvent event) {
        // When a hopper or dispenser transfers an item, register both blocks involved
        this.registerContainer(event.getSource());
        this.registerContainer(event.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerPlace(BlockPlaceEvent event) {
        // Blacklist any placed container blocks
        if(event.getBlock().getState() instanceof InventoryHolder) {
            this.chests.put(((InventoryHolder) event.getBlock().getState()).getInventory(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void placementCheck(final BlockTransformEvent event) {
        if(this.match.getWorld() != event.getWorld()) return;

        final MonumentWool wool = this.findMonumentWool(BlockUtils.center(event.getNewState()).toVector());
        if(wool == null) return;

        if(event.getNewState().getType() == Material.AIR) { // block is being destroyed
            if(isValidWool(wool.getDyeColor(), event.getOldState())) {
                event.setCancelled(true);
            }
            return;
        }

        // default to cancelled; only uncancel if player is placing the correct color wool (see below)
        event.setCancelled(true);

        ParticipantState player = ParticipantBlockTransformEvent.getPlayerState(event);
        if(player != null) { // wool can only be placed by a player
            BaseComponent woolName = BukkitUtils.woolName(wool.getDyeColor());
            if(!isValidWool(wool.getDyeColor(), event.getNewState())) {
                player.getAudience().sendWarning(new TranslatableComponent("match.wool.placeWrong", woolName), true);
            } else if(wool.getOwner() != player.getParty()) {
                player.getAudience().sendWarning(new TranslatableComponent("match.wool.placeOther", wool.getOwner().getComponentName(), woolName), true);
            } else {
                event.setCancelled(false);
                wool.markPlaced();
                this.match.callEvent(new GoalStatusChangeEvent(wool));
                this.match.callEvent(new PlayerWoolPlaceEvent(player, wool, event.getNewState()));
                this.match.callEvent(new GoalCompleteEvent(wool,
                                                           true,
                                                           c -> false,
                                                           c -> c.equals(wool.getOwner()),
                                                           ImmutableList.of(new Contribution(player, 1))));
            }
        }
    }

    @EventHandler
    public void handleWoolCrafting(PrepareItemCraftEvent event) {
        ItemStack result = event.getRecipe().getResult();
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof Player) {
            MatchPlayer playerHolder = this.match.getPlayer((Player) holder);

            if (playerHolder != null && result != null && result.getType() == Material.WOOL) {
                for(MonumentWool wool : this.wools) {
                    if(wool.getDefinition().isObjectiveWool(result)) {
                        if(!wool.getDefinition().isCraftable()) {
                            playerHolder.sendMessage(ChatColor.RED + PGMTranslations.t("match.wool.craftDisabled", playerHolder, BukkitUtils.woolMessage(wool.getDyeColor())));
                            event.getInventory().setResult(null);
                        }
                    }
                }
            }
        }
    }

    private MonumentWool findMonumentWool(Vector point) {
        for(MonumentWool wool : this.wools) {
            if(wool.getDefinition().getPlacementRegion().contains(point)) {
                return wool;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static boolean isValidWool(DyeColor expectedColor, BlockState state) {
        return state.getType() == Material.WOOL && expectedColor.getWoolData() == state.getRawData();
    }
}