package com.inspect.item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

public final class ItemSourceReadinessEvaluator
{
	private static final List<QuestName> QUESTS_BY_NAME_LENGTH = questsByNameLength();

	private ItemSourceReadinessEvaluator()
	{
	}

	public static ItemSourceReadiness evaluate(
		List<ItemSource> sources,
		Map<Skill, Integer> skillLevels,
		Map<Quest, QuestState> questStates,
		ItemSourceAccountMode accountMode)
	{
		ItemSourceAccountMode resolvedMode = accountMode == null
			? ItemSourceAccountMode.STANDARD
			: accountMode;
		List<ItemSourceStatus> statuses = new ArrayList<>();
		if (sources != null)
		{
			for (ItemSource source : sources)
			{
				if (source != null)
				{
					statuses.add(evaluate(source, skillLevels, questStates));
				}
			}
		}

		if (resolvedMode.isIronman())
		{
			statuses.sort(Comparator
				.comparingInt((ItemSourceStatus status) -> status.isIronmanRelevant() ? 0 : 1)
				.thenComparingInt(status -> status.isReady() ? 0 : 1));
		}
		return new ItemSourceReadiness(
			resolvedMode,
			Collections.unmodifiableList(statuses));
	}

	public static List<Quest> referencedQuests(List<ItemSource> sources)
	{
		Set<Quest> quests = new LinkedHashSet<>();
		if (sources != null)
		{
			for (ItemSource source : sources)
			{
				quests.addAll(referencedQuests(source));
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(quests));
	}

	private static ItemSourceStatus evaluate(
		ItemSource source,
		Map<Skill, Integer> skillLevels,
		Map<Quest, QuestState> questStates)
	{
		List<String> met = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		if (source.getRequirements() != null)
		{
			for (ItemSourceRequirement requirement : source.getRequirements())
			{
				addSkillRequirement(requirement, skillLevels, met, missing);
			}
		}
		for (Quest quest : referencedQuests(source))
		{
			String label = quest.getName() + " quest";
			if (questStates != null && questStates.get(quest) == QuestState.FINISHED)
			{
				met.add(label);
			}
			else
			{
				missing.add(label);
			}
		}

		return new ItemSourceStatus(
			source,
			Collections.unmodifiableList(met),
			Collections.unmodifiableList(missing),
			isIronmanRelevant(source));
	}

	private static void addSkillRequirement(
		ItemSourceRequirement requirement,
		Map<Skill, Integer> skillLevels,
		List<String> met,
		List<String> missing)
	{
		if (requirement == null)
		{
			return;
		}

		Skill skill = skill(requirement.getSkillName());
		int currentLevel = skill == null || skillLevels == null
			? 1
			: skillLevels.getOrDefault(skill, 1);
		String label = requirement.getSkillName()
			+ " "
			+ requirement.getLevel()
			+ " ("
			+ currentLevel
			+ ")";
		if (skill != null && currentLevel >= requirement.getLevel())
		{
			met.add(label);
		}
		else
		{
			missing.add(label);
		}
	}

	private static Skill skill(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name.trim()))
			{
				return skill;
			}
		}
		return null;
	}

	private static List<Quest> referencedQuests(ItemSource source)
	{
		if (source == null || source.getDetails() == null || source.getDetails().isEmpty())
		{
			return Collections.emptyList();
		}

		String sourceText = String.join(" ", nonNull(source.getDetails()));
		String normalizedSource = normalize(sourceText);
		if (normalizedSource.isEmpty() || !hasQuestContext(source, normalizedSource))
		{
			return Collections.emptyList();
		}

		String paddedSource = " " + normalizedSource + " ";
		List<Quest> matches = new ArrayList<>();
		List<String> matchedNames = new ArrayList<>();
		for (QuestName questName : QUESTS_BY_NAME_LENGTH)
		{
			String normalizedName = questName.normalizedName;
			if (!paddedSource.contains(" " + normalizedName + " ")
				|| isCoveredByLongerMatch(normalizedName, matchedNames))
			{
				continue;
			}
			matches.add(questName.quest);
			matchedNames.add(normalizedName);
		}
		return matches;
	}

	private static boolean hasQuestContext(ItemSource source, String normalizedSource)
	{
		return "Quests".equals(source.getCategory())
			|| normalizedSource.contains(" quest")
			|| normalizedSource.contains(" miniquest")
			|| normalizedSource.contains(" complet")
			|| normalizedSource.contains(" after ")
			|| normalizedSource.contains(" during ")
			|| normalizedSource.contains(" reward");
	}

	private static boolean isCoveredByLongerMatch(String candidate, Collection<String> matchedNames)
	{
		String paddedCandidate = " " + candidate + " ";
		for (String matched : matchedNames)
		{
			if ((" " + matched + " ").contains(paddedCandidate))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isIronmanRelevant(ItemSource source)
	{
		String category = normalize(source.getCategory());
		String details = source.getDetails() == null
			? ""
			: normalize(String.join(" ", nonNull(source.getDetails())));
		if ("trading".equals(category) || "grand exchange".equals(category))
		{
			return false;
		}
		return !details.contains("bought from the grand exchange")
			&& !details.contains("bought on the grand exchange")
			&& !details.contains("purchased from the grand exchange")
			&& !details.contains("purchased on the grand exchange")
			&& !details.contains("obtained from the grand exchange")
			&& !details.contains("available from the grand exchange")
			&& !details.contains("available on the grand exchange")
			&& !details.contains("bought from other players")
			&& !details.contains("purchased from other players")
			&& !details.contains("obtained from other players")
			&& !details.contains("traded from another player");
	}

	private static List<String> nonNull(List<String> values)
	{
		List<String> present = new ArrayList<>();
		for (String value : values)
		{
			if (value != null && !value.trim().isEmpty())
			{
				present.add(value);
			}
		}
		return present;
	}

	private static String normalize(String value)
	{
		if (value == null)
		{
			return "";
		}

		StringBuilder normalized = new StringBuilder(value.length());
		boolean previousSpace = true;
		for (int i = 0; i < value.length(); i++)
		{
			char ch = Character.toLowerCase(value.charAt(i));
			if (Character.isLetterOrDigit(ch))
			{
				normalized.append(ch);
				previousSpace = false;
			}
			else if (!previousSpace)
			{
				normalized.append(' ');
				previousSpace = true;
			}
		}
		return normalized.toString().trim().toLowerCase(Locale.ENGLISH);
	}

	private static List<QuestName> questsByNameLength()
	{
		List<QuestName> quests = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			quests.add(new QuestName(quest, normalize(quest.getName())));
		}
		quests.sort(Comparator.comparingInt((QuestName quest) -> quest.normalizedName.length()).reversed());
		return Collections.unmodifiableList(quests);
	}

	private static final class QuestName
	{
		private final Quest quest;
		private final String normalizedName;

		private QuestName(Quest quest, String normalizedName)
		{
			this.quest = quest;
			this.normalizedName = normalizedName;
		}
	}
}
