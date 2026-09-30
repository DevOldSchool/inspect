package com.inspect.item;

import net.runelite.api.gameval.ItemID;

/** Equipment which supplies fire runes for a standard High Level Alchemy cast. */
public final class AlchemyEquipment
{
	private AlchemyEquipment()
	{
	}

	public static boolean suppliesFireRunes(int weaponId, int shieldId)
	{
		// The uncharged tome has a different ID and does not supply runes.
		if (shieldId == ItemID.TOME_OF_FIRE)
		{
			return true;
		}
		switch (weaponId)
		{
			case ItemID.STAFF_OF_FIRE:
			case ItemID.FIRE_BATTLESTAFF:
			case ItemID.MYSTIC_FIRE_STAFF:
			case ItemID.LAVA_BATTLESTAFF:
			case ItemID.MYSTIC_LAVA_STAFF:
			case ItemID.LAVA_BATTLESTAFF_PRETTY:
			case ItemID.MYSTIC_LAVA_STAFF_PRETTY:
			case ItemID.STEAM_BATTLESTAFF:
			case ItemID.MYSTIC_STEAM_BATTLESTAFF:
			case ItemID.STEAM_BATTLESTAFF_PRETTY:
			case ItemID.MYSTIC_STEAM_BATTLESTAFF_PRETTY:
			case ItemID.SMOKE_BATTLESTAFF:
			case ItemID.MYSTIC_SMOKE_BATTLESTAFF:
			case ItemID.TWINFLAME_STAFF:
				return true;
			default:
				return false;
		}
	}
}
