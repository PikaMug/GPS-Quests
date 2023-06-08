/*
 * Copyright (c) 2019 PikaMug
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN
 * NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package me.pikamug.gpsquests;

import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.UUID;

import com.live.bemmamin.gps.playerdata.PlayerData;
import me.blackvein.quests.player.IQuester;
import me.blackvein.quests.quests.IQuest;
import me.blackvein.quests.quests.IStage;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.live.bemmamin.gps.Vars;
import com.live.bemmamin.gps.api.GPSAPI;
import com.live.bemmamin.gps.logic.Point;

import me.blackvein.quests.Quester;
import me.blackvein.quests.Quests;
import me.blackvein.quests.enums.ObjectiveType;
import me.blackvein.quests.events.quest.QuestQuitEvent;
import me.blackvein.quests.events.quest.QuestUpdateCompassEvent;
import me.blackvein.quests.events.quester.QuesterPostChangeStageEvent;
import me.blackvein.quests.events.quester.QuesterPostCompleteQuestEvent;
import me.blackvein.quests.events.quester.QuesterPostFailQuestEvent;
import me.blackvein.quests.events.quester.QuesterPostUpdateObjectiveEvent;

public class GPSQuests extends JavaPlugin {
    private GPSAPI gpsapi;
    private Quests quests;
    private long lastUpdated;
    
    private boolean reload = false;
    public boolean npcsToInteract;
    public boolean npcsToKill;
    public boolean locationsToReach;
    public boolean mobsToKillWithin;
    public boolean itemDeliveryTargets;
    
    @Override
    public void onEnable() {
        final PluginManager pm = getServer().getPluginManager();
        
        if (pm.getPlugin("GPS") != null) {
            if (!pm.getPlugin("GPS").isEnabled()) {
                getLogger().warning("GPS was detected, but is not enabled! Fix it to allow linkage.");
                return;
            } else {
                gpsapi = new GPSAPI(this);
                Vars.getInstance().setMaxDistanceToEntry(9999.0);
            }
        }
        if (pm.getPlugin("Quests") != null) {
            if (!pm.getPlugin("Quests").isEnabled()) {
                getLogger().warning("Quests was detected, but is not enabled! Fix it to allow linkage.");
                return;
            } else {
                quests = (Quests) pm.getPlugin("Quests");
            }
        }
        
        getServer().getPluginManager().registerEvents(new ServerListener(), this);  
        getServer().getPluginManager().registerEvents(new QuestsListener(), this);
        
        if (gpsapi != null && quests.isEnabled()) {
            activate();
        }
    }
    
    @Override
    public void onDisable() {
        final PluginManager pm = getServer().getPluginManager();
        
        if (pm.getPlugin("GPS") != null) {
            if (!pm.getPlugin("GPS").isEnabled()) {
                for (final IQuester q : quests.getOnlineQuesters()) {
                    if (q != null) {
                        if (gpsapi.gpsIsActive(q.getPlayer())) {
                            gpsapi.stopGPS(q.getPlayer());
                        }
                    }
                }
            }
        }
    }
    
    private void activate() {
        if (reload) {
            this.reloadConfig();
        } else {
            reload = true;
        }
        final FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);
        cfg.options().header("GPS-Quests configuration");
        this.saveConfig();
        
        npcsToInteract = cfg.getBoolean("npcs-to-interact", true);
        npcsToKill = cfg.getBoolean("npcs-to-kill", true);
        locationsToReach = cfg.getBoolean("locations-to-reach", true);
        mobsToKillWithin = cfg.getBoolean("mobs-to-kill-within", true);
        itemDeliveryTargets = cfg.getBoolean("item-delivery-targets", true);
    }
    
    private class ServerListener implements Listener {
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(final PluginEnableEvent event) {
            final Plugin p = event.getPlugin();
            final String name = p.getDescription().getName();
            if (name.equals("GPS") || name.equals("Quests")) {
                if (gpsapi != null && quests.isEnabled()) {
                    activate();
                }
            }
        }
        
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPlayerRespawnEvent(final PlayerRespawnEvent event) {
            final Quester quester = quests.getQuester(event.getPlayer().getUniqueId());
            if (quester.getCompassTarget() != null) {
                updateGPS(quester.getCompassTarget(), quester);
            } else if (quester.getCurrentQuests().size() == 1) {
                updateGPS(quester.getCurrentQuests().entrySet().iterator().next().getKey(), quester);
            }
        }
    }
    
    private class QuestsListener implements Listener {
        
        @EventHandler
        public void onQuestUpdateCompass(final QuestUpdateCompassEvent event) {
            updateGPS(event.getQuest(), event.getQuester());
        }
        
        @EventHandler
        public void onQuesterPostUpdateObjective(final QuesterPostUpdateObjectiveEvent event) {
            if (event.getObjective().getType() == ObjectiveType.TALK_TO_NPC
                    || event.getObjective().getType() == ObjectiveType.KILL_NPC
                    || event.getObjective().getType() == ObjectiveType.REACH_LOCATION
                    || event.getObjective().getType() == ObjectiveType.KILL_MOB
                    || event.getObjective().getType() == ObjectiveType.DELIVER_ITEM) {
                updateGPS(event.getQuest(), event.getQuester());
            }
        }
        
        @EventHandler
        public void onQuesterPostChangeStage(final QuesterPostChangeStageEvent event) {
            updateGPS(event.getQuest(), event.getQuester());
        }
        
        @EventHandler
        public void onQuesterPostCompleteQuest(final QuesterPostCompleteQuestEvent event) {
            stopGPS(event.getQuest(), event.getQuester());
        }
        
        @EventHandler
        public void onQuesterPostFailQuest(final QuesterPostFailQuestEvent event) {
            stopGPS(event.getQuest(), event.getQuester());
        }
        
        @EventHandler
        public void onQuestQuitEvent(final QuestQuitEvent event) {
            stopGPS(event.getQuest(), event.getQuester());
        }
    }
    
    /**
     * Add location-objective points for GPS and begin navigation.<p>
     * 
     * Avoid call this method multiple times in succession.
     * 
     * @param quest The quest to get objectives for
     * @param quester The quester to have their GPS updated
     * @return true if successful
     */
    public boolean updateGPS(final IQuest quest, final IQuester quester) {
        if (System.currentTimeMillis() - lastUpdated < 500L) {
            return false;
        }
        final IStage stage = quester.getCurrentStage(quest);
        if (stage == null) {
            return false;
        }
        
        int stageIndex = 0;
        for (final Entry<IQuest, Integer> set : quester.getCurrentQuestsTemp().entrySet()) {
            if (set.getKey().getId().equals(quest.getId())) {
                stageIndex = set.getValue();
            }
        }
        
        int objectiveIndex = 0;
        for (final String objective : quester.getCurrentObjectives(quest, false)) {
            if (objective.startsWith(ChatColor.GREEN.toString())) {
                break;
            }
            objectiveIndex++;
        }
        
        final LinkedList<Location> targetLocations = new LinkedList<>();
        if (npcsToInteract && stage.getNpcsToInteract() != null && stage.getNpcsToInteract().size() > 0) {
            if (quests.getDependencies().getCitizens() != null || quests.getDependencies().getZnpcsPlus() != null) {
                for (final UUID uuid : stage.getNpcsToInteract()) {
                    targetLocations.add(quests.getDependencies().getNpcLocation(uuid));
                }
            }
        } else if (npcsToKill && stage.getNpcsToKill() != null && stage.getNpcsToKill().size() > 0) {
            if (quests.getDependencies().getCitizens() != null || quests.getDependencies().getZnpcsPlus() != null) {
                for (final UUID uuid : stage.getNpcsToKill()) {
                    targetLocations.add(quests.getDependencies().getNpcLocation(uuid));
                }
            }
        } else if (locationsToReach && stage.getLocationsToReach() != null && stage.getLocationsToReach().size() > 0) {
            targetLocations.addAll(stage.getLocationsToReach());
        } else if (mobsToKillWithin && stage.getLocationsToKillWithin() != null && stage.getLocationsToKillWithin().size() > 0) {
            targetLocations.addAll(stage.getLocationsToKillWithin());
        } else if (itemDeliveryTargets && stage.getItemDeliveryTargets() != null && stage.getItemDeliveryTargets().size() > 0) {
            if (quests.getDependencies().getCitizens() != null) {
                for (final UUID uuid : stage.getItemDeliveryTargets()) {
                    targetLocations.add(quests.getDependencies().getCitizens().getNPCRegistry().getByUniqueId(uuid).getStoredLocation());
                }
            }
        }
        if (!targetLocations.isEmpty()) {
            final Player p = quester.getPlayer();
            final UUID uuid = p.getUniqueId();
            final String pointName = "quests-" + uuid + "-" + quest.getId() + "-" + stageIndex + "-" + objectiveIndex;
            if (PlayerData.getPlayerData(uuid).getPath() == null
                    || PlayerData.getPlayerData(uuid).getPath().getCurrentTarget() == null
                    || !PlayerData.getPlayerData(uuid).getPath().getCurrentTarget().getName().equals(pointName)) {
                try {
                    if (objectiveIndex < targetLocations.size()) {
                        gpsapi.addPoint(pointName, targetLocations.get(objectiveIndex));
                    }
                } catch (final IllegalArgumentException e) {
                    // Player may have died or is re-taking quest
                }
                gpsapi.startGPS(p, pointName);
                lastUpdated = System.currentTimeMillis();
            }
        }
        return !targetLocations.isEmpty();
    }
    
    /**
     * Stop GPS navigation and attempt to remove objective points.<p>
     * 
     * Do not call this method multiple times in succession.
     * 
     * @param quest The quest to remove objectives for
     * @param quester The quester to have their GPS updated
     * @return true if successful
     */
    public boolean stopGPS(final IQuest quest, final IQuester quester) {
        final Player p = quester.getPlayer();
        if (gpsapi.gpsIsActive(p)) {
            gpsapi.stopGPS(p);
            for (final Point point : gpsapi.getAllPoints()) {
                if (point.getName().startsWith("quests-" + p.getUniqueId() + "-" + quest.getId())) {
                    try {
                        gpsapi.removePoint(point.getName());
                    } catch (final ConcurrentModificationException e) {
                        // Not ideal, but we have to remove point to avoid duplicates
                    }
                }
            }
            return true;
        }
        return false;
    }
}
