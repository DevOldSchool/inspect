package com.inspect.npc;

import com.inspect.item.ItemInspectInfo;
import lombok.Getter;

import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

public enum CombatStyleRecommendation
{
	STAB("Stab melee", "Stab defence", "Stab", ItemInspectInfo::getAttackStab, "Strength", ItemInspectInfo::getStrength),
	SLASH("Slash melee", "Slash defence", "Slash", ItemInspectInfo::getAttackSlash, "Strength", ItemInspectInfo::getStrength),
	CRUSH("Crush melee", "Crush defence", "Crush", ItemInspectInfo::getAttackCrush, "Strength", ItemInspectInfo::getStrength),
	MAGIC("Magic", "Magic defence", "Magic accuracy", ItemInspectInfo::getAttackMagic, "Magic damage (%)", ItemInspectInfo::getMagicDamage),
	RANGED("Ranged", "Ranged defence", "Ranged accuracy", ItemInspectInfo::getAttackRanged, "Ranged strength", ItemInspectInfo::getRangedStrength);

	@Getter
	private final String displayName;
	@Getter
	private final String defenceLabel;
	private final String accuracyLabel;
	private final java.util.function.Function<ItemInspectInfo, String> accuracy;
	private final String damageLabel;
	private final java.util.function.Function<ItemInspectInfo, String> damage;

	CombatStyleRecommendation(String displayName, String defenceLabel, String accuracyLabel,
		java.util.function.Function<ItemInspectInfo, String> accuracy, String damageLabel,
		java.util.function.Function<ItemInspectInfo, String> damage)
	{
		this.displayName = displayName;
		this.defenceLabel = defenceLabel;
		this.accuracyLabel = accuracyLabel;
		this.accuracy = accuracy;
		this.damageLabel = damageLabel;
		this.damage = damage;
	}

	public EquipmentScore scoreBreakdown(ItemInspectInfo item)
	{
		return new EquipmentScore(accuracyLabel, numericValue(accuracy.apply(item)), damageLabel,
			numericValue(damage.apply(item)), numericValue(item.getPrayer()));
	}

	double score(ItemInspectInfo item)
	{
		return scoreBreakdown(item).getTotal();
	}

	boolean isRelevant(ItemInspectInfo item)
	{
		return score(item) > 0;
	}

	public static CombatStyleRecommendation forNpc(NpcCombatInfo info)
	{
		if (info == null)
		{
			return null;
		}

		return java.util.stream.Stream.of(
				candidate(STAB, info.getStabDefence()),
				candidate(SLASH, info.getSlashDefence()),
				candidate(CRUSH, info.getCrushDefence()),
				candidate(MAGIC, info.getMagicDefence()),
				candidate(RANGED, lowest(info.getLightRangedDefence(), info.getStandardRangedDefence(), info.getHeavyRangedDefence()))
			)
			.filter(Objects::nonNull)
			.min(Comparator.comparingDouble(candidate -> candidate.defence))
			.map(candidate -> candidate.style)
			.orElse(null);
	}

	static Double numericValue(String value)
	{
		if (value == null || value.isEmpty())
		{
			return null;
		}

		String normalized = value.replace(",", "").toLowerCase(Locale.ENGLISH);
		StringBuilder number = new StringBuilder();
		boolean started = false;
		boolean hasDecimal = false;
		for (int i = 0; i < normalized.length(); i++)
		{
			char c = normalized.charAt(i);
			if (!started && (c == '+' || c == '-' || Character.isDigit(c)))
			{
				number.append(c);
				started = true;
				continue;
			}

			if (started && Character.isDigit(c))
			{
				number.append(c);
				continue;
			}

			if (started && c == '.' && !hasDecimal)
			{
				number.append(c);
				hasDecimal = true;
				continue;
			}

			if (started)
			{
				break;
			}
		}

		if (number.length() == 0 || "+".contentEquals(number) || "-".contentEquals(number))
		{
			return null;
		}

		try
		{
			double result = Double.parseDouble(number.toString());
			return Double.isFinite(result) ? result : null;
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	private static Candidate candidate(CombatStyleRecommendation style, String defence)
	{
		Double value = numericValue(defence);
		return value == null ? null : new Candidate(style, value);
	}

	private static String lowest(String... values)
	{
		Double lowest = null;
		for (String value : values)
		{
			Double numeric = numericValue(value);
			if (numeric != null && (lowest == null || numeric < lowest))
			{
				lowest = numeric;
			}
		}
		return lowest == null ? null : Double.toString(lowest);
	}

	private static final class Candidate
	{
		private final CombatStyleRecommendation style;
		private final double defence;

		private Candidate(CombatStyleRecommendation style, double defence)
		{
			this.style = style;
			this.defence = defence;
		}
	}
}
