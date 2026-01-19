package com.specialattacktimers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Circular overlay that draws a progress arc around the special attack orb,
 * similar to the Regeneration Meter plugin.
 *
 * The arc fills up as you get closer to the next spec regen tick:
 * - Empty arc = just regenerated, 30 seconds until next
 * - Full arc = about to regenerate
 * - No arc when spec is at 100% or between waves (timer resets on wave start anyway)
 */
public class SpecialAttackTimersCircleOverlay extends Overlay
{
	/**
	 * Offset from the widget bounds to position the arc.
	 */
	private static final int OFFSET = 27;

	/**
	 * Diameter of the circular arc.
	 */
	private static final int DIAMETER = 26;

	/**
	 * Stroke width for drawing the arc.
	 */
	private static final int STROKE_WIDTH = 2;

	private final Client client;
	private final SpecialAttackTimersPlugin plugin;
	private final SpecialAttackTimersConfig config;

	@Inject
	public SpecialAttackTimersCircleOverlay(Client client, SpecialAttackTimersPlugin plugin, SpecialAttackTimersConfig config)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Don't render if not logged in
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		// Don't render if circular overlay is disabled
		if (!config.showCircularOverlay())
		{
			return null;
		}

		// Don't render if spec is full (nothing to show)
		if (plugin.isSpecFull())
		{
			return null;
		}

		// Don't render between waves - timer resets on wave start anyway
		if (plugin.isBetweenWaves())
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

		// Calculate progress percentage (0.0 = just reset, 1.0 = about to regen)
		// This makes the arc fill UP as you get closer to regen, matching RegenMeter behavior
		// Uses getMaxRegenTicks() to account for Lightbearer (25 ticks instead of 50)
		double progress = 1.0 - ((double) plugin.getTicksUntilRegen() / plugin.getMaxRegenTicks());

		// Draw the arc with the configured circle color
		drawArc(graphics, bounds, progress, config.circleColor());

		return null;
	}

	/**
	 * Draws a circular progress arc around the spec orb.
	 *
	 * @param graphics The graphics context
	 * @param bounds   The widget bounds
	 * @param progress Progress from 0.0 (empty) to 1.0 (full)
	 * @param color    The color to draw the arc
	 */
	private void drawArc(Graphics2D graphics, Rectangle bounds, double progress, Color color)
	{
		// Enable anti-aliasing for smooth arc
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		// Calculate arc position (centered on the orb)
		double x = bounds.getX() + OFFSET;
		double y = bounds.getY() + (bounds.getHeight() / 2.0 - DIAMETER / 2.0);

		// Create arc starting at top (90 degrees) and sweeping clockwise
		// Progress of 1.0 = full circle, 0.0 = no arc
		double sweepAngle = -360.0 * progress;
		Arc2D.Double arc = new Arc2D.Double(x, y, DIAMETER, DIAMETER, 90.0, sweepAngle, Arc2D.OPEN);

		// Draw the arc with stroke
		Stroke originalStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
		graphics.setColor(color);
		graphics.draw(arc);
		graphics.setStroke(originalStroke);
	}
}
