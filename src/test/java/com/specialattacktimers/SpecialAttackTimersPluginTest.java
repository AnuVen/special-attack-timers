package com.specialattacktimers;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SpecialAttackTimersPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SpecialAttackTimersPlugin.class);
		RuneLite.main(args);
	}
}
