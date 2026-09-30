package com.inspect.npc;

import java.math.BigDecimal;
import lombok.Value;

/** The same weighted bonuses used for both ranking and the displayed explanation. */
@Value
public class EquipmentScore
{
	String accuracyLabel;
	Double accuracy;
	String damageLabel;
	Double damage;
	Double prayer;

	public double getTotal()
	{
		return value(accuracy) + value(damage) * 1.5d + value(prayer) * 0.1d;
	}

	public String getSummary()
	{
		return accuracyLabel + " " + bonus(accuracy) + " · " + damageLabel + " " + bonus(damage)
			+ " · Prayer " + bonus(prayer);
	}

	public String getExplanation()
	{
		return accuracyLabel + " " + term(accuracy) + " + " + damageLabel + " " + term(damage)
			+ " × 1.5 + Prayer " + term(prayer) + " × 0.1 = " + format(getTotal())
			+ ". Unknown stats (?) count as zero. Attack speed, special effects and equipment requirements are not included.";
	}

	private static double value(Double value)
	{
		return value == null ? 0 : value;
	}

	private static String bonus(Double value)
	{
		return value == null ? "?" : (value > 0 ? "+" : "") + format(value);
	}

	private static String term(Double value)
	{
		return value == null ? "0 (unknown)" : "(" + format(value) + ")";
	}

	private static String format(double value)
	{
		return BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}
}
