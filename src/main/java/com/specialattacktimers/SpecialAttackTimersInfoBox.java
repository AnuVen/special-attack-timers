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
		return plugin.getRegenTimerText();
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
		return "Special Attack Timers";
	}
}
