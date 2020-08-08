/*******************************************************************************************************
 * Copyright (c) 2019 PikaMug
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN
 * NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************************************/

package me.pikamug.gpsquests;

import java.util.ConcurrentModificationException;
import java.util.LinkedList;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.live.bemmamin.gps.Vars;
import com.live.bemmamin.gps.api.GPSAPI;
import com.live.bemmamin.gps.logic.Point;

import me.blackvein.quests.Quest;
import me.blackvein.quests.Quester;
import me.blackvein.quests.Quests;
import me.blackvein.quests.Stage;
import me.blackvein.quests.events.quest.QuestQuitEvent;
import me.blackvein.quests.events.quester.QuesterPostChangeStageEvent;
import me.blackvein.quests.events.quester.QuesterPostCompleteQuestEvent;
import me.blackvein.quests.events.quester.QuesterPostFailQuestEvent;
import me.blackvein.quests.events.quester.QuesterPostStartQuestEvent;

public class GPSQuests extends JavaPlugin {
    private static GPSAPI gpsapi;
    private Quests quests;
    
    private boolean reload = false;
    private FileConfiguration cfg;
    public boolean citizensToInteract;
    public boolean citizensToKill;
    public boolean locationsToReach;
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
                for (final Quester q : quests.getQuesters()) {
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
        cfg = getConfig();
        cfg.options().copyDefaults(true);
        this.saveConfig();
        
        citizensToInteract = cfg.getBoolean("citizens-to-interact", true);
        citizensToKill = cfg.getBoolean("citizens-to-kill", true);
        locationsToReach = cfg.getBoolean("locations-to-reach", true);
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
    }
    
    private class QuestsListener implements Listener {
        @EventHandler
        public void onQuesterPostStartQuest(final QuesterPostStartQuestEvent event) {
            updateGPS(event.getQuest(), event.getQuester());
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
     * Do not call this method multiple times in succession.
     * 
     * @param quest The quest to get objectives for
     * @param quester The quester to have their GPS updated
     * @return true if successful
     */
    public boolean updateGPS(final Quest quest, final Quester quester) {
        final LinkedList<Location> targetLocations = new LinkedList<Location>();
        final Stage stage = quester.getCurrentStage(quest);
        if (stage == null) {
            getLogger().severe("Called updateGPS() with a null stage from quest " + quest.getName());
            return false;
        }
        if (citizensToInteract && stage.getCitizensToInteract() != null && stage.getCitizensToInteract().size() > 0) {
            if (quests.getDependencies().getCitizens() != null) {
                for (final Integer i : stage.getCitizensToInteract()) {
                    targetLocations.add(quests.getDependencies().getNPCLocation(i));
                }
            }
        } else if (citizensToKill && stage.getCitizensToKill() != null && stage.getCitizensToKill().size() > 0) {
            if (quests.getDependencies().getCitizens() != null) {
                for (final Integer i : stage.getCitizensToKill()) {
                    targetLocations.add(quests.getDependencies().getNPCLocation(i));
                }
            }
        } else if (locationsToReach && stage.getLocationsToReach() != null && stage.getLocationsToReach().size() > 0) {
            targetLocations.addAll(stage.getLocationsToReach());
        } else if (itemDeliveryTargets && stage.getItemDeliveryTargets() != null && stage.getItemDeliveryTargets().size() > 0) {
            if (quests.getDependencies().getCitizens() != null) {
                for (final Integer i : stage.getItemDeliveryTargets()) {
                    targetLocations.add(quests.getDependencies().getCitizens().getNPCRegistry().getById(i).getStoredLocation());
                }
            }
        }
        if (targetLocations != null && !targetLocations.isEmpty()) {
            int index = 1;
            final String pointName = "quests-" + quester.getPlayer().getUniqueId().toString() + "-" + quest.getName() + "-" + stage.toString() + "-";
            final Player p = quester.getPlayer();
            for (final Location l : targetLocations) {
                if (l.getWorld().getName().equals(p.getWorld().getName())) {
                    if (!gpsapi.gpsIsActive(p)) {
                        gpsapi.addPoint(pointName + index, l);
                        index++;
                    }
                }
            }
            for (int i = 1 ; i < targetLocations.size(); i++) {
                if (!gpsapi.gpsIsActive(quester.getPlayer())) {
                    gpsapi.connect(pointName + i, pointName + (i + 1), true);
                }
            }
            if (!gpsapi.gpsIsActive(quester.getPlayer())) {
                gpsapi.startGPS(quester.getPlayer(), pointName + (index - 1));
            }
        }
        return targetLocations != null && !targetLocations.isEmpty();
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
    public boolean stopGPS(final Quest quest, final Quester quester) {
        final Player p = quester.getPlayer();
        if (gpsapi.gpsIsActive(p)) {
            gpsapi.stopGPS(p);
            for (final Point point : gpsapi.getAllPoints()) {
                if (point.getName().startsWith("quests-" + p.getUniqueId().toString() + "-" + quest.getName())) {
                    try {
                        gpsapi.removePoint(point.getName());
                    } catch (final ConcurrentModificationException e) {
                        // Throws an exception for some reason, but we have to remove point to avoid duplicates
                    }
                }
            }
            return true;
        }
        return false;
    }
}