package us.poliscore.model;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import us.poliscore.legiscan.view.LegiscanChamber;
import us.poliscore.legiscan.view.LegiscanRole;
import us.poliscore.model.LegislativeChamber.LegislativeChamberDeserializer;

@Getter
@JsonDeserialize(using = LegislativeChamberDeserializer.class)
public enum LegislativeChamber {
	UPPER("Upper Chamber"), // Nebraska has a unicam, however their legislators are techincally Upper Chamber (Referred to as Senators)
	LOWER("Lower Chamber"),
	JOINT("Joint Conference");
	
	private String fallbackName;
	
	private LegislativeChamber(String fallbackName)
	{
		this.fallbackName = fallbackName;
	}
	
	public String getName(LegislativeNamespace namespace) {
		if (namespace == null) return fallbackName;

		switch (namespace) {
			case US_NEBRASKA:
				return "Legislature"; // Unicameral
			case US_VIRGINIA:
			case US_MARYLAND:
			case US_WEST_VIRGINIA:
				if (this.equals(LOWER)) return "House of Delegates";
				break;
			default:
				break;
		}

		if (this.equals(UPPER)) return "Senate";
		if (this.equals(LOWER)) return "House";

		return fallbackName;
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
	
	public static class LegislativeChamberDeserializer extends JsonDeserializer<LegislativeChamber> {

		@Override
		public LegislativeChamber deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			String text = p.getText().trim().toUpperCase();

			switch (text) {
				case "UPPER":
					return LegislativeChamber.UPPER;
				case "LOWER":
					return LegislativeChamber.LOWER;
				case "JOINT":
					return LegislativeChamber.JOINT;
				case "SENATE":
					return LegislativeChamber.UPPER;
				case "HOUSE":
					return LegislativeChamber.LOWER;
				default:
					throw new IllegalArgumentException("Unknown chamber value: " + text);
			}
		}
	}
}
