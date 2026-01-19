package com.specialattacktimers;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.NPC;
import net.runelite.api.SpriteID;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;

/**
 * Special Attack Regen Timer Plugin for the Colosseum, Doom of Mokhaoitl, and Theatre of Blood.
 *
 * This plugin tracks special attack regeneration timing in content where the timer
 * pauses between encounters (waves/delves/rooms) and resets when a new encounter starts,
 * matching the game's behavior.
 * Supports Lightbearer ring which halves the regen time (25 ticks instead of 50).
 *
 * Colosseum transitions (via chat messages):
 * - "Wave X completed!": Wave finished, timer stops until next wave
 * - "Wave: X": New wave starting, timer resets to 30 seconds (or 15 with Lightbearer)
 * - "Search the chest nearby": Run over (claimed rewards), timer continues
 *
 * Doom of Mokhaoitl transitions:
 * - "Delve level: X duration: ..." (chat): Delve finished, timer stops
 * - Boss NPC spawns: Timer resumes and resets (more accurate than chat message)
 *
 * Theatre of Blood transitions:
 * - "Wave 'Room Name' (...) complete!" (chat): Room finished, timer stops
 * - Room boss NPC spawns: Timer resumes and resets
 * - "Theatre of Blood total completion time:" (chat): Run completed, timer resumes
 *
 * The timer also resets whenever spec energy actually increases,
 * preventing drift between the overlay and actual game mechanics.
 */
@Slf4j
@PluginDescriptor(
	name = "Special Attack Timers",
	description = "Properly tracks special attack regeneration and surge potion cooldown in wave-based content (Colosseum, Doom of Mokhaoitl, Theatre of Blood)",
	tags = {"colosseum", "special", "attack", "spec", "timer", "regen", "lightbearer", "doom", "mokhaoitl", "delve", "tob", "theatre", "blood", "surge", "potion"}
)
public class SpecialAttackTimersPlugin extends Plugin
{
	/**
	 * Pattern to match the wave start message in chat.
	 * Matches "Wave: 1" through "Wave: 12".
	 */
	private static final Pattern WAVE_START_PATTERN = Pattern.compile("^Wave: (1[0-2]|[1-9])$");

	/**
	 * Pattern to match the wave completed message in chat.
	 * Matches "Wave 1 completed!" through "Wave 12 completed!" (with duration info after).
	 * This is when spec regen stops until the next wave.
	 */
	private static final Pattern WAVE_COMPLETED_PATTERN = Pattern.compile("^Wave (1[0-2]|[1-9]) completed!.*");

	/**
	 * Partial message that appears when the player claims rewards (run is over).
	 * Spec regen continues normally after this.
	 */
	private static final String CLAIM_REWARDS_MESSAGE = "Search the chest nearby";

	/**
	 * Pattern to match the delve completed message in Doom of Mokhaoitl.
	 * Matches "Delve level: X duration: ..." which indicates delve was completed.
	 * This is when spec regen stops. Timer resumes when the boss NPC spawns.
	 */
	private static final Pattern DELVE_COMPLETED_PATTERN = Pattern.compile("^Delve level: \\d+ duration:.*");

	/**
	 * Message that appears when drinking a surge potion (restores 25% spec).
	 * We need to ignore the spec increase from this to avoid resetting the timer.
	 */
	private static final String SURGE_POTION_MESSAGE = "You drink some of your surge potion.";

	/**
	 * Message that appears when surge potion cooldown expires.
	 * Used to clear the cooldown timer.
	 */
	private static final String SURGE_COOLDOWN_EXPIRED_MESSAGE = "You now feel capable of drinking another dose of surge potion.";

	/**
	 * Spec energy restored by surge potion (25% = 250 internal units).
	 */
	private static final int SURGE_POTION_RESTORE = 250;

	/**
	 * Message that appears when Death Charge triggers (restores 15% spec on kill).
	 * We need to ignore the spec increase from this to avoid resetting the timer.
	 */
	private static final String DEATH_CHARGE_MESSAGE = "Some of your special attack energy has been restored";

	/**
	 * Spec energy restored by Death Charge (15% = 150 internal units).
	 */
	private static final int DEATH_CHARGE_RESTORE = 150;

	/**
	 * Surge potion cooldown duration in ticks (5 minutes = 300 seconds = 500 ticks).
	 */
	public static final int SURGE_COOLDOWN_TICKS = 500;

	/**
	 * Surge potion cooldown duration (5 minutes).
	 * Using wall-clock time for accurate display like RuneLite's timer system.
	 */
	public static final Duration SURGE_COOLDOWN_DURATION = Duration.ofMillis(SURGE_COOLDOWN_TICKS * 600L);

	// TOB

	/**
	 * Pattern to match TOB room completion messages.
	 * Matches "Wave 'Room Name' (...) complete!" for all rooms.
	 */
	private static final Pattern TOB_ROOM_COMPLETED_PATTERN = Pattern.compile("^Wave '.*' \\(.*\\) complete!.*");

	/**
	 * Pattern to match TOB completion message (after Verzik).
	 */
	private static final Pattern TOB_COMPLETION_PATTERN = Pattern.compile("^Theatre of Blood total completion time:.*");

	/**
	 * Template region IDs for TOB boss rooms.
	 * TOB is instanced, so we use WorldPoint.fromLocalInstance() to convert
	 * the instanced coordinates to template region IDs.
	 * Based on tob-qol plugin: https://github.com/damencs/tob-qol
	 */
	private static final Set<Integer> TOB_BOSS_ROOM_REGIONS = Set.of(
		12613,  // Maiden
		13125,  // Bloat
		13122,  // Nylocas
		13123,  // Sotetseg (boss room)
		13379,  // Sotetseg (maze)
		12612,  // Xarpus
		12611   // Verzik
	);

	/**
	 * TOB lobby region ID (template).
	 */
	private static final int TOB_LOBBY_REGION = 12869;

	/**
	 * Bloat region ID (template). Bloat region includes a hallway before the combat area,
	 * so we use coordinate-based detection instead of region-based for this room.
	 */
	private static final int TOB_BLOAT_REGION = 13125;

	/**
	 * Bloat barrier tile coordinates (template coordinates).
	 * The barrier tiles are at X = 3303, Y = 4446-4449.
	 * Passing through these tiles (X <= 3303 at these Y values) means entering the combat area.
	 */
	private static final int BLOAT_BARRIER_X = 3303;
	private static final int BLOAT_BARRIER_MIN_Y = 4446;
	private static final int BLOAT_BARRIER_MAX_Y = 4449;

	/**
	 * Nylocas region ID (template). Like Bloat, Nylocas has a hallway before combat area.
	 */
	private static final int TOB_NYLOCAS_REGION = 13122;

	/**
	 * Nylocas barrier tile coordinates (template coordinates).
	 * The barrier tiles are at X = 3295-3296, Y = 4254.
	 */
	private static final int NYLOCAS_BARRIER_MIN_X = 3295;
	private static final int NYLOCAS_BARRIER_MAX_X = 3296;
	private static final int NYLOCAS_BARRIER_Y = 4254;

	/**
	 * Sotetseg region ID (template). Like Bloat/Nylocas, uses coordinate-based detection.
	 */
	private static final int TOB_SOTETSEG_REGION = 13123;

	/**
	 * Sotetseg barrier tile coordinates (template coordinates).
	 * The barrier tiles are at X = 3278-3281, Y = 4308.
	 */
	private static final int SOTETSEG_BARRIER_MIN_X = 3278;
	private static final int SOTETSEG_BARRIER_MAX_X = 3281;
	private static final int SOTETSEG_BARRIER_Y = 4308;

	/**
	 * Xarpus region ID (template). Uses coordinate-based detection.
	 */
	private static final int TOB_XARPUS_REGION = 12612;

	/**
	 * Xarpus barrier tile coordinates (template coordinates).
	 * The barrier tiles are at X = 3169-3171, Y = 4380.
	 */
	private static final int XARPUS_BARRIER_MIN_X = 3169;
	private static final int XARPUS_BARRIER_MAX_X = 3171;
	private static final int XARPUS_BARRIER_Y = 4380;

	/**
	 * Verzik region ID (template). Uses NPC spawn detection (ID 8370).
	 */
	private static final int TOB_VERZIK_REGION = 12611;

	/**
	 * Verzik NPC ID when the fight starts (transforms from 14796 to 8370).
	 */
	private static final int VERZIK_FIGHT_START_NPC_ID = 8370;

	/**
	 * Special attack regenerates 10% every 30 seconds (50 game ticks).
	 * Each game tick is 0.6 seconds.
	 */
	public static final int SPEC_REGEN_TICKS = 50;

	/**
	 * With Lightbearer equipped, spec regenerates twice as fast (25 ticks).
	 */
	public static final int LIGHTBEARER_REGEN_TICKS = SPEC_REGEN_TICKS / 2;

	/**
	 * Maximum special attack energy (displayed as 100%, stored as 1000 internally).
	 */
	private static final int MAX_SPEC_ENERGY = 1000;

	@Inject
	private Client client;

	@Inject
	private SpecialAttackTimersConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SpecialAttackTimersCircleOverlay circleOverlay;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ItemManager itemManager;

	/**
	 * The infobox instance, created when useInfoBox config is enabled.
	 */
	private SpecialAttackTimersInfoBox infoBox;

	/**
	 * Whether we are currently between waves (Minimus is present).
	 * When true, the spec regen timer is stopped and will reset when wave starts.
	 */
	@Getter
	private boolean betweenWaves = false;

	/**
	 * Ticks remaining until the next spec regen tick.
	 * Counts down from SPEC_REGEN_TICKS (50) to 0, then resets.
	 */
	@Getter
	private int ticksUntilRegen = SPEC_REGEN_TICKS;

	/**
	 * Last known special attack energy value (0-1000).
	 * Used to detect when spec has regenerated or been used.
	 */
	private int lastSpecEnergy = -1;

	/**
	 * Whether the player is wearing a Lightbearer ring.
	 * When true, spec regenerates twice as fast (25 ticks instead of 50).
	 */
	@Getter
	private boolean wearingLightbearer = false;

	/**
	 * Game tick until which spec increases should be ignored (for surge potion, death charge, etc.).
	 * Set to client.getTickCount() + 2 when a spec restore message is detected.
	 * A value of -1 means no grace period is active.
	 */
	private int ignoreSpecIncreaseUntilTick = -1;

	/**
	 * Expected spec restore amount from the detected effect (surge potion or death charge).
	 * Used to detect if natural regen also occurred during the grace period.
	 */
	private int expectedSpecRestoreAmount = 0;

	/**
	 * Whether the player is currently inside Theatre of Blood.
	 * Used to determine if TOB room detection should be active.
	 */
	private boolean insideTob = false;

	/**
	 * Whether the player is currently between TOB rooms.
	 * Used to pause the surge cooldown timer (but not spec regen).
	 */
	private boolean tobBetweenRooms = false;

	/**
	 * Last known region ID, used to detect room transitions in TOB.
	 */
	private int lastRegionId = -1;

	/**
	 * Whether we have already triggered combat area entry for the current room.
	 * Prevents re-triggering after room completion while still in the same region.
	 * Reset when entering a new region or when a room completes.
	 */
	private boolean combatAreaEnteredThisRoom = false;

	/**
	 * Wall-clock time when the surge potion cooldown ends.
	 * Null when no cooldown is active or when paused.
	 */
	private Instant surgeEndTime = null;

	/**
	 * Remaining surge cooldown duration when paused (between waves/rooms).
	 * Null when not paused or no cooldown is active.
	 */
	private Duration surgePausedRemaining = null;

	/**
	 * The surge potion cooldown infobox instance.
	 */
	private SurgePotionInfoBox surgeInfoBox;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Special Attack Timers plugin started");
		overlayManager.add(circleOverlay);
		updateInfoBox();
		updateSurgeInfoBox();
		resetState();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Special Attack Timers plugin stopped");
		overlayManager.remove(circleOverlay);
		removeInfoBox();
		removeSurgeInfoBox();
		resetState();
	}

	/**
	 * Updates the infobox visibility based on config.
	 */
	private void updateInfoBox()
	{
		if (config.showInfoBox())
		{
			addInfoBox();
		}
		else
		{
			removeInfoBox();
		}
	}

	/**
	 * Creates and adds the infobox if it doesn't exist.
	 * Uses async sprite loading to ensure the image is available before adding.
	 */
	private void addInfoBox()
	{
		if (infoBox == null)
		{
			infoBox = new SpecialAttackTimersInfoBox(this, config);
			// Load the sprite asynchronously - only add to manager once image is ready
			spriteManager.getSpriteAsync(SpriteID.MINIMAP_ORB_SPECIAL_ICON, 0, img ->
			{
				if (img != null && infoBox != null)
				{
					infoBox.setImage(img);
					infoBoxManager.addInfoBox(infoBox);
				}
			});
		}
	}

	/**
	 * Removes the infobox if it exists.
	 */
	private void removeInfoBox()
	{
		if (infoBox != null)
		{
			infoBoxManager.removeInfoBox(infoBox);
			infoBox = null;
		}
	}

	/**
	 * Updates the surge infobox visibility based on config.
	 */
	private void updateSurgeInfoBox()
	{
		if (config.showSurgeInfoBox())
		{
			addSurgeInfoBox();
		}
		else
		{
			removeSurgeInfoBox();
		}
	}

	/**
	 * Creates and adds the surge infobox if it doesn't exist.
	 * Uses the surge potion item image.
	 */
	private void addSurgeInfoBox()
	{
		if (surgeInfoBox == null)
		{
			surgeInfoBox = new SurgePotionInfoBox(this, config);
			// Use surge potion item image
			surgeInfoBox.setImage(itemManager.getImage(ItemID.SURGE_POTION1));
			infoBoxManager.addInfoBox(surgeInfoBox);
		}
	}

	/**
	 * Removes the surge infobox if it exists.
	 */
	private void removeSurgeInfoBox()
	{
		if (surgeInfoBox != null)
		{
			infoBoxManager.removeInfoBox(surgeInfoBox);
			surgeInfoBox = null;
		}
	}

	/**
	 * Resets all tracking state to initial values.
	 * Called on startup, shutdown, and login/logout transitions.
	 */
	private void resetState()
	{
		betweenWaves = false;
		ticksUntilRegen = SPEC_REGEN_TICKS;
		lastSpecEnergy = -1;
		wearingLightbearer = false;
		ignoreSpecIncreaseUntilTick = -1;
		expectedSpecRestoreAmount = 0;
		insideTob = false;
		tobBetweenRooms = false;
		lastRegionId = -1;
		combatAreaEnteredThisRoom = false;
		surgeEndTime = null;
		surgePausedRemaining = null;
	}

	@Provides
	SpecialAttackTimersConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SpecialAttackTimersConfig.class);
	}

	/**
	 * Handles config changes to toggle infobox visibility.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"specialattacktimers".equals(event.getGroup()))
		{
			return;
		}

		if ("showInfoBox".equals(event.getKey()))
		{
			updateInfoBox();
		}
		else if ("showSurgeInfoBox".equals(event.getKey()))
		{
			updateSurgeInfoBox();
		}
	}

	/**
	 * Handles game state changes (login, logout, hopping).
	 * Resets state when transitioning to avoid stale data.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		// Reset state on logout or hopping to prevent carrying stale data
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			resetState();
		}
		else if (state == GameState.LOGGED_IN)
		{
			// Initialize lastSpecEnergy on login
			lastSpecEnergy = getSpecEnergy();
		}
	}

	/**
	 * Handles varbit changes to detect entering/leaving Theatre of Blood.
	 * TOB varbit is 2 or 3 when inside, other values when outside.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int tobVar = client.getVarbitValue(Varbits.THEATRE_OF_BLOOD);
		boolean nowInsideTob = tobVar == 2 || tobVar == 3;

		if (nowInsideTob != insideTob)
		{
			insideTob = nowInsideTob;

			if (insideTob)
			{
				// Entered TOB - check if already in a boss room
				// Use template region ID for instanced content
				int currentRegion = getTemplateRegionId();

				if (TOB_BOSS_ROOM_REGIONS.contains(currentRegion))
				{
					// Already in a boss room, don't pause
					tobBetweenRooms = false;
				}
				else
				{
					// In lobby/transition area, pause until room entry
					tobBetweenRooms = true;
				}
			}
			else
			{
				// Left TOB, resume surge cooldown timer
				tobBetweenRooms = false;
			}
			updateSurgePauseState();
		}
	}

	/**
	 * Handles equipment changes to detect Lightbearer ring.
	 * When Lightbearer is equipped/unequipped, adjusts the timer appropriately.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.EQUIPMENT.getId())
		{
			return;
		}

		ItemContainer equipment = event.getItemContainer();
		final boolean hasLightbearer = equipment.contains(ItemID.LIGHTBEARER);

		if (hasLightbearer == wearingLightbearer)
		{
			return;
		}

		if (hasLightbearer)
		{
			// Equipping Lightbearer: cap timer at 25 ticks (preserve progress if close to regen)
			ticksUntilRegen = Math.min(ticksUntilRegen, LIGHTBEARER_REGEN_TICKS);
		}
		else
		{
			// Unequipping Lightbearer: reset to full 50 tick cycle
			ticksUntilRegen = SPEC_REGEN_TICKS;
		}

		wearingLightbearer = hasLightbearer;
	}

	/**
	 * Detects when boss NPCs spawn to resume timers.
	 * Handles Doom of Mokhaoitl spec regen timer.
	 * Uses getMaxRegenTicks() - 2 for Doom because the game's internal timer starts
	 * 2 ticks before the NPC spawn event fires.
	 * Note: TOB room detection uses region-based detection in onGameTick instead.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		String name = npc.getName();

		// Doom of Mokhaoitl boss spawn - resumes spec regen timer
		if (name != null && name.contains("Doom"))
		{
			betweenWaves = false;
			ticksUntilRegen = getMaxRegenTicks() - 2;
			updateSurgePauseState();
		}

		// Verzik fight start - NPC 8370 spawns when the fight begins
		if (npc.getId() == VERZIK_FIGHT_START_NPC_ID && tobBetweenRooms && !combatAreaEnteredThisRoom)
		{
			tobBetweenRooms = false;
			combatAreaEnteredThisRoom = true;
			updateSurgePauseState();
		}
	}

	/**
	 * Detects when player confirms entering a TOB boss room via dialogue.
	 * - Barrier rooms: After clicking "Pass", player clicks "Yes, let's begin."
	 * - Verzik: After her dialogue "Oh I'm going to enjoy this...", player clicks "Continue"
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!insideTob)
		{
			return;
		}

		String menuOption = event.getMenuOption();

		if (!tobBetweenRooms || menuOption == null)
		{
			return;
		}

		// Detect the dialogue confirmation to enter a TOB boss room (barrier rooms)
		if (menuOption.contains("Yes, let's begin"))
		{
			tobBetweenRooms = false;
			updateSurgePauseState();
			return;
		}

		// Detect Verzik fight start - "Continue" after her dialogue in Verzik region (12611)
		if (menuOption.equals("Continue") && getTemplateRegionId() == 12611)
		{
			tobBetweenRooms = false;
			updateSurgePauseState();
		}
	}

	/**
	 * Handles chat messages to detect wave/delve transitions.
	 * Colosseum:
	 * - "Wave X completed!": Wave finished, timer stops
	 * - "Wave: X" (1-12): Wave starting, timer resets to 30 seconds
	 * - "Search the chest...": Run over (claimed rewards), timer continues
	 * Doom of Mokhaoitl:
	 * - "Delve level: X duration: ...": Delve finished, timer stops
	 * - "Delve level: X": New delve starting, timer resets
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Guard: Only process when logged in
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// Check GAMEMESSAGE for wave/reward announcements and death charge,
		// and SPAM for surge potion messages
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}

		String message = event.getMessage();
		if (message == null)
		{
			return;
		}

		// Strip color tags from the message (e.g., "<col=ff3045>Wave: 1</col>" -> "Wave: 1")
		String strippedMessage = Text.removeTags(message);

		// Colo Checks

		// Check if wave was completed - this is when spec regen stops
		if (WAVE_COMPLETED_PATTERN.matcher(strippedMessage).matches())
		{
			betweenWaves = true;
			updateSurgePauseState();
			return;
		}

		// Check if this is a wave start message - resume timer and reset to full cycle
		if (WAVE_START_PATTERN.matcher(strippedMessage).matches())
		{
			betweenWaves = false;
			ticksUntilRegen = getMaxRegenTicks();
			updateSurgePauseState();
			return;
		}

		// Check if the run is over (player claimed rewards) - timer continues
		if (strippedMessage.contains(CLAIM_REWARDS_MESSAGE))
		{
			betweenWaves = false;
			updateSurgePauseState();
			return;
		}

		// Doom Checks

		// Check if delve was completed - this is when spec regen stops
		// Timer resumes when the boss NPC spawns (handled in onNpcSpawned)
		if (DELVE_COMPLETED_PATTERN.matcher(strippedMessage).matches())
		{
			betweenWaves = true;
			updateSurgePauseState();
			return;
		}

		// TOB Checks
		// Check if TOB room was completed - this is when surge cooldown pauses
		// Timer resumes when entering the next room's combat area
		if (TOB_ROOM_COMPLETED_PATTERN.matcher(strippedMessage).matches())
		{
			insideTob = true; // Ensure we track that we're in TOB
			tobBetweenRooms = true;
			// combatAreaEnteredThisRoom stays true to prevent re-triggering while still in this room
			// It resets when entering a new region
			updateSurgePauseState();
			return;
		}

		// Check if TOB run was completed (after Verzik) - surge cooldown resumes
		if (TOB_COMPLETION_PATTERN.matcher(strippedMessage).matches())
		{
			tobBetweenRooms = false;
			updateSurgePauseState();
			return;
		}

		// Spec restore checks (surge potion, death charge)

		// Detect spec restores to ignore the spec increase and not reset the timer.
		// Use a 2-tick grace period to handle event ordering (onGameTick may fire before onChatMessage).
		if (strippedMessage.equals(SURGE_POTION_MESSAGE))
		{
			ignoreSpecIncreaseUntilTick = client.getTickCount() + 2;
			expectedSpecRestoreAmount = SURGE_POTION_RESTORE;
			// Start surge cooldown timer (5 minutes) using wall-clock time
			startSurgeCooldown();
			return;
		}

		if (strippedMessage.contains(DEATH_CHARGE_MESSAGE))
		{
			ignoreSpecIncreaseUntilTick = client.getTickCount() + 2;
			expectedSpecRestoreAmount = DEATH_CHARGE_RESTORE;
			return;
		}

		// Detect surge cooldown expiry to clear the timer
		if (strippedMessage.equals(SURGE_COOLDOWN_EXPIRED_MESSAGE))
		{
			clearSurgeCooldown();
			return;
		}
	}

	/**
	 * Main timer logic executed every game tick.
	 * Handles spec regen countdown and detects actual spec changes.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Guard: Only process when logged in
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int currentSpec = getSpecEnergy();

		// Detect if spec has regenerated - reset timer to prevent drift
		// But ignore if spec was restored by an effect (surge potion, death charge, etc.)
		if (lastSpecEnergy != -1 && currentSpec > lastSpecEnergy)
		{
			int currentTick = client.getTickCount();
			int actualIncrease = currentSpec - lastSpecEnergy;
			int maxPossibleIncrease = MAX_SPEC_ENERGY - lastSpecEnergy;
			boolean inGracePeriod = currentTick <= ignoreSpecIncreaseUntilTick;

			// Check if natural regen also occurred alongside the effect
			// Natural regen would cause the increase to exceed the expected effect amount
			// (unless capped at max spec)
			boolean naturalRegenAlsoOccurred = inGracePeriod
				&& actualIncrease > expectedSpecRestoreAmount
				&& actualIncrease <= maxPossibleIncrease;

			if (inGracePeriod && !naturalRegenAlsoOccurred)
			{
				// Spec increased only from an effect, not natural regen - don't reset timer
			}
			else
			{
				// Natural spec regen occurred - reset timer
				ticksUntilRegen = getMaxRegenTicks();
			}
		}

		lastSpecEnergy = currentSpec;

		// Spec regen timer countdown
		// Don't count down if spec is full or if between waves (stopped)
		if (currentSpec >= MAX_SPEC_ENERGY)
		{
			ticksUntilRegen = getMaxRegenTicks();
		}
		else if (betweenWaves)
		{
			// Spec timer is stopped between waves - do not decrement
		}
		else
		{
			// Count down the spec regen timer
			ticksUntilRegen--;
			if (ticksUntilRegen <= 0)
			{
				// Timer expired, expect regen next tick - reset for next cycle
				ticksUntilRegen = getMaxRegenTicks();
			}
		}

		// TOB room entry detection
		// Resume surge cooldown when entering a boss room region.
		// Bloat and Nylocas use coordinate-based detection since their regions include hallways.
		int currentRegion = getTemplateRegionId();
		if (currentRegion != -1 && currentRegion != lastRegionId)
		{
			// Reset combat area flag when entering a new region
			combatAreaEnteredThisRoom = false;

			// For rooms without hallway issues or special detection needs, use region-based detection
			// Maiden (12613) is the only room that uses pure region-based detection
			if (tobBetweenRooms && TOB_BOSS_ROOM_REGIONS.contains(currentRegion)
				&& currentRegion != TOB_BLOAT_REGION
				&& currentRegion != TOB_NYLOCAS_REGION
				&& currentRegion != TOB_SOTETSEG_REGION
				&& currentRegion != TOB_XARPUS_REGION
				&& currentRegion != TOB_VERZIK_REGION)
			{
				tobBetweenRooms = false;
				combatAreaEnteredThisRoom = true;
				updateSurgePauseState();
			}
			lastRegionId = currentRegion;
		}

		// Bloat and Nylocas use coordinate-based detection
		if (tobBetweenRooms && !combatAreaEnteredThisRoom)
		{
			WorldPoint templatePoint = getTemplateWorldPoint();
			if (templatePoint != null)
			{
				int x = templatePoint.getX();
				int y = templatePoint.getY();
				boolean enteredCombatArea = false;

				// Bloat: barrier at X=3303, Y=4446-4449
				if (currentRegion == TOB_BLOAT_REGION)
				{
					enteredCombatArea = x <= BLOAT_BARRIER_X
						&& y >= BLOAT_BARRIER_MIN_Y
						&& y <= BLOAT_BARRIER_MAX_Y;
				}
				// Nylocas: barrier at X=3295-3296, Y=4254
				else if (currentRegion == TOB_NYLOCAS_REGION)
				{
					enteredCombatArea = x >= NYLOCAS_BARRIER_MIN_X
						&& x <= NYLOCAS_BARRIER_MAX_X
						&& y == NYLOCAS_BARRIER_Y;
				}
				// Sotetseg: barrier at X=3278-3281, Y=4308
				else if (currentRegion == TOB_SOTETSEG_REGION)
				{
					enteredCombatArea = x >= SOTETSEG_BARRIER_MIN_X
						&& x <= SOTETSEG_BARRIER_MAX_X
						&& y == SOTETSEG_BARRIER_Y;
				}
				// Xarpus: barrier at X=3169-3171, Y=4380
				else if (currentRegion == TOB_XARPUS_REGION)
				{
					enteredCombatArea = x >= XARPUS_BARRIER_MIN_X
						&& x <= XARPUS_BARRIER_MAX_X
						&& y == XARPUS_BARRIER_Y;
				}

				if (enteredCombatArea)
				{
					tobBetweenRooms = false;
					combatAreaEnteredThisRoom = true;
					updateSurgePauseState();
				}
			}
		}
	}

	/**
	 * Gets the current special attack energy from the client.
	 *
	 * @return Special attack energy (0-1000, where 1000 = 100%)
	 */
	public int getSpecEnergy()
	{
		return client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
	}

	/**
	 * Gets the current special attack energy as a percentage.
	 *
	 * @return Special attack percentage (0-100)
	 */
	public int getSpecPercent()
	{
		return getSpecEnergy() / 10;
	}

	/**
	 * Checks if special attack is at maximum.
	 *
	 * @return true if spec is 100%
	 */
	public boolean isSpecFull()
	{
		return getSpecEnergy() >= MAX_SPEC_ENERGY;
	}

	/**
	 * Gets the time until next spec regen in seconds.
	 *
	 * @return Seconds until regen (each tick is 0.6 seconds)
	 */
	public double getSecondsUntilRegen()
	{
		return ticksUntilRegen * 0.6;
	}

	/**
	 * Gets the current max ticks for spec regen based on Lightbearer status.
	 *
	 * @return 25 if wearing Lightbearer, 50 otherwise
	 */
	public int getMaxRegenTicks()
	{
		return wearingLightbearer ? LIGHTBEARER_REGEN_TICKS : SPEC_REGEN_TICKS;
	}

	/**
	 * Gets the template region ID for the player's current location.
	 * For instanced content like TOB, this converts the instanced coordinates
	 * to the template region ID using WorldPoint.fromLocalInstance().
	 *
	 * @return Template region ID, or -1 if player is null
	 */
	private int getTemplateRegionId()
	{
		WorldPoint templatePoint = getTemplateWorldPoint();
		return templatePoint != null ? templatePoint.getRegionID() : -1;
	}

	/**
	 * Gets the template WorldPoint for the player's current location.
	 * For instanced content like TOB, this converts the instanced coordinates
	 * to template coordinates using WorldPoint.fromLocalInstance().
	 *
	 * @return Template WorldPoint, or null if player is null
	 */
	private WorldPoint getTemplateWorldPoint()
	{
		if (client.getLocalPlayer() == null)
		{
			return null;
		}

		WorldPoint worldPoint = client.getLocalPlayer().getWorldLocation();
		if (client.isInInstancedRegion())
		{
			// For instanced content, convert to template coordinates
			LocalPoint localPoint = LocalPoint.fromWorld(client, worldPoint);
			if (localPoint != null)
			{
				WorldPoint templatePoint = WorldPoint.fromLocalInstance(client, localPoint);
				if (templatePoint != null)
				{
					return templatePoint;
				}
			}
		}

		// Non-instanced or fallback to direct world point
		return worldPoint;
	}

	/**
	 * Gets the remaining surge potion cooldown duration.
	 * Uses wall-clock time for accurate display.
	 *
	 * @return Remaining duration, or Duration.ZERO if no cooldown is active
	 */
	public Duration getSurgeCooldownRemaining()
	{
		if (surgePausedRemaining != null)
		{
			return surgePausedRemaining;
		}
		if (surgeEndTime != null)
		{
			Duration remaining = Duration.between(Instant.now(), surgeEndTime);
			if (remaining.isNegative())
			{
				return Duration.ZERO;
			}
			return remaining;
		}
		return Duration.ZERO;
	}

	/**
	 * Gets the remaining surge cooldown in ticks (for backwards compatibility).
	 *
	 * @return Ticks remaining, or 0 if no cooldown is active
	 */
	public int getSurgeCooldownTicks()
	{
		Duration remaining = getSurgeCooldownRemaining();
		if (remaining.isZero() || remaining.isNegative())
		{
			return 0;
		}
		return (int) (remaining.toMillis() / 600);
	}

	/**
	 * Checks if the surge cooldown timer is currently paused.
	 *
	 * @return true if paused (between waves/rooms), false otherwise
	 */
	public boolean isSurgeCooldownPaused()
	{
		return surgePausedRemaining != null;
	}

	/**
	 * Updates the surge cooldown pause state based on current wave/room status.
	 * Should be called whenever betweenWaves or tobBetweenRooms changes.
	 */
	private void updateSurgePauseState()
	{
		boolean shouldBePaused = betweenWaves || tobBetweenRooms;
		boolean isCurrentlyPaused = surgePausedRemaining != null;

		if (shouldBePaused && !isCurrentlyPaused && surgeEndTime != null)
		{
			// Transition to paused: save remaining duration
			surgePausedRemaining = Duration.between(Instant.now(), surgeEndTime);
			if (surgePausedRemaining.isNegative())
			{
				surgePausedRemaining = Duration.ZERO;
			}
			surgeEndTime = null;
		}
		else if (!shouldBePaused && isCurrentlyPaused)
		{
			// Transition to resumed: restore end time from remaining duration
			surgeEndTime = Instant.now().plus(surgePausedRemaining);
			surgePausedRemaining = null;
		}
	}

	/**
	 * Starts the surge potion cooldown timer.
	 * Handles starting in either paused or active state.
	 */
	private void startSurgeCooldown()
	{
		if (betweenWaves || tobBetweenRooms)
		{
			// Start in paused state
			surgePausedRemaining = SURGE_COOLDOWN_DURATION;
			surgeEndTime = null;
		}
		else
		{
			// Start in active state
			surgeEndTime = Instant.now().plus(SURGE_COOLDOWN_DURATION);
			surgePausedRemaining = null;
		}
	}

	/**
	 * Clears the surge potion cooldown timer.
	 * Called when the cooldown expires (game message) or when resetting state.
	 */
	private void clearSurgeCooldown()
	{
		surgeEndTime = null;
		surgePausedRemaining = null;
	}
}
