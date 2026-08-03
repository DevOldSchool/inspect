package com.inspect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.MenuAction;
import org.junit.Test;

public class InspectPluginPlayerMenuTest
{
	@Test
	public void acceptsManagedPlayerInspectAction()
	{
		assertTrue(InspectPlugin.isPlayerInspectMenuClick(MenuAction.RUNELITE_PLAYER, "Inspect"));
	}

	@Test
	public void rejectsGenericRuneLiteAction()
	{
		assertFalse(InspectPlugin.isPlayerInspectMenuClick(MenuAction.RUNELITE, "Inspect"));
	}

	@Test
	public void rejectsOtherPlayerOptions()
	{
		assertFalse(InspectPlugin.isPlayerInspectMenuClick(MenuAction.RUNELITE_PLAYER, "Equipment"));
		assertFalse(InspectPlugin.isPlayerInspectMenuClick(MenuAction.RUNELITE_PLAYER, null));
	}
}
