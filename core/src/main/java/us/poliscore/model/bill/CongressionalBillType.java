package us.poliscore.model.bill;

import java.util.Arrays;
import java.util.List;

import lombok.Getter;
import us.poliscore.legiscan.view.LegiscanBillType;
import us.poliscore.model.LegislativeChamber;

@Getter
public enum CongressionalBillType {
	
	SCONRES("sconres"),
	
	HRES("hres"),
	
	HCONRES("hconres"),
	
	S("s"),
	
	SJRES("sjres"),
	
	SRES("sres"),
	
	HJRES("hjres"),
	
	HR("hr");

	
	private String name;
	
	private CongressionalBillType(String name)
	{
		this.name = name;
	}
	
	public static List<CongressionalBillType> getIgnoredBillTypes()
	{
		return Arrays.asList(CongressionalBillType.SCONRES, CongressionalBillType.HCONRES, CongressionalBillType.HRES, CongressionalBillType.SRES);
	}
	
	public static CongressionalBillType fromName(String name) {
		return Arrays.asList(CongressionalBillType.values()).stream().filter(bt -> bt.getName().equals(name)).findFirst().get();
	}
	
	public static LegislativeChamber getOriginatingChamber(CongressionalBillType type)
	{
		if (type.equals(CongressionalBillType.HCONRES) || type.equals(CongressionalBillType.HJRES) || type.equals(CongressionalBillType.HR) || type.equals(CongressionalBillType.HRES))
		{
			return LegislativeChamber.LOWER;
		}
		else
		{
			return LegislativeChamber.UPPER;
		}
	}
}
