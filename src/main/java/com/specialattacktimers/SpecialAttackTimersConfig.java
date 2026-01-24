package com.specialattacktimers;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("specialattacktimers")
public interface SpecialAttackTimersConfig extends Config
{
	@Getter
	@RequiredArgsConstructor
	enum DisplayFormat
	{
		TICKS("Ticks"),
		SECONDS("Seconds"),
		DECIMALS("Decimals");

		private final String name;

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigSection(
		name = "Display",
		description = "Overlay display settings",
		position = 0
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "showInfoBox",
		name = "Show InfoBox",
		description = "Show the special attack timer infobox",
		position = 1,
		section = displaySection
	)
	default boolean showInfoBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "displayFormat",
		name = "Display Format",
		description = "Ticks (game ticks), Seconds (whole), or Decimals (e.g. 29.4s)",
		position = 2,
		section = displaySection
	)
	default DisplayFormat displayFormat()
	{
		return DisplayFormat.TICKS;
	}

	@ConfigItem(
		keyName = "showCircularOverlay",
		name = "Show Circular Overlay",
		description = "Show circular progress indicator on the spec orb (like Regeneration Meter)",
		position = 3,
		section = displaySection
	)
	default boolean showCircularOverlay()
	{
		return true;
	}

	@ConfigSection(
		name = "Surge Potion Timer",
		description = "Surge potion cooldown timer settings",
		position = 5
	)
	String surgeSection = "surge";

	@ConfigItem(
		keyName = "showSurgeInfoBox",
		name = "Show Surge Timer",
		description = "Show the surge potion cooldown timer infobox",
		position = 6,
		section = surgeSection
	)
	default boolean showSurgeInfoBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "surgeDisplayFormat",
		name = "Surge Display Format",
		description = "Ticks (game ticks), Seconds (whole), or Decimals (e.g. 29.4s)",
		position = 7,
		section = surgeSection
	)
	default DisplayFormat surgeDisplayFormat()
	{
		return DisplayFormat.SECONDS;
	}

	@ConfigSection(
		name = "Colors",
		description = "Color settings for the overlay",
		position = 10
	)
	String colorSection = "colors";

	@Alpha
	@ConfigItem(
		keyName = "activeColor",
		name = "Infobox Color",
		description = "Color for the spec regen timer infobox text",
		position = 11,
		section = colorSection
	)
	default Color activeColor()
	{
		return new Color(255, 255, 255, 255);
	}

	@Alpha
	@ConfigItem(
		keyName = "circleColor",
		name = "Circle Overlay Color",
		description = "Color for the circular progress indicator on the spec orb",
		position = 12,
		section = colorSection
	)
	default Color circleColor()
	{
		return new Color(0, 255, 255, 255);
	}

	@Alpha
	@ConfigItem(
		keyName = "surgeColor",
		name = "Surge Active Color",
		description = "Color for the surge potion cooldown timer when active (unpaused)",
		position = 13,
		section = colorSection
	)
	default Color surgeColor()
	{
		return new Color(255, 255, 255, 255);
	}

	@Alpha
	@ConfigItem(
		keyName = "surgePausedColor",
		name = "Surge Paused Color",
		description = "Color for the surge potion cooldown timer when paused (between waves/rooms)",
		position = 14,
		section = colorSection
	)
	default Color surgePausedColor()
	{
		return new Color(255, 165, 0, 255);
	}
}
