package me.azenet.UHPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

import me.azenet.UHPlugin.task.TeamStartTask;
import me.azenet.UHPlugin.task.UpdateTimerTask;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class UHGameManager {
	
	private UHPlugin p = null;
	private UHTeamManager tm = null;
	private Random random = null;
	
	private Boolean damageIsOn = false;
	private UHScoreboardManager scoreboardManager = null;

	private LinkedList<Location> loc = new LinkedList<Location>();
	private HashSet<String> alivePlayers = new HashSet<String>();
	private HashSet<String> spectators = new HashSet<String>();
	private Map<String,Location> deathLocations = new HashMap<String,Location>();
	
	private Integer alivePlayersCount = 0;
	private Integer aliveTeamsCount = 0;
	
	private Boolean gameWithTeams = true;
	
	// Used for the slow start.
	private Boolean slowStartInProgress = false;
	private Boolean slowStartTPFinished = false;
	
	private Boolean gameRunning = false;
	private Integer episode = 0;
	private Integer minutesLeft = 0;
	private Integer secondsLeft = 0;
	
	private Long episodeStartTime = 0L;
	
	
	public UHGameManager(UHPlugin plugin) {
		this.p = plugin;
		this.tm = plugin.getTeamManager();
		
		this.random = new Random();
	}


	public void initEnvironment() {
		p.getServer().getWorlds().get(0).setGameRuleValue("doDaylightCycle", "false");
		p.getServer().getWorlds().get(0).setTime(6000L);
		p.getServer().getWorlds().get(0).setStorm(false);
		p.getServer().getWorlds().get(0).setDifficulty(Difficulty.PEACEFUL);
	}
	
	public void initScoreboard() {
		// Strange: if the scoreboard manager is instanced in the constructor, when the
		// scoreboard manager try to get the game manager through UHPlugin.getGameManager(),
		// the value returned is "null"...
		// This is why we initializes the scoreboard manager later, in this method.
		this.scoreboardManager = new UHScoreboardManager(p);
	}


	/**
	 * Starts the game, the standard way.
	 *  - Teleports the teams
	 *  - Changes the gamemode, reset the life, clear inventories, etc.
	 *  - Launches the timer
	 *  
	 * @param sender The player who launched the game.
	 * @param slow If true, the slow mode is enabled.
	 * With the slow mode, the players are, at first, teleported team by team with a 3-seconds delay,
	 * and with the fly.
	 * Then, the fly is removed and the game starts.
	 * 
	 * @throws RuntimeException if the game is already started.
	 */
	public void start(CommandSender sender, Boolean slow) {
		
		if(isGameRunning()) {
			throw new RuntimeException("The game is already started!");
		}
		
		/** Initialization of the players and the teams **/
		
		// We adds all the connected players (excepted spectators) to a list of alive players.
		// Also, the spectator mode is enabled/disabled if needed.
		alivePlayers.clear();
		for(final Player player : p.getServer().getOnlinePlayers()) {
			if(!spectators.contains(player.getName())) {
				alivePlayers.add(player.getName());
				
				if(p.getSpectatorPlusIntegration().isSPIntegrationEnabled()) {
					p.getSpectatorPlusIntegration().getSPAPI().setSpectating(player, false);
				}
			}
			else {
				if(p.getSpectatorPlusIntegration().isSPIntegrationEnabled()) {
					p.getSpectatorPlusIntegration().getSPAPI().setSpectating(player, true);
				}
			}
		}
		this.alivePlayersCount = alivePlayers.size();
		
		
		// No team? We creates a team per player.
		if(tm.getTeams().size() == 0) {
			this.gameWithTeams = false;
			
			for(final Player player : p.getServer().getOnlinePlayers()) {
				if(!spectators.contains(player.getName())) {
					UHTeam team = new UHTeam(player.getName(), player.getName(), ChatColor.WHITE, this.p);
					team.addPlayer(player);
					tm.addTeam(team);
				}
			}
		}
		// With teams? We adds players without teams to a solo team.
		else {
			this.gameWithTeams = true;
			
			for(final Player player : p.getServer().getOnlinePlayers()) {
				if(tm.getTeamForPlayer(player) == null && !spectators.contains(player.getName())) {
					UHTeam team = new UHTeam(player.getName(), player.getName(), ChatColor.WHITE, this.p);
					team.addPlayer(player);
					tm.addTeam(team);
				}
			}
		}
		
		
		this.aliveTeamsCount = tm.getTeams().size();
		
		p.getLogger().info("[start] " + aliveTeamsCount + " teams");
		p.getLogger().info("[start] " + alivePlayersCount + " players");
		
		if(loc.size() < tm.getTeams().size()) {
			sender.sendMessage(ChatColor.RED + "Unable to start the game: not enough teleportation spots.");
			return;
		}
		
		/** Teleportation **/
		
		// Standard mode
		if(slow == false) {
			LinkedList<Location> unusedTP = loc;
			for (final UHTeam t : tm.getTeams()) {
				final Location lo = unusedTP.get(this.random.nextInt(unusedTP.size()));
				
				BukkitRunnable teamStartTask = new TeamStartTask(p, t, lo);
				teamStartTask.runTaskLater(p, 10L);
				
				unusedTP.remove(lo);
			}
			
			
			this.startEnvironment();
			this.startTimer();
			this.scheduleDamages();
			this.finalizeStart();
		}
		
		// Slow mode
		else {
			slowStartInProgress = true;
			
			// Used to display the number of teams, players... in the scoreboard instead of 0
			// while the players are teleported.
			scoreboardManager.updateScoreboard();
			
			// A simple information, because this start is slower (yeah, Captain Obvious here)
			
			p.getServer().broadcastMessage(ChatColor.LIGHT_PURPLE + "Téléportation des joueurs en cours... Merci de patienter.");
			
			
			// TP
			
			LinkedList<Location> unusedTP = loc;
			Integer teamsTeleported = 1;
			Integer delayBetweenTP = p.getConfig().getInt("slow-start.delayBetweenTP");
			
			for (final UHTeam t : tm.getTeams()) {
				final Location lo = unusedTP.get(this.random.nextInt(unusedTP.size()));
				
				BukkitRunnable teamStartTask = new TeamStartTask(p, t, lo, true, sender, teamsTeleported);
				teamStartTask.runTaskLater(p, 20L * teamsTeleported * delayBetweenTP);
				
				teamsTeleported++;

				
				unusedTP.remove(lo);
			}
			
			// The end is handled by this.finalizeStartSlow().
		}
	}
	
	/**
	 * Finalizes the start of the game, with the slow mode.
	 * Removes the fly and ends the start (environment, timer...)
	 * 
	 * @param sender
	 */
	public void finalizeStartSlow(CommandSender sender) {
		
		if(!this.slowStartInProgress) {
			sender.sendMessage(ChatColor.RED + "Please execute " + ChatColor.GOLD + "/uh start slow" + ChatColor.RED + " before.");
			return;
		}
		
		if(!this.slowStartTPFinished) {
			sender.sendMessage(ChatColor.RED + "Please wait while the players are teleported.");
			return;
		}
		
		
		// The fly is removed to everyone
		for(Player player : p.getServer().getOnlinePlayers()) {
			player.setFlying(false);
			player.setAllowFlight(false);
		}
		
		// The environment is initialized, the game is started.
		this.startEnvironment();
		this.startTimer();
		this.scheduleDamages();
		this.finalizeStart();
		
		this.slowStartInProgress = false;
	}
	
	/**
	 * Initializes the environment at the beginning of the game.
	 */
	public void startEnvironment() {
		World w = p.getServer().getWorlds().get(0);
		
		w.setGameRuleValue("doDaylightCycle", ((Boolean) p.getConfig().getBoolean("daylightCycle.do")).toString());
		w.setGameRuleValue("keepInventory", ((Boolean) false).toString()); // Just in case...
		
		w.setTime(p.getConfig().getLong("daylightCycle.time"));
		w.setStorm(false);
		w.setDifficulty(Difficulty.HARD);
	}
	
	/**
	 * Launches the timer by launching the task that updates the scoreboard every second.
	 */
	private void startTimer() {
		this.episode = 1;
		this.minutesLeft = getEpisodeLength();
		this.secondsLeft = 0;
		
		this.episodeStartTime = System.currentTimeMillis();
		
		BukkitRunnable updateTimer = new UpdateTimerTask(p);
		updateTimer.runTaskTimer(p, 20L, 20L);
	}
	
	/**
	 * Enables the damages 30 seconds (600 ticks) later.
	 */
	private void scheduleDamages() {
		// 30 seconds later, damages are enabled.
		Bukkit.getScheduler().runTaskLater(p, new BukkitRunnable() {
			@Override
			public void run() {
				damageIsOn = true;
				p.getLogger().info("Immunity ended.");
			}
		}, 600L);
	}
	
	/**
	 * Broadcast the start message and change the state of the game.
	 */
	private void finalizeStart() {
		Bukkit.getServer().broadcastMessage(ChatColor.GREEN + "--- GO ---");
		this.gameRunning = true;
	}
	
	public Boolean getSlowStartInProgress() {
		return this.slowStartInProgress;
	}
	
	public void setSlowStartTPFinished(Boolean finished) {
		this.slowStartTPFinished = finished;
	}
	
	
	
	
	
	public void updateTimer() {
		if(p.getConfig().getBoolean("episodes.syncTimer")) {
			long timeSinceStart = System.currentTimeMillis() - this.episodeStartTime;
			long diffSeconds = timeSinceStart / 1000 % 60;
			long diffMinutes = timeSinceStart / (60 * 1000) % 60;
			
			if(diffMinutes >= this.getEpisodeLength()) {
				shiftEpisode();
			}
			else {
				minutesLeft = (int) (this.getEpisodeLength() - diffMinutes) - 1;
				secondsLeft = (int) (60 - diffSeconds) - 1;
			}
		}
		else {
			secondsLeft--;
			if (secondsLeft == -1) {
				minutesLeft--;
				secondsLeft = 59;
			}
			if (minutesLeft == -1) {
				shiftEpisode();
			}
		}
	}
	
	public void updateAliveCounters() {
		this.alivePlayersCount = alivePlayers.size();
		this.aliveTeamsCount = getAliveTeams().size();
		
		this.scoreboardManager.updateScoreboard();
	}
	
	/**
	 * Shifts an episode.
	 * 
	 * @param shifter The player who shifts the episode, an empty string if the episode is shifted because the timer is up.
	 */
	public void shiftEpisode(String shifter) {
		String message = ChatColor.AQUA + "-------- Fin de l'épisode " + episode;
		if(!shifter.equals("")) {
			message += " [forcé par " + shifter + "]";
		}
		message += " --------";
		p.getServer().broadcastMessage(message);
		
		this.episode++;
		this.minutesLeft = getEpisodeLength();
		this.secondsLeft = 0;
		
		this.episodeStartTime = System.currentTimeMillis();
	}
	
	/**
	 * Shift an episode because the timer is up.
	 */
	public void shiftEpisode() {
		shiftEpisode("");
	}

	
	/**
	 * Resurrect a player
	 * 
	 * @param player
	 * @return true if the player was dead, false otherwise.
	 */
	public boolean resurrect(Player player) {
		if(!this.isPlayerDead(player.getName())) {
			return false;
		}
		
		this.alivePlayers.add(player.getName());
		this.updateAliveCounters();
		
		if(p.getSpectatorPlusIntegration().isSPIntegrationEnabled()) {
			p.getSpectatorPlusIntegration().getSPAPI().setSpectating(player, false);
		}
		
		this.p.getServer().broadcastMessage(ChatColor.GOLD + player.getName() + " returned from the dead!");
		
		return true;
	}
	
	/**
	 * This method saves the location of the death of a player.
	 * 
	 * @param player
	 * @param location
	 */
	public void addDeathLocation(Player player, Location location) {
		deathLocations.put(player.getName(), location);
	}
	
	/**
	 * This method removes the stored death location.
	 * @param player
	 */
	public void removeDeathLocation(Player player) {
		deathLocations.remove(player.getName());
	}
	
	/**
	 * This method returns the stored death location.
	 * 
	 * @param player
	 * @return Location
	 */
	public Location getDeathLocation(Player player) {
		if(deathLocations.containsKey(player.getName())) {
			return deathLocations.get(player.getName());
		}
		
		return null;
	}
	
	/**
	 * This method returns true if a death location is stored for the given player.
	 * 
	 * @param player
	 * @return boolean
	 */
	public boolean hasDeathLocation(Player player) {
		return deathLocations.containsKey(player.getName());
	}
	
	/**
	 * Find a safe spot where teleport the player, and teleport the player to that spot.
	 * If a spot is not found, the player is not teleported, except if the force arg is set to true.
	 * 
	 * Inspiration took in the WorldBorder plugin.
	 * 
	 * @param player
	 * @param location
	 * @param force If true the player will be teleported to the exact given location if there is no safe spot.
	 * @return true if the player was effectively teleported.
	 */
	public boolean safeTP(Player player, Location location, boolean force) {
		// If the target is safe, let's go
		if(isSafeSpot(location)) {
			player.teleport(location);
			return true;
		}
		
		// If the teleportation is forced, let's go
		if(force) {
			player.teleport(location);
			return true;
		}
		
		// We try to find a spot above or below the target (this is probably the good solution, because
		// if the spot is obstrued, because this is mainly used to teleport players back after their
		// death, the cause is likely to be a falling block or an arrow shot during a fall).
		
		Location safeSpot = null;
		// Max height (thx to WorldBorder)
		final int maxHeight = (location.getWorld().getEnvironment() == World.Environment.NETHER) ? 125 : location.getWorld().getMaxHeight() - 2;
		
		for(int yGrow = (int) location.getBlockY(), yDecr = (int) location.getBlockY(); yDecr >= 1 || yGrow <= maxHeight; yDecr--, yGrow++) {
			// Above?
			if(yGrow < maxHeight) {
				Location spot = new Location(location.getWorld(), location.getBlockX(), yGrow, location.getBlockZ());
				if(isSafeSpot(spot)) {
					safeSpot = spot;
					break;
				}
			}
			
			// Below?
			if(yDecr > 1 && yDecr != yGrow) {
				Location spot = new Location(location.getWorld(), location.getX(), yDecr, location.getZ());
				if(isSafeSpot(spot)) {
					safeSpot = spot;
					break;
				}
			}
		}
		
		// A spot was found, let's teleport.
		if(safeSpot != null) {
			player.teleport(safeSpot);
			return true;
		}
		// No spot found; the teleportation is cancelled.
		else {
			return false;
		}
	}
	
	public boolean safeTP(Player player, Location location) {
		return safeTP(player, location, false);
	}
	
	/**
	 * Checks if a given location is safe.
	 * A safe location is a location with two breathable blocks (aka transparent block or water)
	 * over something solid
	 * 
	 * @param location
	 * @return true if the location is safe.
	 */
	private boolean isSafeSpot(Location location) {		
		Block blockCenter = location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
		Block blockAbove = location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
		Block blockBelow = location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
		
		if((blockCenter.getType().isTransparent() || blockCenter.isLiquid())
				&& (blockAbove.getType().isTransparent() || blockAbove.isLiquid())) {
			// two breathable blocks: ok
			
			if(blockBelow.getType().isSolid()) {
				// The block below is solid 
				return true;
			}
			return false;
		}
		return false;
	}
	
	
	/**
	 * Adds a spectator. When the game is started, spectators are ignored 
	 * and the spectator mode is enabled if SpectatorPlus is present.
	 * 
	 * @param player The player to register as a spectator.
	 */
	public void addSpectator(Player player) {
		spectators.add(player.getName());
		tm.removePlayerFromTeam(player);
	}
	
	public void removeSpectator(Player player) {
		spectators.remove(player.getName());
	}
	
	public HashSet<String> getSpectators() {
		return spectators;
	}
	
	
	/**
	 * Adds a spawn point.
	 * 
	 * @param x
	 * @param z
	 */
	public void addLocation(int x, int z) {
		loc.add(new Location(p.getServer().getWorlds().get(0), x, p.getServer().getWorlds().get(0).getHighestBlockYAt(x,z)+120, z));
	}
	
	public boolean isGameRunning() {
		return gameRunning;
	}
	
	public boolean isGameWithTeams() {
		return gameWithTeams;
	}
	
	public boolean isTakingDamage() {
		return damageIsOn;
	}

	public boolean isPlayerDead(String name) {
		return !alivePlayers.contains(name);
	}
	
	public void addDead(String name) {
		alivePlayers.remove(name);
	}

	private ArrayList<UHTeam> getAliveTeams() {
		ArrayList<UHTeam> aliveTeams = new ArrayList<UHTeam>();
		for (UHTeam t : tm.getTeams()) {
			for (Player p : t.getPlayers()) {
				if (p.isOnline() && !aliveTeams.contains(t)) aliveTeams.add(t);
			}
		}
		return aliveTeams;
	}

	public UHScoreboardManager getScoreboardManager() {
		return scoreboardManager;
	}
	
	public Integer getEpisodeLength() {
		return p.getConfig().getInt("episodes.length");
	}

	public Integer getAlivePlayersCount() {
		return alivePlayersCount;
	}

	public Integer getAliveTeamsCount() {
		return aliveTeamsCount;
	}

	public Integer getEpisode() {
		return episode;
	}

	public Integer getMinutesLeft() {
		return minutesLeft;
	}

	public Integer getSecondsLeft() {
		return secondsLeft;
	}
}
