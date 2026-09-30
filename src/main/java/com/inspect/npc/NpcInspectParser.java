package com.inspect.npc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NpcInspectParser
{
	private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^]|]+\\|)?([^]]+)]]");
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^{}|]+\\|)?([^{}|]+)}}");
	private static final Pattern ITEM_TEMPLATE_LINK = Pattern.compile("\\{\\{(?:plink|ilink|item)\\|([^}|]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern DROP_LINE = Pattern.compile("\\|name\\s*=\\s*([^\\n|}]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern DROPS_HEADING = Pattern.compile("(?im)^==[ \\t]*Drops[ \\t]*==[ \\t]*$");
	private static final Pattern LEVEL_TWO_HEADING = Pattern.compile("(?m)^==(?!=)[^\\r\\n]*?(?<![=])==[ \\t]*$");
	private static final Pattern ITEM_REQUIREMENT_CUE = Pattern.compile("\\b(?:must|need(?:ed)?|requires?|required|use|without)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern ITEM_REQUIREMENT_TARGET = Pattern.compile("\\b(?:kill(?:ing)?|finish(?:ing)?|defeat(?:ing)?|slay(?:ing)?)\\b", Pattern.CASE_INSENSITIVE);

	NpcCombatInfo parse(int npcId, String fallbackName, NpcWikiLookup lookup, String wikitext)
	{
		Map<String, String> fields = matchingFields(npcId, lookup.getAnchor(), wikitext);
		if (fields == null)
		{
			return null;
		}
		String suffix = selectVersionSuffix(npcId, lookup.getAnchor(), fields);
		if (suffix == null)
		{
			return null;
		}
		return parseFieldsInfo(npcId, fallbackName, lookup, wikitext, fields, suffix);
	}

	List<NpcCombatInfo> parseChoices(NpcWikiLookup page, String wikitext)
	{
		Map<String, NpcCombatInfo> choices = new LinkedHashMap<>();
		for (String infobox : extractInfoboxes(wikitext))
		{
			Map<String, String> fields = parseFields(infobox);
			Set<String> suffixes = new LinkedHashSet<>();
			for (String key : fields.keySet())
			{
				if (key.matches("version\\d+") || key.matches("id\\d+"))
				{
					suffixes.add(key.replaceFirst("^(version|id)", ""));
				}
			}
			if (suffixes.isEmpty())
			{
				suffixes.add("");
			}
			for (String suffix : suffixes)
			{
				int id = parseFirstId(value(fields, "id", suffix, null));
				if (id < 0)
				{
					continue;
				}
				String anchor = value(fields, "version", suffix, null);
				anchor = anchor == null || anchor.isEmpty() ? null : anchor.replace(' ', '_');
				String url = okhttp3.HttpUrl.get(page.getSourceUrl()).newBuilder().fragment(anchor).build().toString();
				NpcCombatInfo info = parseFieldsInfo(id, page.getPage().replace('_', ' '),
					new NpcWikiLookup(page.getPage(), anchor, url), wikitext, fields, suffix);
				choices.putIfAbsent(info.cacheKey(), info);
				if (choices.size() >= 50)
				{
					return new ArrayList<>(choices.values());
				}
			}
		}
		return new ArrayList<>(choices.values());
	}

	private NpcCombatInfo parseFieldsInfo(int npcId, String fallbackName, NpcWikiLookup lookup,
		String wikitext, Map<String, String> fields, String suffix)
	{
		int resolvedNpcId = npcId >= 0 ? npcId : parseFirstId(value(fields, "id", suffix, null));

		return NpcCombatInfo.builder()
			.npcId(resolvedNpcId)
			.wikiPage(lookup.getPage())
			.wikiAnchor(lookup.getAnchor())
			.imageFile(value(fields, "image", suffix, null))
			.displayName(value(fields, "name", suffix, fallbackName))
			.combatLevel(value(fields, "combat", suffix, null))
			.xpBonus(value(fields, "xpbonus", suffix, null))
			.maxHit(value(fields, "max hit", suffix, null))
			.aggressive(value(fields, "aggressive", suffix, null))
			.poisonous(value(fields, "poisonous", suffix, null))
			.attackStyle(value(fields, "attack style", suffix, null))
			.attackSpeed(value(fields, "attack speed", suffix, null))
			.respawnTime(formatTicks(value(fields, "respawn", suffix, null)))
			.hitpoints(value(fields, "hitpoints", suffix, null))
			.attack(value(fields, "att", suffix, null))
			.strength(value(fields, "str", suffix, null))
			.defence(value(fields, "def", suffix, null))
			.magic(value(fields, "mage", suffix, null))
			.ranged(value(fields, "range", suffix, null))
			.attackBonus(value(fields, "attbns", suffix, null))
			.strengthBonus(value(fields, "strbns", suffix, null))
			.magicAttack(value(fields, "amagic", suffix, null))
			.magicStrength(value(fields, "mbns", suffix, null))
			.rangedAttack(value(fields, "arange", suffix, null))
			.rangedStrength(value(fields, "rngbns", suffix, null))
			.stabDefence(value(fields, "dstab", suffix, null))
			.slashDefence(value(fields, "dslash", suffix, null))
			.crushDefence(value(fields, "dcrush", suffix, null))
			.magicDefence(value(fields, "dmagic", suffix, null))
			.elementalWeaknessType(value(fields, "elementalweaknesstype", suffix, null))
			.elementalWeakness(value(fields, "elementalweaknesspercent", suffix, "No elemental weakness"))
			.lightRangedDefence(value(fields, "dlight", suffix, null))
			.standardRangedDefence(value(fields, "dstandard", suffix, null))
			.heavyRangedDefence(value(fields, "dheavy", suffix, null))
			.poisonResistance(formatResistance(value(fields, "poisonresistance", suffix, null)))
			.venomResistance(formatResistance(value(fields, "venomresistance", suffix, null)))
			.cannonImmunity(formatImmunity(value(fields, "immunecannon", suffix, null)))
			.thrallImmunity(formatImmunity(value(fields, "immunethrall", suffix, null)))
			.slayerLevel(value(fields, "slaylvl", suffix, null))
			.slayerCategory(firstNonEmpty(
				value(fields, "cat", suffix, null),
				value(fields, "slaycat", suffix, null),
				value(fields, "assignedby", suffix, null)
			))
			.assignedBy(value(fields, "assignedby", suffix, null))
			.taskOnly(firstNonEmpty(value(fields, "taskonly", suffix, null), value(fields, "task only", suffix, null)))
			.superiorVariant(firstNonEmpty(
				value(fields, "superior", suffix, null),
				value(fields, "superior monster", suffix, null)
			))
			.notableDrops(dropSummary(wikitext, "uncommon", "rare", "very rare"))
			.rareDrops(classifiedDrops(wikitext, DropCategory.RARE))
			.valuableDrops(classifiedDrops(wikitext, DropCategory.VALUABLE))
			.slayerOnlyDrops(classifiedDrops(wikitext, DropCategory.SLAYER_ONLY))
			.uniqueDrops(classifiedDrops(wikitext, DropCategory.UNIQUE))
			.alchableDrops(classifiedDrops(wikitext, DropCategory.ALCHABLE))
			.clueDrops(classifiedDrops(wikitext, DropCategory.CLUE))
			.ironmanDrops(classifiedDrops(wikitext, DropCategory.IRONMAN))
			.upgradeDrops(classifiedDrops(wikitext, DropCategory.UPGRADE))
			.resourceDrops(classifiedDrops(wikitext, DropCategory.RESOURCE))
			.supplyDrops(classifiedDrops(wikitext, DropCategory.SUPPLY))
			.itemRequirements(itemRequirements(wikitext))
			.fetchedAtEpochSecond(Instant.now().getEpochSecond())
			.sourceUrl(lookup.getSourceUrl())
			.build();
	}

	static String extractInfobox(String wikitext)
	{
		int start = wikitext.indexOf("{{Infobox Monster");
		if (start < 0)
		{
			return null;
		}

		return extractInfobox(wikitext, start);
	}

	private static String extractInfobox(String wikitext, int start)
	{
		int depth = 0;
		for (int i = start; i < wikitext.length() - 1; i++)
		{
			String token = wikitext.substring(i, i + 2);
			if ("{{".equals(token))
			{
				depth++;
				i++;
			}
			else if ("}}".equals(token))
			{
				depth--;
				i++;
				if (depth == 0)
				{
					return wikitext.substring(start, i + 1);
				}
			}
		}

		return null;
	}

	private static Map<String, String> matchingFields(int npcId, String anchor, String wikitext)
	{
		List<Map<String, String>> candidates = new ArrayList<>();
		for (String infobox : extractInfoboxes(wikitext))
		{
			Map<String, String> fields = parseFields(infobox);
			if (selectVersionSuffixByAnchor(anchor, fields) != null)
			{
				return fields;
			}
			candidates.add(fields);
		}
		for (Map<String, String> fields : candidates)
		{
			for (Map.Entry<String, String> entry : fields.entrySet())
			{
				if (entry.getKey().matches("id\\d*")
					&& java.util.Arrays.asList(entry.getValue().replace(" ", "").split(",")).contains(Integer.toString(npcId)))
				{
					return fields;
				}
			}
		}
		return candidates.size() == 1 || (npcId < 0 && anchor == null && !candidates.isEmpty())
			? candidates.get(0) : null;
	}

	private static List<String> extractInfoboxes(String wikitext)
	{
		List<String> infoboxes = new ArrayList<>();
		int searchFrom = 0;
		while (searchFrom < wikitext.length())
		{
			int start = wikitext.indexOf("{{Infobox Monster", searchFrom);
			if (start < 0)
			{
				break;
			}

			String infobox = extractInfobox(wikitext, start);
			if (infobox == null)
			{
				break;
			}

			infoboxes.add(infobox);
			searchFrom = start + infobox.length();
		}
		return infoboxes;
	}

	static Map<String, String> parseFields(String infobox)
	{
		Map<String, String> fields = new LinkedHashMap<>();
		String infoboxBody = infobox.endsWith("}}") ? infobox.substring(0, infobox.length() - 2) : infobox;
		for (String part : splitTopLevel(infoboxBody))
		{
			part = part.trim();
			int equals = part.indexOf('=');
			if (equals <= 0)
			{
				continue;
			}

			String key = part.substring(0, equals).trim().toLowerCase();
			String rawValue = part.substring(equals + 1).trim();
			String value = key.matches("image\\d*") ? imageFile(rawValue) : normalizeValue(rawValue);
			if (!key.isEmpty())
			{
				fields.put(key, value);
			}
		}
		return fields;
	}

	private static String selectVersionSuffix(int npcId, String anchor, Map<String, String> fields)
	{
		String exactAnchorSuffix = selectVersionSuffixByAnchor(anchor, fields);
		if (exactAnchorSuffix != null)
		{
			return exactAnchorSuffix;
		}
		if (npcId < 0)
		{
			String anchorSuffix = selectVersionSuffixByAnchor(anchor, fields);
			if (anchorSuffix != null)
			{
				return anchorSuffix;
			}

			if (fields.containsKey("id") || !fields.containsKey("version1"))
			{
				return "";
			}
			return "1";
		}

		String id = Integer.toString(npcId);
		for (Map.Entry<String, String> entry : fields.entrySet())
		{
			String key = entry.getKey();
			if (!key.matches("id\\d*"))
			{
				continue;
			}

			for (String candidate : entry.getValue().split(","))
			{
				if (id.equals(candidate.trim()))
				{
					return key.substring(2);
				}
			}
		}

		String anchorSuffix = selectVersionSuffixByAnchor(anchor, fields);
		if (anchorSuffix != null)
		{
			return anchorSuffix;
		}

		return fields.containsKey("id") || !fields.containsKey("version1") ? "" : null;
	}

	private static String selectVersionSuffixByAnchor(String anchor, Map<String, String> fields)
	{
		if (anchor == null)
		{
			return null;
		}

		String normalizedAnchor = anchor.replace('_', ' ').trim();
		for (Map.Entry<String, String> entry : fields.entrySet())
		{
			if (entry.getKey().startsWith("version") && normalizedAnchor.equalsIgnoreCase(entry.getValue()))
			{
				return entry.getKey().substring("version".length());
			}
		}
		return null;
	}

	private static int parseFirstId(String value)
	{
		if (value == null)
		{
			return -1;
		}

		for (String candidate : value.split(","))
		{
			String trimmed = candidate.trim();
			if (trimmed.matches("\\d+"))
			{
				return Integer.parseInt(trimmed);
			}
		}
		return -1;
	}

	private static String value(Map<String, String> fields, String key, String suffix, String fallback)
	{
		String suffixed = fields.get(key + suffix);
		if (suffixed != null && !suffixed.isEmpty())
		{
			return suffixed;
		}

		String unsuffixed = fields.get(key);
		if (unsuffixed != null && !unsuffixed.isEmpty())
		{
			return unsuffixed;
		}

		return fallback;
	}

	private static String imageFile(String raw)
	{
		Matcher file = Pattern.compile("(?i)(?:File:|Image:)([^|\\]\\r\\n]+)").matcher(raw);
		String name = file.find() ? file.group(1).trim() : raw.trim();
		return name.matches("(?i)[^{}\\[\\]|]+\\.(png|gif|jpe?g|webp)") ? name : null;
	}

	private static String normalizeValue(String value)
	{
		String normalized = value.replace("&nbsp;", " ").replace("&#160;", " ");
		Matcher linkMatcher = WIKI_LINK.matcher(normalized);
		StringBuilder linkBuffer = new StringBuilder();
		while (linkMatcher.find())
		{
			linkMatcher.appendReplacement(linkBuffer, Matcher.quoteReplacement(linkMatcher.group(2)));
		}
		linkMatcher.appendTail(linkBuffer);

		return HTML_TAG.matcher(normalizeTemplates(linkBuffer.toString()))
			.replaceAll("")
			.replace("'''", "")
			.replace("''", "")
			.trim();
	}

	private static String normalizeTemplates(String value)
	{
		String normalized = value;
		while (true)
		{
			Matcher matcher = TEMPLATE.matcher(normalized);
			if (!matcher.find())
			{
				return normalized;
			}

			StringBuilder buffer = new StringBuilder();
			do
			{
				matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(2)));
			}
			while (matcher.find());
			matcher.appendTail(buffer);
			normalized = buffer.toString();
		}
	}

	private static String formatTicks(String value)
	{
		if (value == null || !value.matches("\\d+"))
		{
			return value;
		}

		int ticks = Integer.parseInt(value);
		double seconds = ticks * 0.6d;
		String secondsText = seconds == Math.rint(seconds) ? Integer.toString((int) seconds) : String.format(Locale.ENGLISH, "%.1f", seconds);
		return ticks + " ticks (" + secondsText + " seconds)";
	}

	private static String formatResistance(String value)
	{
		if (value == null || value.contains("%"))
		{
			return value;
		}

		return value + "% resistance";
	}

	private static String formatImmunity(String value)
	{
		if (value == null)
		{
			return null;
		}

		if ("yes".equalsIgnoreCase(value))
		{
			return "Immune";
		}

		if ("no".equalsIgnoreCase(value))
		{
			return "Not immune";
		}

		return value;
	}

	private static String firstNonEmpty(String... values)
	{
		for (String value : values)
		{
			if (value != null && !value.isEmpty())
			{
				return value;
			}
		}
		return null;
	}

	private static String dropSummary(String wikitext, String... rarityTokens)
	{
		String drops = dropSection(wikitext);
		if (drops == null)
		{
			return null;
		}

		List<String> names = new ArrayList<>();
		Matcher matcher = DROP_LINE.matcher(drops);
		while (matcher.find() && names.size() < 6)
		{
			int lineStart = Math.max(0, drops.lastIndexOf('\n', matcher.start()));
			int lineEnd = drops.indexOf('\n', matcher.end());
			String line = drops.substring(lineStart, lineEnd < 0 ? drops.length() : lineEnd).toLowerCase();
			if (!containsAny(line, rarityTokens))
			{
				continue;
			}

			String name = normalizeValue(matcher.group(1));
			if (!name.isEmpty() && !names.contains(name))
			{
				names.add(name);
			}
		}
		return names.isEmpty() ? null : String.join(", ", names);
	}

	private static boolean containsAny(String value, String... tokens)
	{
		for (String token : tokens)
		{
			if (value.contains(token))
			{
				return true;
			}
		}
		return false;
	}

	private static List<NpcItemRequirement> itemRequirements(String wikitext)
	{
		if (wikitext == null || wikitext.isEmpty())
		{
			return Collections.emptyList();
		}

		List<NpcItemRequirement> requirements = new ArrayList<>();
		Set<String> seenLabels = new LinkedHashSet<>();
		for (String sentence : wikitext.split("(?<=[.!?])\\s+|\\r?\\n+"))
		{
			String normalizedSentence = normalizeValue(sentence);
			if (!isItemRequirementSentence(normalizedSentence))
			{
				continue;
			}

			List<String> alternatives = itemRequirementAlternatives(sentence);
			if (alternatives.isEmpty())
			{
				continue;
			}

			String label = String.join(" or ", alternatives);
			String normalizedLabel = label.toLowerCase(Locale.ENGLISH);
			if (seenLabels.add(normalizedLabel))
			{
				requirements.add(new NpcItemRequirement(label, alternatives));
			}
		}
		return requirements.isEmpty() ? Collections.emptyList() : requirements;
	}

	private static boolean isItemRequirementSentence(String sentence)
	{
		String lower = sentence.toLowerCase(Locale.ENGLISH);
		if (containsAny(lower,
			"not required",
			"not need",
			"not needed",
			"not necessary",
			"no requirement",
			"without needing"))
		{
			return false;
		}

		return ITEM_REQUIREMENT_CUE.matcher(sentence).find()
			&& ITEM_REQUIREMENT_TARGET.matcher(sentence).find();
	}

	private static List<String> itemRequirementAlternatives(String sentence)
	{
		List<String> alternatives = new ArrayList<>();
		addItemRequirementLinks(alternatives, sentence);
		addItemRequirementTemplates(alternatives, sentence);
		addKnownItemRequirementAlternatives(alternatives, sentence);
		return alternatives;
	}

	private static void addKnownItemRequirementAlternatives(List<String> alternatives, String sentence)
	{
		String lower = sentence.toLowerCase(Locale.ENGLISH);
		if (lower.contains("gargoyle") && containsAnyItem(alternatives, "rock hammer", "rock thrownhammer", "granite hammer"))
		{
			addItemRequirementAlternative(alternatives, "Granite maul");
		}
	}

	private static boolean containsAnyItem(List<String> items, String... names)
	{
		for (String item : items)
		{
			for (String name : names)
			{
				if (item.equalsIgnoreCase(name))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static void addItemRequirementLinks(List<String> alternatives, String sentence)
	{
		Matcher matcher = WIKI_LINK.matcher(sentence);
		while (matcher.find())
		{
			addItemRequirementAlternative(alternatives, matcher.group(2));
		}
	}

	private static void addItemRequirementTemplates(List<String> alternatives, String sentence)
	{
		Matcher matcher = ITEM_TEMPLATE_LINK.matcher(sentence);
		while (matcher.find())
		{
			addItemRequirementAlternative(alternatives, matcher.group(1));
		}
	}

	private static void addItemRequirementAlternative(List<String> alternatives, String value)
	{
		String name = normalizeValue(value).replace('_', ' ').trim();
		if (!isLikelyItemRequirement(name))
		{
			return;
		}

		for (String existing : alternatives)
		{
			if (existing.equalsIgnoreCase(name))
			{
				return;
			}
		}
		alternatives.add(name);
	}

	private static boolean isLikelyItemRequirement(String name)
	{
		if (name == null || name.isEmpty() || name.length() > 48)
		{
			return false;
		}

		String lower = name.toLowerCase(Locale.ENGLISH);
		return !containsAny(lower, "slayer", "hitpoints", "attack", "strength", "defence", "ranged", "magic", "prayer", "combat", "smasher");
	}

	private static String classifiedDrops(String wikitext, DropCategory category)
	{
		String drops = dropSection(wikitext);
		if (drops == null)
		{
			return null;
		}

		List<String> matching = new ArrayList<>();
		Matcher matcher = DROP_LINE.matcher(drops);
		while (matcher.find())
		{
			String drop = normalizeValue(matcher.group(1));
			String context = dropContext(drops, matcher.start(), matcher.end());
			if (!drop.isEmpty() && category.matches(drop, context) && !matching.contains(drop))
			{
				matching.add(drop);
				if (matching.size() >= 6)
				{
					break;
				}
			}
		}
		return matching.isEmpty() ? null : String.join(", ", matching);
	}

	private static String dropContext(String drops, int matchStart, int matchEnd)
	{
		int lineStart = Math.max(0, drops.lastIndexOf('\n', matchStart));
		int lineEnd = drops.indexOf('\n', matchEnd);
		String line = drops.substring(lineStart, lineEnd < 0 ? drops.length() : lineEnd);

		String heading = "";
		Matcher headingMatcher = Pattern.compile("(?m)^===[^\\r\\n]*===[ \\t]*$").matcher(drops);
		while (headingMatcher.find() && headingMatcher.start() < matchStart)
		{
			heading = headingMatcher.group();
		}
		return (heading + " " + line).toLowerCase(Locale.ENGLISH);
	}

	private static List<String> dropNames(String wikitext)
	{
		String drops = dropSection(wikitext);
		if (drops == null)
		{
			return java.util.Collections.emptyList();
		}

		List<String> names = new ArrayList<>();
		Matcher matcher = DROP_LINE.matcher(drops);
		while (matcher.find() && names.size() < 30)
		{
			String name = normalizeValue(matcher.group(1));
			if (!name.isEmpty() && !names.contains(name))
			{
				names.add(name);
			}
		}
		return names;
	}

	private static String dropSection(String wikitext)
	{
		Matcher dropsHeading = DROPS_HEADING.matcher(wikitext);
		if (!dropsHeading.find())
		{
			return null;
		}

		int start = dropsHeading.end();
		Matcher nextHeading = LEVEL_TWO_HEADING.matcher(wikitext);
		int end = nextHeading.find(start) ? nextHeading.start() : wikitext.length();
		return wikitext.substring(start, end);
	}

	private enum DropCategory
	{
		VALUABLE
			{
				@Override
				boolean matches(String drop, String context)
				{
					return RARE.matches(drop, context) || UNIQUE.matches(drop, context) || ALCHABLE.matches(drop, context);
				}
			},
		RARE
			{
				@Override
				boolean matches(String drop, String context)
				{
					return containsAny(context, "rare", "very rare") || numericRarityDenominator(context) >= 128;
				}
			},
		SLAYER_ONLY
			{
				@Override
				boolean matches(String drop, String context)
				{
					return containsAny(context, "slayer", "task only", "on task", "task-only");
				}
			},
		UNIQUE
			{
				@Override
				boolean matches(String drop, String context)
				{
					String lower = drop.toLowerCase(Locale.ENGLISH);
					return lower.contains("clue") || lower.contains("key") || lower.contains("shard") || lower.contains("sigil")
						|| lower.contains("pet") || lower.contains("visage") || lower.contains("head") || lower.contains("unique");
				}
			},
		ALCHABLE
			{
				@Override
				boolean matches(String drop, String context)
				{
					String lower = drop.toLowerCase(Locale.ENGLISH);
					return lower.contains("rune ") || lower.contains("dragon ") || lower.contains("adamant ")
						|| lower.contains("mithril ") || lower.contains("battleaxe") || lower.contains("platebody")
						|| lower.contains("kiteshield") || lower.contains("longsword");
				}
			},
		CLUE
			{
				@Override
				boolean matches(String drop, String context)
				{
					return drop.toLowerCase(Locale.ENGLISH).contains("clue");
				}
			},
		IRONMAN
			{
				@Override
				boolean matches(String drop, String context)
				{
					return RESOURCE.matches(drop, context) || SUPPLY.matches(drop, context) || UPGRADE.matches(drop, context);
				}
			},
		UPGRADE
			{
				@Override
				boolean matches(String drop, String context)
				{
					String lower = drop.toLowerCase(Locale.ENGLISH);
					return containsAny(lower, "upgrade", "attachment", "shard", "sigil", "visage", "hilt", "claw",
						"fang", "jaw", "heart", "gem", "dust", "clamp", "needle", "thread", "totem", "head");
				}
			},
		RESOURCE
			{
				@Override
				boolean matches(String drop, String context)
				{
					String lower = drop.toLowerCase(Locale.ENGLISH);
					return lower.contains("ore") || lower.contains("log") || lower.contains("bar") || lower.contains("herb")
						|| lower.startsWith("grimy ") || lower.contains(" weed") || lower.contains("seed")
						|| lower.contains("hide") || lower.contains("scale") || lower.contains("essence");
				}
			},
		SUPPLY
			{
				@Override
				boolean matches(String drop, String context)
				{
					String lower = drop.toLowerCase(Locale.ENGLISH);
					return lower.contains("bone") || lower.contains("rune") || lower.contains("arrow") || lower.contains("bolt")
						|| lower.contains("food") || lower.contains("shark") || lower.contains("lobster")
						|| lower.contains("potion") || lower.contains("ammo");
				}
			};

		abstract boolean matches(String drop, String context);
	}

	private static int numericRarityDenominator(String context)
	{
		Matcher matcher = Pattern.compile("1\\s*/\\s*(\\d+)").matcher(context);
		if (!matcher.find())
		{
			return 0;
		}

		try
		{
			return Integer.parseInt(matcher.group(1));
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private static Iterable<String> splitTopLevel(String infobox)
	{
		Map<Integer, String> parts = new LinkedHashMap<>();
		int depth = 0;
		int linkDepth = 0;
		int start = 0;
		int index = 0;
		for (int i = 0; i < infobox.length(); i++)
		{
			if (i < infobox.length() - 1 && "[[".equals(infobox.substring(i, i + 2)))
			{
				linkDepth++;
				i++;
				continue;
			}
			if (i < infobox.length() - 1 && "]]".equals(infobox.substring(i, i + 2)))
			{
				linkDepth = Math.max(0, linkDepth - 1);
				i++;
				continue;
			}
			if (i < infobox.length() - 1 && "{{".equals(infobox.substring(i, i + 2)))
			{
				depth++;
				i++;
				continue;
			}
			if (i < infobox.length() - 1 && "}}".equals(infobox.substring(i, i + 2)))
			{
				depth = Math.max(0, depth - 1);
				i++;
				continue;
			}
			if (infobox.charAt(i) == '|' && depth == 1 && linkDepth == 0)
			{
				parts.put(index++, infobox.substring(start, i));
				start = i + 1;
			}
		}
		parts.put(index, infobox.substring(start));
		return parts.values();
	}
}
