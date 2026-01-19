package com.specialattacktimers;

import java.awt.Color;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * InfoBox that displays the special attack regeneration timer as a simple tick count.
 * Shows "Spec Regen Timer" on hover.
 */
public class SpecialAttackTimersInfoBox extends InfoBox
{
	private final SpecialAttackTimersPlugin plugin;
	private final SpecialAttackTimersConfig config;

	public SpecialAttackTimersInfoBox(SpecialAttackTimersPlugin plugin, SpecialAttackTimersConfig config)
	{
		super(null, plugin);
		this.plugin = plugin;
		this.config = config;
	}

	@Override
	public String getText()
	{
		// Display tick 0 instead of max ticks (25 with LB, 50 without)
		int ticksUntilRegen = plugin.getTicksUntilRegen();
		int displayTicks = ticksUntilRegen == plugin.getMaxRegenTicks() ? 0 : ticksUntilRegen;

		switch (config.displayFormat())
		{
			case SECONDS:
				// Whole seconds (rounded up so you know when regen will happen)
				int wholeSeconds = (int) Math.ceil(displayTicks * 0.6);
				return String.valueOf(wholeSeconds);
			case DECIMALS:
				// Decimal seconds (e.g. 29.4s)
				double decimalSeconds = displayTicks * 0.6;
				return String.format("%.1fs", decimalSeconds);
			case TICKS:
			default:
				return String.valueOf(displayTicks);
		}
	}

	@Override
	public Color getTextColor()
	{
		return config.activeColor();
	}

	@Override
	public boolean render()
	{
		// Don't render if spec is full or between waves/delves (timer paused)
		return !plugin.isSpecFull() && !plugin.isBetweenWaves();
	}

	@Override
	public String getTooltip()
	{
		return "Spec Regen Timer";
	}
}
