/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention;

import java.util.Calendar;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Vehicle;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.painting.PaintingBreakByEntityEvent;
import org.bukkit.event.painting.PaintingBreakEvent;
import org.bukkit.event.painting.PaintingPlaceEvent;

//handles events related to entities
class EntityEventHandler implements Listener
{
	//convenience reference for the singleton datastore
	private DataStore dataStore;
	
	public EntityEventHandler(DataStore dataStore)
	{
		this.dataStore = dataStore;
	}
	
	//when an entity explodes...
	@EventHandler(ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent explodeEvent)
	{		
		List<Block> blocks = explodeEvent.blockList();
		Entity entity = explodeEvent.getEntity();
		
		//FEATURE: creepers don't destroy blocks when they explode near or above sea level
		
		if(GriefPrevention.instance.config_creepersDontDestroySurface && entity instanceof Creeper)
		{
			if(entity.getLocation().getBlockY() > entity.getLocation().getWorld().getSeaLevel() - 7)
			{
				blocks.clear();  //explosion still happens, can damage creatures/players, but no blocks will be destroyed
				return;
			}
		}
		
		//FEATURE: creating an explosion near a claim doesn't damage any of the claimed blocks
	
		Claim claim = null;
		for(int i = 0; i < blocks.size(); i++)  //for each destroyed block
		{
			Block block = blocks.get(i);
			if(block.getType() == Material.AIR) continue;  //if it's air, we don't care
			
			claim = this.dataStore.getClaimAt(block.getLocation(), false, claim); 
			//if the block is claimed, remove it from the list of destroyed blocks
			if(claim != null)
			{
				blocks.remove(i--);
			}
			
			//if the block is not claimed and is a log, trigger the anti-tree-top code
			else if(block.getType() == Material.LOG)
			{
				GriefPrevention.instance.handleLogBroken(block);
			}
		}
	}
	
	//when an entity dies...
	@EventHandler
	public void onEntityDeath(EntityDeathEvent event)
	{
		//FEATURE: when a player is involved in a siege (attacker or defender role)
		//his death will end the siege
		
		LivingEntity entity = event.getEntity();
		if(!(entity instanceof Player)) return;  //only tracking players
		
		Player player = (Player)entity;
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		
		//if involved in a siege
		if(playerData.siegeData != null)
		{
			//end it, with the dieing player being the loser
			this.dataStore.endSiege(playerData.siegeData, null, player.getName());
		}
	}
	
	//when an entity picks up an item
	@EventHandler
	public void onEntityPickup(EntityChangeBlockEvent event)
	{
		//FEATURE: endermen don't steal claimed blocks
		
		//if its an enderman
		if(event.getEntity() instanceof Enderman)
		{
			//and the block is claimed
			if(this.dataStore.getClaimAt(event.getBlock().getLocation(), false, null) != null)
			{
				//he doesn't get to steal it
				event.setCancelled(true);
			}
		}
	}
	
	//when a painting is broken
	@EventHandler(ignoreCancelled = true)
	public void onPaintingBreak(PaintingBreakEvent event)
    {
        //FEATURE: claimed paintings are protected from breakage
		
		//only allow players to break paintings, not anything else (like water and explosions)
		if(!(event instanceof PaintingBreakByEntityEvent))
    	{
        	event.setCancelled(true);
        	return;
    	}
        
        PaintingBreakByEntityEvent entityEvent = (PaintingBreakByEntityEvent)event;
        
        //which claim is the painting in?
        Claim claim = this.dataStore.getClaimAt(event.getPainting().getLocation(), false, null);
        if(claim == null) return;
        
        //who is removing it?
		Entity remover = entityEvent.getRemover();
        
		//again, making sure the breaker is a player
		if(!(remover instanceof Player))
        {
        	event.setCancelled(true);
        	return;
        }
        
        //if the player doesn't have build permission, don't allow the breakage
		Player playerRemover = (Player)entityEvent.getRemover();
        String noBuildReason = claim.allowBuild(playerRemover);
        if(noBuildReason != null)
        {
        	event.setCancelled(true);
        	GriefPrevention.sendMessage(playerRemover, TextMode.Err, noBuildReason);
        }		
    }
	
	//when a painting is placed...
	@EventHandler(ignoreCancelled = true)
	public void onPaintingPlace(PaintingPlaceEvent event)
	{
		//FEATURE: similar to above, placing a painting requires build permission in the claim
	
		//which claim is the painting in?
        Claim claim = this.dataStore.getClaimAt(event.getBlock().getLocation(), false, null);
        if(claim == null) return;
        
        //if the player doesn't have permission, don't allow the placement
		String noBuildReason = claim.allowBuild(event.getPlayer());
        if(noBuildReason != null)
        {
        	event.setCancelled(true);
        	GriefPrevention.sendMessage(event.getPlayer(), TextMode.Err, noBuildReason);
        }	
	}
	
	//when an entity is damaged
	@EventHandler(ignoreCancelled = true)
	public void onEntityDamage (EntityDamageEvent event)
	{
		//only actually interested in entities damaging entities (ignoring environmental damage)
		if(!(event instanceof EntityDamageByEntityEvent)) return;
		
		EntityDamageByEntityEvent subEvent = (EntityDamageByEntityEvent) event;
		
		//determine which player is attacking, if any
		Player attacker = null;
		Entity damageSource = subEvent.getDamager();
		if(damageSource instanceof Player)
		{
			attacker = (Player)damageSource;
		}
		else if(damageSource instanceof Arrow)
		{
			Arrow arrow = (Arrow)damageSource;
			if(arrow.getShooter() instanceof Player)
			{
				attacker = (Player)arrow.getShooter();
			}
		}
		else if(damageSource instanceof ThrownPotion)
		{
			ThrownPotion potion = (ThrownPotion)damageSource;
			if(potion.getShooter() instanceof Player)
			{
				attacker = (Player)potion.getShooter();
			}
		}
		
		//if the attacker is a player and defender is a player (pvp combat)
		if(attacker != null && event.getEntity() instanceof Player)
		{
			//if pvp is disabled, cancel the event
			if(!event.getEntity().getWorld().getPVP())
			{
				event.setCancelled(true);
				return;
			}
			
			//FEATURE: prevent players who very recently participated in pvp combat from hiding inventory to protect it from looting
			//FEATURE: prevent players who are in pvp combat from logging out to avoid being defeated
			Player defender = (Player)(event.getEntity());
					
			PlayerData defenderData = this.dataStore.getPlayerData(((Player)event.getEntity()).getName());
			PlayerData attackerData = this.dataStore.getPlayerData(attacker.getName());
			
			long now = Calendar.getInstance().getTimeInMillis();
			defenderData.lastPvpTimestamp = now;
			defenderData.lastPvpPlayer = attacker.getName();
			attackerData.lastPvpTimestamp = now;
			attackerData.lastPvpPlayer = defender.getName();
			
			//FEATURE: prevent pvp in the first minute after spawn, and prevent pvp when one or both players have no inventory
			
			//otherwise if protecting spawning players
			if(GriefPrevention.instance.config_pvp_protectFreshSpawns)
			{
				if(defenderData.pvpImmune)
				{
					event.setCancelled(true);
					return;
				}
				
				if(attackerData.pvpImmune)
				{
					event.setCancelled(true);
					return;
				}		
			}
		}
		
		//FEATURE: protect claimed animals, boats, minecarts
		//NOTE: animals can be lead with wheat, vehicles can be pushed around.
		//so unless precautions are taken by the owner, a resourceful thief might find ways to steal anyway
		
		//if theft protection is enabled
		if(GriefPrevention.instance.config_claims_preventTheft && event instanceof EntityDamageByEntityEvent)
		{
			//if the entity is an animal or a vehicle
			if (subEvent.getEntity() instanceof Animals || subEvent.getEntity() instanceof Vehicle)
			{
				Claim claim = this.dataStore.getClaimAt(event.getEntity().getLocation(), false, null);
				
				//if it's claimed
				if(claim != null)
				{
					//if damaged by anything other than a player, cancel the event
					if(attacker == null)
					{
						event.setCancelled(true);
					}
					
					//otherwise the player damaging the entity must have permission
					else
					{		
						String noContainersReason = claim.allowContainers(attacker);
						if(noContainersReason != null)
						{
							event.setCancelled(true);
							GriefPrevention.sendMessage(attacker, TextMode.Err, "That belongs to " + claim.getOwnerName() + ".");
						}
					}
				}
			}
		}
	}
}