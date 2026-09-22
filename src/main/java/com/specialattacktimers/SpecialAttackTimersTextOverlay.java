package com.specialattacktimers;

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
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * Standalone overlay that shows only the spec regen timer number, with no icon or background.
 * Used in place of the infobox when the "Transparent Infobox" option is enabled, since
 * RuneLite applies the global overlay background color to every infobox.
 *
 * Uses the same font and text outline settings as RuneLite infoboxes, at the configured
 * font size. Movable with Alt-drag.
 */
public class SpecialAttackTimersTextOverlay extends Overlay
{
	private final Client client;
	private final SpecialAttackTimersPlugin plugin;
	private final SpecialAttackTimersConfig config;
	private final RuneLiteConfig runeLiteConfig;
	private final TextComponent textComponent = new TextComponent();

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
		Font font = runeLiteConfig.infoboxFont().getFont().deriveFont((float) config.transparentFontSize());
		graphics.setFont(font);
		FontMetrics metrics = graphics.getFontMetrics();

		// Use a fixed width (the widest text the current format can show) and center the text in it,
		// like the regular infobox, so the number doesn't shift as it counts down
		int boxWidth = getMaxTextWidth(metrics);
		int x = (boxWidth - metrics.stringWidth(text)) / 2;

		textComponent.setText(text);
		textComponent.setFont(font);
		textComponent.setColor(config.activeColor());
		textComponent.setOutline(runeLiteConfig.infoBoxTextOutline());
		textComponent.setPosition(x, metrics.getAscent());
		textComponent.render(graphics);

		return new Dimension(boxWidth, metrics.getHeight());
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
