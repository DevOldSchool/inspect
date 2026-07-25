package com.inspect.item;

public enum ItemSourceAccountMode
{
	STANDARD("Standard", false),
	IRONMAN("Ironman", true),
	ULTIMATE_IRONMAN("Ultimate Ironman", true),
	HARDCORE_IRONMAN("Hardcore Ironman", true),
	GROUP_IRONMAN("Group Ironman", true),
	HARDCORE_GROUP_IRONMAN("Hardcore Group Ironman", true),
	UNRANKED_GROUP_IRONMAN("Unranked Group Ironman", true);

	private final String displayName;
	private final boolean ironman;

	ItemSourceAccountMode(String displayName, boolean ironman)
	{
		this.displayName = displayName;
		this.ironman = ironman;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public boolean isIronman()
	{
		return ironman;
	}

	public static ItemSourceAccountMode fromVarbitValue(int value)
	{
		switch (value)
		{
			case 1:
				return IRONMAN;
			case 2:
				return ULTIMATE_IRONMAN;
			case 3:
				return HARDCORE_IRONMAN;
			case 4:
				return GROUP_IRONMAN;
			case 5:
				return HARDCORE_GROUP_IRONMAN;
			case 6:
				return UNRANKED_GROUP_IRONMAN;
			default:
				return value > 0 ? IRONMAN : STANDARD;
		}
	}
}
