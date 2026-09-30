package com.inspect.npc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;
import org.junit.Test;

public class NpcInspectParserTest
{
	private final NpcInspectParser parser = new NpcInspectParser();

	@Test
	public void choicesRetainVariantImagesAndAllowMissingImages()
	{
		java.util.List<NpcCombatInfo> choices = parser.parseChoices(
			new NpcWikiLookup("Guard", null, "https://oldschool.runescape.wiki/w/Guard"),
			"{{Infobox Monster|name=Guard|version1=Edgeville|id1=10|image1=[[File:Guard Edgeville.png|120px]]"
				+ "|version2=Falador|id2=11|image2=Guard Falador.png|version3=Varrock|id3=12}}");
		assertEquals(3, choices.size());
		assertEquals("Guard Edgeville.png", choices.get(0).getImageFile());
		assertEquals("Guard Falador.png", choices.get(1).getImageFile());
		assertNull(choices.get(2).getImageFile());
	}

	@Test
	public void parsesVersionByNpcId()
	{
		NpcCombatInfo info = parser.parse(3046, "Goblin", new NpcWikiLookup("Goblin", "Level_13", "https://oldschool.runescape.wiki/w/Goblin#Level_13"), goblinWikitext());

		assertEquals("Goblin", info.getDisplayName());
		assertEquals("13", info.getCombatLevel());
		assertEquals("2", info.getMaxHit());
		assertEquals("Crush", info.getAttackStyle());
		assertEquals("1", info.getMagic());
		assertEquals("4", info.getMagicDefence());
		assertEquals("No elemental weakness", info.getElementalWeakness());
		assertEquals("0% resistance", info.getPoisonResistance());
		assertEquals("Not immune", info.getCannonImmunity());
	}

	@Test
	public void parsesNpcIdFromCommaSeparatedVersionIds()
	{
		NpcCombatInfo info = parser.parse(3029, "Goblin", new NpcWikiLookup("Goblin", "Level_2", "https://oldschool.runescape.wiki/w/Goblin#Level_2"), goblinWikitext());

		assertEquals(3029, info.getNpcId());
		assertEquals("2", info.getCombatLevel());
		assertEquals("Stab", info.getAttackStyle());
	}

	@Test
	public void selectsLaterInfoboxByNpcIdOnMultiInfoboxPage()
	{
		String wikitext = "{{Multi Infobox\n"
			+ "|text1 = Edgeville\n"
			+ "|item1 =\n"
			+ "{{Infobox Monster\n"
			+ "|version1 = Edgeville 1\n"
			+ "|name = Guard\n"
			+ "|combat = 21\n"
			+ "|attack style = [[Stab]]\n"
			+ "|id1 = 3254\n"
			+ "}}\n"
			+ "|text2 = Varrock\n"
			+ "|item2 =\n"
			+ "{{Infobox Monster\n"
			+ "|version1 = Varrock 1\n"
			+ "|version2 = Varrock 2\n"
			+ "|name = Guard\n"
			+ "|combat = 21\n"
			+ "|attack style = [[Crush]]\n"
			+ "|dcrush = 25\n"
			+ "|id1 = 11911,hist3010\n"
			+ "|id2 = 11912\n"
			+ "}}\n"
			+ "}}";

		NpcCombatInfo info = parser.parse(11911, "Guard", new NpcWikiLookup("Guard", "Varrock_1", "https://oldschool.runescape.wiki/w/Guard#Varrock_1"), wikitext);

		assertEquals("Guard", info.getDisplayName());
		assertEquals("21", info.getCombatLevel());
		assertEquals("Crush", info.getAttackStyle());
		assertEquals("25", info.getCrushDefence());
	}

	@Test
	public void parsesUnsuffixedSingleVersionFields()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Rat\n"
			+ "|combat = 1\n"
			+ "|attack style = [[Crush]]\n"
			+ "|id = 2854\n"
			+ "|mage = 1\n"
			+ "}}";

		NpcCombatInfo info = parser.parse(2854, "Rat", new NpcWikiLookup("Rat", null, "https://oldschool.runescape.wiki/w/Rat"), wikitext);

		assertEquals("Rat", info.getDisplayName());
		assertEquals("1", info.getCombatLevel());
		assertEquals("Crush", info.getAttackStyle());
		assertEquals("1", info.getMagic());
	}

	@Test
	public void returnsNullWhenNpcIdDoesNotMatchVersionedPage()
	{
		NpcCombatInfo info = parser.parse(999999, "Goblin", new NpcWikiLookup("Goblin", "Level_99", "https://oldschool.runescape.wiki/w/Goblin#Level_99"), goblinWikitext());

		assertNull(info);
	}

	@Test
	public void searchWithoutNpcIdSelectsFirstVersion()
	{
		NpcCombatInfo info = parser.parse(-1, "Goblin", new NpcWikiLookup("Goblin", null, "https://oldschool.runescape.wiki/w/Goblin"), goblinWikitext());

		assertEquals(3028, info.getNpcId());
		assertEquals("2", info.getCombatLevel());
	}

	@Test
	public void searchWithoutNpcIdSelectsVersionByAnchor()
	{
		NpcCombatInfo info = parser.parse(-1, "Goblin", new NpcWikiLookup("Goblin", "Level_13", "https://oldschool.runescape.wiki/w/Goblin#Level_13"), goblinWikitext());

		assertEquals(3046, info.getNpcId());
		assertEquals("13", info.getCombatLevel());
		assertEquals("Crush", info.getAttackStyle());
	}

	@Test
	public void normalizesNestedTemplatesAndPipedLinks()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = [[Giant rat|Giant rat]]\n"
			+ "|combat = 3\n"
			+ "|attack style = {{Nowrap|{{Small|[[Magic#Magic combat|Magic]]}}}}\n"
			+ "|id = 2855\n"
			+ "}}";

		NpcCombatInfo info = parser.parse(2855, "Rat", new NpcWikiLookup("Giant_rat", null, "https://wiki"), wikitext);

		assertEquals("Giant rat", info.getDisplayName());
		assertEquals("Magic", info.getAttackStyle());
	}

	@Test
	public void formatsRespawnSecondsIndependentlyOfDefaultLocale()
	{
		Locale originalLocale = Locale.getDefault();
		try
		{
			Locale.setDefault(Locale.GERMANY);
			String wikitext = "{{Infobox Monster\n"
				+ "|name = Rat\n"
				+ "|respawn = 1\n"
				+ "|id = 2854\n"
				+ "}}";

			NpcCombatInfo info = parser.parse(2854, "Rat", new NpcWikiLookup("Rat", null, "https://wiki"), wikitext);

			assertEquals("1 ticks (0.6 seconds)", info.getRespawnTime());
		}
		finally
		{
			Locale.setDefault(originalLocale);
		}
	}

	@Test
	public void returnsNullForUnbalancedInfobox()
	{
		String wikitext = "{{Infobox Monster\n|name = Rat\n|id = 2854\n";

		assertNull(parser.parse(2854, "Rat", new NpcWikiLookup("Rat", null, "https://wiki"), wikitext));
	}

	@Test
	public void parsesSlayerDetailsAndClassifiedDrops()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Abyssal demon\n"
			+ "|combat = 124\n"
			+ "|id = 415\n"
			+ "|slaylvl = 85\n"
			+ "|cat = Demons\n"
			+ "|assignedby = [[Duradel]], [[Nieve]], [[Konar quo Maten]]\n"
			+ "|taskonly = No\n"
			+ "|superior = [[Greater abyssal demon]]\n"
			+ "}}\n"
			+ "== Drops ==\n"
			+ "{{DropsLine|name=Abyssal whip|rarity=1/512}}\n"
			+ "{{DropsLine|name=Adamant platebody|rarity=Rare}}\n"
			+ "{{DropsLine|name=Clue scroll (hard)|rarity=Rare}}\n"
			+ "{{DropsLine|name=Grimy ranarr weed|rarity=Uncommon}}\n"
			+ "{{DropsLine|name=Law rune|rarity=Common}}\n"
			+ "=== Slayer task ===\n"
			+ "{{DropsLine|name=Brimstone key|rarity=1/100}}\n"
			+ "{{DropsLine|name=Abyssal head|rarity=Very rare}}\n";

		NpcCombatInfo info = parser.parse(415, "Abyssal demon", new NpcWikiLookup("Abyssal_demon", null, "https://wiki"), wikitext);

		assertEquals("85", info.getSlayerLevel());
		assertEquals("Demons", info.getSlayerCategory());
		assertEquals("Duradel, Nieve, Konar quo Maten", info.getAssignedBy());
		assertEquals("No", info.getTaskOnly());
		assertEquals("Greater abyssal demon", info.getSuperiorVariant());
		assertEquals("Abyssal whip, Adamant platebody, Clue scroll (hard), Abyssal head", info.getRareDrops());
		assertEquals("Abyssal whip, Adamant platebody, Clue scroll (hard), Brimstone key, Abyssal head", info.getValuableDrops());
		assertEquals("Brimstone key, Abyssal head", info.getSlayerOnlyDrops());
		assertEquals("Adamant platebody", info.getAlchableDrops());
		assertEquals("Clue scroll (hard)", info.getClueDrops());
		assertEquals("Grimy ranarr weed, Law rune, Abyssal head", info.getIronmanDrops());
		assertEquals("Abyssal head", info.getUpgradeDrops());
		assertEquals("Grimy ranarr weed", info.getResourceDrops());
		assertEquals("Law rune", info.getSupplyDrops());
	}

	@Test
	public void parsesNpcItemRequirementsFromWikiLinks()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Gargoyle\n"
			+ "|combat = 111\n"
			+ "|id = 412\n"
			+ "}}\n"
			+ "Players must use a [[Rock hammer]] or [[Rock thrownhammer]] to finish killing gargoyles.\n"
			+ "The [[Gargoyle Smasher]] unlock can automatically finish killing gargoyles.\n";

		NpcCombatInfo info = parser.parse(412, "Gargoyle", new NpcWikiLookup("Gargoyle", null, "https://wiki"), wikitext);

		assertEquals(1, info.getItemRequirements().size());
		assertEquals("Rock hammer or Rock thrownhammer or Granite maul", info.getItemRequirements().get(0).getLabel());
		assertEquals("Rock hammer", info.getItemRequirements().get(0).getAlternatives().get(0));
		assertEquals("Rock thrownhammer", info.getItemRequirements().get(0).getAlternatives().get(1));
		assertEquals("Granite maul", info.getItemRequirements().get(0).getAlternatives().get(2));
	}

	@Test
	public void parsesNpcItemRequirementsFromItemTemplates()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Rockslug\n"
			+ "|combat = 29\n"
			+ "|id = 422\n"
			+ "}}\n"
			+ "A {{plink|Bag of salt}} is required to finish killing rockslugs.\n";

		NpcCombatInfo info = parser.parse(422, "Rockslug", new NpcWikiLookup("Rockslug", null, "https://wiki"), wikitext);

		assertEquals(1, info.getItemRequirements().size());
		assertEquals("Bag of salt", info.getItemRequirements().get(0).getLabel());
		assertEquals("Bag of salt", info.getItemRequirements().get(0).getAlternatives().get(0));
	}

	@Test
	public void ignoresNonItemUnlocksWhenParsingNpcItemRequirements()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Gargoyle\n"
			+ "|combat = 111\n"
			+ "|id = 412\n"
			+ "}}\n"
			+ "Players must unlock [[Gargoyle Smasher]] to finish killing gargoyles automatically.\n";

		NpcCombatInfo info = parser.parse(412, "Gargoyle", new NpcWikiLookup("Gargoyle", null, "https://wiki"), wikitext);

		assertEquals(0, info.getItemRequirements().size());
	}

	@Test
	public void ignoresSkillRequirementsWhenParsingNpcItemRequirements()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Abyssal demon\n"
			+ "|combat = 124\n"
			+ "|id = 415\n"
			+ "}}\n"
			+ "Players require 85 Slayer to kill abyssal demons.\n";

		NpcCombatInfo info = parser.parse(415, "Abyssal demon", new NpcWikiLookup("Abyssal_demon", null, "https://wiki"), wikitext);

		assertEquals(0, info.getItemRequirements().size());
	}

	@Test
	public void ignoresRecommendedAndSetupLinksWhenParsingNpcItemRequirements()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Hespori\n"
			+ "|combat = 284\n"
			+ "|id = 8583\n"
			+ "}}\n"
			+ "The '''Hespori''' is a [[Farming]] boss which requires {{SCP|Farming|65|link=yes}} {{Boostable|yes}} to access.\n"
			+ "To fight the Hespori, you must first plant a [[hespori seed]] in the [[hespori patch]].\n"
			+ "You will need a [[rake]] and a [[seed dibber]] unless the Farming portion of [[Barbarian Training]] has been completed.\n"
			+ "A [[toxic blowpipe]] with [[dart]]s and [[antipoison]] are useful, but not required to kill Hespori.\n"
			+ "The minimum [[HiScores]] killcount required for the Hespori has been lowered from 10 to 5.\n";

		NpcCombatInfo info = parser.parse(8583, "Hespori", new NpcWikiLookup("Hespori", null, "https://wiki"), wikitext);

		assertEquals(0, info.getItemRequirements().size());
	}

	@Test
	public void ignoresGreenDragonLocationAndClueLinksWhenParsingNpcItemRequirements()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Green dragon\n"
			+ "|combat = 79\n"
			+ "|id = 260\n"
			+ "}}\n"
			+ "'''Green dragons''' are found in various locations within the [[Wilderness]] as well as in the [[Corsair Cove Dungeon]].\n"
			+ "{{LocLine\n"
			+ "|name = Green dragon\n"
			+ "|location = [[Corsair Cove Dungeon]]/[[Myths' Guild|Myths' Guild (basement)]]<ref group=\"l\">Requires completion of [[Dragon Slayer II]]</ref>\n"
			+ "}}\n"
			+ "*{{CrypticNPC|Killing a green dragon|elite|Mix yellow with blue and add heat, make sure you bring protection.}} "
			+ "A [[brutal green dragon]] may also be killed for this step.\n";

		NpcCombatInfo info = parser.parse(260, "Green dragon", new NpcWikiLookup("Green_dragon", null, "https://wiki"), wikitext);

		assertEquals(0, info.getItemRequirements().size());
	}

	@Test
	public void keepsDropsAcrossSubheadingsButStopsAtNextTopLevelHeading()
	{
		String wikitext = "{{Infobox Monster\n"
			+ "|name = Test monster\n"
			+ "|id = 1\n"
			+ "}}\n"
			+ "== Drops ==\n"
			+ "=== Herbs ===\n"
			+ "{{DropsLine|name=Grimy ranarr weed|rarity=Uncommon}}\n"
			+ "=== Weapons and armour ===\n"
			+ "{{DropsLine|name=Rune platebody|rarity=Rare}}\n"
			+ "== Changes ==\n"
			+ "{{DropsLine|name=Dragon platebody|rarity=Rare}}\n";

		NpcCombatInfo info = parser.parse(1, "Test monster", new NpcWikiLookup("Test_monster", null, "https://wiki"), wikitext);

		assertEquals("Grimy ranarr weed, Rune platebody", info.getNotableDrops());
		assertEquals("Rune platebody", info.getRareDrops());
		assertEquals("Grimy ranarr weed", info.getResourceDrops());
	}

	@Test
	public void limitsDropSummariesToSixUniqueEntries()
	{
		String wikitext = "{{Infobox Monster|name=Test monster|id=1}}\n"
			+ "==Drops==\n"
			+ "{{DropsLine|name=First drop|rarity=Rare}}\n"
			+ "{{DropsLine|name=Second drop|rarity=Rare}}\n"
			+ "{{DropsLine|name=Third drop|rarity=Rare}}\n"
			+ "{{DropsLine|name=Fourth drop|rarity=Rare}}\n"
			+ "{{DropsLine|name=Fifth drop|rarity=Rare}}\n"
			+ "{{DropsLine|name=Sixth drop|rarity=Rare}}\n"
			+ "{{DropsLine|name=Seventh drop|rarity=Rare}}\n";

		NpcCombatInfo info = parser.parse(1, "Test monster", new NpcWikiLookup("Test_monster", null, "https://wiki"), wikitext);

		assertEquals("First drop, Second drop, Third drop, Fourth drop, Fifth drop, Sixth drop", info.getRareDrops());
	}

	private static String goblinWikitext()
	{
		return "{{Infobox Monster\n"
			+ "|version1 = Level 2\n"
			+ "|version2 = Level 13\n"
			+ "|name = Goblin\n"
			+ "|image1 = [[File:Goblin.png|150px]]\n"
			+ "|combat1 = 2\n"
			+ "|combat2 = 13\n"
			+ "|xpbonus = 0\n"
			+ "|max hit1 = 1\n"
			+ "|max hit2 = 2\n"
			+ "|aggressive = No\n"
			+ "|poisonous = No\n"
			+ "|attack style1 = [[Stab]]\n"
			+ "|attack style2 = [[Crush]]\n"
			+ "|attack speed1 = 4\n"
			+ "|attack speed2 = 4\n"
			+ "|respawn = 35\n"
			+ "|hitpoints1 = 5\n"
			+ "|hitpoints2 = 16\n"
			+ "|att1 = 1\n"
			+ "|att2 = 12\n"
			+ "|str1 = 1\n"
			+ "|str2 = 13\n"
			+ "|def1 = 1\n"
			+ "|def2 = 7\n"
			+ "|mage = 1\n"
			+ "|range = 1\n"
			+ "|attbns1 = -21\n"
			+ "|attbns2 = 0\n"
			+ "|strbns1 = -15\n"
			+ "|strbns2 = 0\n"
			+ "|amagic = 0\n"
			+ "|mbns = 0\n"
			+ "|arange = 0\n"
			+ "|rngbns = 0\n"
			+ "|dstab1 = -15\n"
			+ "|dstab2 = 4\n"
			+ "|dslash1 = -15\n"
			+ "|dslash2 = 6\n"
			+ "|dcrush1 = -15\n"
			+ "|dcrush2 = 8\n"
			+ "|dmagic1 = -15\n"
			+ "|dmagic2 = 4\n"
			+ "|dlight1 = -15\n"
			+ "|dlight2 = 4\n"
			+ "|dstandard1 = -15\n"
			+ "|dstandard2 = 4\n"
			+ "|dheavy1 = -15\n"
			+ "|dheavy2 = 4\n"
			+ "|poisonresistance = 0\n"
			+ "|venomresistance = 0\n"
			+ "|immunecannon = No\n"
			+ "|immunethrall = No\n"
			+ "|id1 = 3028,3029\n"
			+ "|id2 = 3046\n"
			+ "}}";
	}
}
