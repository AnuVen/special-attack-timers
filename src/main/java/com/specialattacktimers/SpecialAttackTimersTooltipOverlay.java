package com.specialattacktimers;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Adds a "Time to full" tooltip when the mouse hovers over the special attack orb.
 * Shows the time until special attack energy reaches 100%, formatted as ticks,
 * whole seconds, or decimal seconds based on config.
 *
 * Disabled by default; enable via the "Time to Full Tooltip" config option.
 */
public class SpecialAttackTimersTooltipOverlay extends Overlay
{
	private final Client client;
	private final SpecialAttackTimersPlugin plugin;
	private final SpecialAttackTimersConfig config;
	private final TooltipManager tooltipManager;

	@Inject
	public SpecialAttackTimersTooltipOverlay(Client client, SpecialAttackTimersPlugin plugin,
		SpecialAttackTimersConfig config, TooltipManager tooltipManager)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.tooltipManager = tooltipManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		if (!config.showTimeToFullTooltip())
		{
			return null;
		}

		// Nothing to show once spec is full
		if (plugin.isSpecFull())
		{
			return null;
		}

		// Find the spec orb widget (try standard orbs first, then no-map variant)
		Widget specOrbWidget = client.getWidget(InterfaceID.Orbs.ORB_SPECENERGY);
		if (specOrbWidget == null || specOrbWidget.isHidden())
		{
			specOrbWidget = client.getWidget(InterfaceID.OrbsNomap.ORB_SPECENERGY);
		}
		if (specOrbWidget == null || specOrbWidget.isHidden())
		{
			return null;
		}

		Rectangle bounds = specOrbWidget.getBounds();
		if (bounds.getX() <= 0)
		{
			return null;
		}

		// Only show the tooltip while hovering over the orb
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || !bounds.contains(mouse.getX(), mouse.getY()))
		{
			return null;
		}

		tooltipManager.add(new Tooltip("Time to full: " + formatTimeToFull()));
		return null;
	}

	/**
	 * Formats the ticks-until-full value according to the configured display format.
	 */
	private String formatTimeToFull()
	{
		int ticks = plugin.getTicksUntilFull();

		switch (config.timeToFullFormat())
		{
			case SECONDS:
				// Whole seconds, rounded up so you know when full will be reached
				return (int) Math.ceil(ticks * 0.6) + "s";
			case DECIMALS:
				return String.format("%.1fs", ticks * 0.6);
			case TICKS:
			default:
				return ticks + " ticks";
		}
	}
}
