package com.specialattacktimers;

import java.awt.Color;
import java.time.Duration;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * InfoBox that displays the surge potion cooldown timer.
 * Shows "Surge Cooldown" on hover.
 */
public class SurgePotionInfoBox extends InfoBox
{
	private final SpecialAttackTimersPlugin plugin;
	private final SpecialAttackTimersConfig config;

	public SurgePotionInfoBox(SpecialAttackTimersPlugin plugin, SpecialAttackTimersConfig config)
	{
		super(null, plugin);
		this.plugin = plugin;
		this.config = config;
	}

	@Override
	public String getText()
	{
		Duration remaining = plugin.getSurgeCooldownRemaining();
		long totalMillis = remaining.toMillis();

		switch (config.surgeDisplayFormat())
		{
			case SECONDS:
				// Minutes:seconds format (e.g. 4:30) using integer division like RuneLite
				int totalSeconds = (int) (totalMillis / 1000);
				int minutes = totalSeconds / 60;
				int seconds = totalSeconds % 60;
				return String.format("%d:%02d", minutes, seconds);
			case DECIMALS:
				// Minutes:seconds with decimal (e.g. 4:29.4)
				double totalSecondsDecimal = totalMillis / 1000.0;
				int mins = (int) (totalSecondsDecimal / 60);
				double secs = totalSecondsDecimal % 60;
				return String.format("%d:%04.1f", mins, secs);
			case TICKS:
			default:
				// Convert milliseconds back to ticks for display
				return String.valueOf((int) (totalMillis / 600));
		}
	}

	@Override
	public Color getTextColor()
	{
		if (plugin.isSurgeCooldownPaused())
		{
			return config.surgePausedColor();
		}
		return config.surgeColor();
	}

	@Override
	public boolean render()
	{
		// Only render if surge cooldown is active
		Duration remaining = plugin.getSurgeCooldownRemaining();
		return !remaining.isZero() && !remaining.isNegative();
	}

	@Override
	public String getTooltip()
	{
		return "Surge Cooldown";
	}
}
