package com.specialattacktimers;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Standalone overlay that shows only the spec regen timer number, with no icon or background.
 * Used in place of the infobox when the "Transparent Infobox" option is enabled, since
 * RuneLite applies the global overlay background color to every infobox.
 *
 * Uses the same font and text outline settings as RuneLite infoboxes, at the configured
 * font size, unless a custom font and/or text outline is chosen in the config. Movable with Alt-drag.
 */
public class SpecialAttackTimersTextOverlay extends Overlay
{
	private final Client client;
	private final SpecialAttackTimersPlugin plugin;
	private final SpecialAttackTimersConfig config;
	private final RuneLiteConfig runeLiteConfig;

	@Inject
	public SpecialAttackTimersTextOverlay(Client client, SpecialAttackTimersPlugin plugin,
		SpecialAttackTimersConfig config, RuneLiteConfig runeLiteConfig)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.runeLiteConfig = runeLiteConfig;

		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);

		// Don't allow snapping into overlay corners. The minimap sits in the top-right snap corner,
		// whose drop area grows to cover the whole minimap, so a snappable overlay can't be placed
		// on the spec orb. Non-snappable overlays always stay exactly where they are dropped.
		setSnappable(false);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		// Only shown in place of the infobox when transparent mode is enabled
		if (!config.showInfoBox() || !config.transparentInfoBox())
		{
			return null;
		}

		// Don't render if spec is full or between waves/delves (timer paused)
		if (plugin.isSpecFull() || plugin.isBetweenWaves())
		{
			return null;
		}

		String text = plugin.getRegenTimerText();
		// A custom font carries its own size (set in RuneLite's font picker); otherwise use
		// RuneLite's infobox font at the configured size
		Font font = config.useCustomFont()
			? config.timerFont().getFont()
			: runeLiteConfig.infoboxFont().getFont().deriveFont((float) config.transparentFontSize());
		graphics.setFont(font);
		FontMetrics metrics = graphics.getFontMetrics();

		// Use a fixed width (the widest text the current format can show) and center the text in it,
		// like the regular infobox, so the number doesn't shift as it counts down
		int boxWidth = getMaxTextWidth(metrics);
		int x = (boxWidth - metrics.stringWidth(text)) / 2;

		drawText(graphics, text, x, metrics.getAscent(), config.activeColor());

		return new Dimension(boxWidth + 1, metrics.getHeight() + 1);
	}

	/**
	 * Draws the timer text with the configured text style (outline, shadow or none).
	 * "Same as RuneLite" follows the "Outline infobox text" RuneLite setting.
	 */
	private void drawText(Graphics2D graphics, String text, int x, int y, Color color)
	{
		SpecialAttackTimersConfig.TextStyle style = config.textStyle();
		if (style == SpecialAttackTimersConfig.TextStyle.RUNELITE)
		{
			style = runeLiteConfig.infoBoxTextOutline()
				? SpecialAttackTimersConfig.TextStyle.OUTLINE
				: SpecialAttackTimersConfig.TextStyle.SHADOW;
		}

		graphics.setColor(Color.BLACK);
		switch (style)
		{
			case OUTLINE:
				graphics.drawString(text, x - 1, y);
				graphics.drawString(text, x + 1, y);
				graphics.drawString(text, x, y - 1);
				graphics.drawString(text, x, y + 1);
				break;
			case SHADOW:
				graphics.drawString(text, x + 1, y + 1);
				break;
			case NONE:
			default:
				break;
		}

		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}

	/**
	 * Gets the width of the widest text the timer can show in the current display format:
	 * two digits for ticks/seconds (max 50 ticks or 30 seconds), or "00.0s" for decimals.
	 * Uses the widest digit so the width never changes while counting down.
	 */
	private int getMaxTextWidth(FontMetrics metrics)
	{
		int maxDigitWidth = 0;
		for (char digit = '0'; digit <= '9'; digit++)
		{
			maxDigitWidth = Math.max(maxDigitWidth, metrics.charWidth(digit));
		}

		if (config.displayFormat() == SpecialAttackTimersConfig.DisplayFormat.DECIMALS)
		{
			return 3 * maxDigitWidth + metrics.charWidth('.') + metrics.charWidth('s');
		}
		return 2 * maxDigitWidth;
	}
}
