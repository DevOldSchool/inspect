package com.inspect.item;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class AlchemyEquipmentTest
{
	@Test
	public void recognisesFireAndCombinationStavesIncludingOrnaments()
	{
		int[] weapons = {ItemID.STAFF_OF_FIRE, ItemID.FIRE_BATTLESTAFF, ItemID.MYSTIC_FIRE_STAFF,
			ItemID.LAVA_BATTLESTAFF, ItemID.MYSTIC_LAVA_STAFF, ItemID.LAVA_BATTLESTAFF_PRETTY,
			ItemID.MYSTIC_LAVA_STAFF_PRETTY, ItemID.STEAM_BATTLESTAFF, ItemID.MYSTIC_STEAM_BATTLESTAFF,
			ItemID.STEAM_BATTLESTAFF_PRETTY, ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY,
			ItemID.SMOKE_BATTLESTAFF, ItemID.MYSTIC_SMOKE_BATTLESTAFF, ItemID.TWINFLAME_STAFF};
		for (int weapon : weapons)
		{
			assertTrue(AlchemyEquipment.suppliesFireRunes(weapon, -1));
		}
	}

	@Test
	public void requiresChargedTomeInShieldSlotOrQualifyingWeapon()
	{
		assertTrue(AlchemyEquipment.suppliesFireRunes(-1, ItemID.TOME_OF_FIRE));
		assertFalse(AlchemyEquipment.suppliesFireRunes(-1, ItemID.TOME_OF_FIRE_UNCHARGED));
		assertFalse(AlchemyEquipment.suppliesFireRunes(ItemID.STAFF_OF_WATER, ItemID.TOME_OF_WATER));
		assertFalse(AlchemyEquipment.suppliesFireRunes(-1, -1));
		assertFalse(AlchemyEquipment.suppliesFireRunes(ItemID.TOME_OF_FIRE, ItemID.STAFF_OF_FIRE));
	}
}
