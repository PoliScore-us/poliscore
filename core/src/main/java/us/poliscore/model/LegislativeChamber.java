package us.poliscore.model;

import lombok.Getter;
import us.poliscore.legiscan.view.LegiscanChamber;
import us.poliscore.legiscan.view.LegiscanRole;

@Getter
public enum LegislativeChamber {
	UPPER("Upper Chamber"), // Nebraska has a unicam, however their legislators are techincally Upper Chamber (Referred to as Senators)
	LOWER("Lower Chamber"),
	JOINT("Joint Conference");
	
	private String name;
	
	private LegislativeChamber(String name)
	{
		this.name = name;
	}
	
	public static LegislativeChamber fromLegiscanRole(LegiscanRole role) {
		if (role.equals(LegiscanRole.SENATOR)) {
			return UPPER;
		} else if (role.equals(LegiscanRole.REPRESENTATIVE)) {
			return LOWER;
		} else if (role.equals(LegiscanRole.JOINT_CONFERENCE)) {
			return JOINT;
		} else {
			throw new UnsupportedOperationException("Unsupported legiscan role : " + role.getCode());
		}
	}

	public static LegislativeChamber fromLegiscanChamber(LegiscanChamber chamber) {
		if (chamber.equals(LegiscanChamber.HOUSE)) {
			return LOWER;
		} else if (chamber.equals(LegiscanChamber.SENATE)) {
			return UPPER;
		} else if (chamber.equals(LegiscanChamber.UNICAM)) {
			return UPPER;
		} else {
			throw new UnsupportedOperationException("Unsupported legiscan chamber : " + chamber.getCode());
		}
	}
}
