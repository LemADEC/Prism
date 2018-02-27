/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.helion3.prism.util;

import com.helion3.prism.Prism;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.Living;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.item.inventory.BlockCarrier;
import org.spongepowered.api.item.inventory.Inventory;
import org.spongepowered.api.item.inventory.transaction.SlotTransaction;
import org.spongepowered.api.item.inventory.type.CarriedInventory;
import org.spongepowered.api.world.Locatable;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EventUtil {
    private EventUtil() {}

    /**
     * Reject certain events which can only be identified
     * by the change + cause signature.
     *
     * @param a BlockType original
     * @param b BlockType replacement
     * @param cause Cause chain from event
     * @return boolean If should be rejected
     */
    public static boolean rejectBreakEventIdentity(BlockType a, BlockType b, Cause cause) {
        // Falling blocks
        if (a.equals(BlockTypes.GRAVEL) && b.equals(BlockTypes.AIR)) {
            return !cause.first(Player.class).isPresent();
        }

        // Interesting bugs...
        if (a.equals(BlockTypes.AIR) && b.equals(BlockTypes.AIR)) {
            return true;
        }

        return false;
    }

    /**
     * Reject certain events which can only be identified
     * by the change + cause signature.
     *
     * @param a BlockType original
     * @param b BlockType replacement
     * @param cause Cause chain from event
     * @return boolean If should be rejected
     */
    public static boolean rejectPlaceEventIdentity(BlockType a, BlockType b, Cause cause) {
        // Things that eat grass...
        if (a.equals(BlockTypes.GRASS) && b.equals(BlockTypes.DIRT)) {
            return cause.first(Living.class).isPresent();
        }

        // Grass-like "Grow" events
        if (a.equals(BlockTypes.DIRT) && b.equals(BlockTypes.GRASS)) {
            return cause.first(BlockSnapshot.class).isPresent();
        }

        // If no entity at fault, we don't care about placement that didn't affect anything
        if (!cause.first(Entity.class).isPresent()) {
            return (a.equals(BlockTypes.AIR));
        }

        // Natural flow/fire.
        // Note: This only allows tracking on the source block set by a player using
        // buckets, or items to set fires. Blocks broken by water, lava or fire are still logged as usual.
        // Full flow/fire tracking would be hard on the database and is generally unnecessary.
        if (!cause.first(Player.class).isPresent()) {
            return ( a.equals(BlockTypes.AIR)
                  && ( b.equals(BlockTypes.FLOWING_LAVA)
                    || b.equals(BlockTypes.FLOWING_WATER) )
                  || b.equals(BlockTypes.FIRE) );
        }

        return false;
    }

    /**
     * Try hard to find the location of slot transactions, if all fails, use the player location.
     *
     * @param transactions List of slot transaction (item movements)
     * @param player Fallback location provider, namely, the player
     * @return DataContainer DataContainer of the location
     */
    public static DataContainer getLocationDataContainer(final List<SlotTransaction> transactions, final Player player) {
        for (SlotTransaction transaction : transactions) {
            final Location<World> location = getLocation(transaction);
            if (location != null) {
                return location.toContainer();
            }
        }
        
        final Location<World> location = getLastInteractedLocation(player);
        if (location == null) {
            Prism.getLogger().error(String.format("getLocationDataContainer: no location found, failed to get last interacted block for player %s first slot %s",
                                                  player == null ? "-null-" : player,
                                                  transactions.isEmpty() ? "-none-" : transactions.get(0)));
            return null;
        }
        return location.toContainer();
    }


    /**
     * Try hard to find the location of an event, if all fails, return null.
     *
     * @param transaction List of slot transaction (item movements)
     * @return DataContainer DataContainer of the location
     */
    private static Location<World> getLocation(final SlotTransaction transaction) {
        // check direct tile entities on first slot
        final Inventory parent = transaction.getSlot().parent();
        if (parent instanceof Locatable) {
            return ((Locatable) parent).getLocation();
        } else if (parent instanceof BlockCarrier) {
            return ((BlockCarrier) parent).getLocation();
        }

        final Inventory root = transaction.getSlot().root();
        if (root instanceof Locatable) {
            return ((Locatable) root).getLocation();
        } else if (root instanceof BlockCarrier) {
            return ((BlockCarrier) root).getLocation();
        }

        // check carrier
        if (root instanceof CarriedInventory) {
            final Optional oCarrier = ((CarriedInventory) root).getCarrier();
            if (oCarrier.isPresent()) {
                final Object carrier = oCarrier.get();
                if (carrier instanceof Locatable) {
                    return ((Locatable) carrier).getLocation();
                } else if (carrier instanceof BlockCarrier) {
                    return ((BlockCarrier) carrier).getLocation();
                } else {
                    Prism.getLogger().warn(String.format("getLocatable: carrier isn't Locatable nor BlockCarrier %s with root %s",
                                                         carrier, root));
                }
            }
        }

        // check other slots
        for (Inventory inventory : parent.slots()) {
            if (inventory instanceof Locatable) {
                return ((Locatable) inventory).getLocation();
            } else if (inventory instanceof BlockCarrier) {
                return ((BlockCarrier) inventory).getLocation();
            }
            final Inventory rootIndirect = inventory.root();
            if (rootIndirect instanceof Locatable) {
                return ((Locatable) rootIndirect).getLocation();
            } else if (rootIndirect instanceof BlockCarrier) {
                return ((BlockCarrier) rootIndirect).getLocation();
            }
        }

        // report failure
        // Prism.getLogger().error(String.format("getLocatable: unable to find location for transaction %s",
        //                                       transaction));
        return null;
    }


    // memorize the last interacted block location to support modded containers
    // (in current Forge design, there's no deterministic way to get the tileentity from a container)
    private static ConcurrentHashMap<UUID, Location<World>> playerInteractedLocations = new ConcurrentHashMap<>(100);

    /**
     * Set last interacted block location for a given player.
     *
     * @param player Player triggering the event.
     */
    public static void setInteractedLocation(Player player, Location<World> location) {
        playerInteractedLocations.put(player.getUniqueId(), location);
    }

    /**
     * Clear last interacted block location of a given player.
     *
     * @param player Player triggering the event.
     */
    public static void clearInteractedLocation(Player player) {
        playerInteractedLocations.remove(player.getUniqueId());
    }

    /**
     * Return last interacted block location, or player location.
     *
     * @param player Player triggering the event.
     */
    public static Location<World> getLastInteractedLocation(Player player) {
        if (player == null) {
            Prism.getLogger().error("getLastInteractedLocation: player is null");
            return null;
        }
        final Location<World> location = playerInteractedLocations.get(player.getUniqueId());
        if (location == null) {
            Prism.getLogger().warn(String.format("getLastInteractedLocation: interaction without opening a block for player %s",
                                                 player));
            return player.getLocation();
        }
        try {
            if (!location.inExtent(player.getWorld())) {
                Prism.getLogger().warn(String.format("getLastInteractedLocation: world has changed for player %s", 
                                                     player));
                return player.getLocation();
            }
        } catch (IllegalStateException exception) {
            Prism.getLogger().warn(String.format("getLastInteractedLocation: world has been unloaded for player %s",
                                                 player));
            return player.getLocation();
        }
        return location;
    }
}
