package us.poliscore.model;

import java.util.Arrays;

import lombok.Getter;
import us.poliscore.legiscan.view.LegiscanState;

@Getter
public enum LegislativeNamespace {
	US_CONGRESS("us/congress"),
	US_ALABAMA("us/al"),
	US_ALASKA("us/ak"),
	US_ARIZONA("us/az"),
	US_ARKANSAS("us/ar"),
	US_CALIFORNIA("us/ca"),
	US_COLORADO("us/co"),
	US_CONNECTICUT("us/ct"),
	US_DELAWARE("us/de"),
	US_FLORIDA("us/fl"),
	US_GEORGIA("us/ga"),
	US_HAWAII("us/hi"),
	US_IDAHO("us/id"),
	US_ILLINOIS("us/il"),
	US_INDIANA("us/in"),
	US_IOWA("us/ia"),
	US_KANSAS("us/ks"),
	US_KENTUCKY("us/ky"),
	US_LOUISIANA("us/la"),
	US_MAINE("us/me"),
	US_MARYLAND("us/md"),
	US_MASSACHUSETTS("us/ma"),
	US_MICHIGAN("us/mi"),
	US_MINNESOTA("us/mn"),
	US_MISSISSIPPI("us/ms"),
	US_MISSOURI("us/mo"),
	US_MONTANA("us/mt"),
	US_NEBRASKA("us/ne"),
	US_NEVADA("us/nv"),
	US_NEW_HAMPSHIRE("us/nh"),
	US_NEW_JERSEY("us/nj"),
	US_NEW_MEXICO("us/nm"),
	US_NEW_YORK("us/ny"),
	US_NORTH_CAROLINA("us/nc"),
	US_NORTH_DAKOTA("us/nd"),
	US_OHIO("us/oh"),
	US_OKLAHOMA("us/ok"),
	US_OREGON("us/or"),
	US_PENNSYLVANIA("us/pa"),
	US_RHODE_ISLAND("us/ri"),
	US_SOUTH_CAROLINA("us/sc"),
	US_SOUTH_DAKOTA("us/sd"),
	US_TENNESSEE("us/tn"),
	US_TEXAS("us/tx"),
	US_UTAH("us/ut"),
	US_VERMONT("us/vt"),
	US_VIRGINIA("us/va"),
	US_WASHINGTON("us/wa"),
	US_WASHINGTON_DC("us/dc"),
	US_WEST_VIRGINIA("us/wv"),
	US_WISCONSIN("us/wi"),
	US_WYOMING("us/wy");

	private final String namespace;

	private LegislativeNamespace(String namespace) {
		this.namespace = namespace;
	}

	public static LegislativeNamespace of(String namespace)
	{
		return Arrays.asList(LegislativeNamespace.values()).stream().filter(n -> n.getNamespace().equals(namespace)).findFirst().get();
	}

	public static LegislativeNamespace fromAbbreviation(String abbr) {
		if (abbr == null) {
			throw new IllegalArgumentException("Abbreviation cannot be null");
		}
		String normalized = abbr.trim().toLowerCase();
		String fullNamespace = normalized.equals("us") ? "us/congress" : "us/" + normalized;

		return of(fullNamespace);
	}
	
	public String toAbbreviation() {
		return this.getNamespace().split("/")[1].toUpperCase();
	}
	
	public String toString() {
		return namespace;
	}
	
	public String getDescription() {
		if (this == US_CONGRESS) {
			return "Congress";
		}
		
		// Extract the state abbreviation from the namespace, e.g., "us/co" → "co"
		String abbr = namespace.substring(namespace.lastIndexOf('/') + 1).toUpperCase();

		try {
			LegiscanState state = LegiscanState.fromAbbreviation(abbr);
			return capitalizeFully(state.name().replace('_', ' '));
		} catch (IllegalArgumentException e) {
			// fallback for special cases like "us/dc"
			switch (abbr) {
				case "DC": return "Washington D.C.";
				default: return abbr;
			}
		}
	}

	// Helper to capitalize e.g., "NORTH_DAKOTA" → "North Dakota"
	private static String capitalizeFully(String input) {
		String[] parts = input.split(" ");
		StringBuilder result = new StringBuilder();
		for (String part : parts) {
			if (!part.isEmpty()) {
				result.append(part.substring(0, 1).toUpperCase());
				result.append(part.substring(1).toLowerCase());
				result.append(" ");
			}
		}
		return result.toString().trim();
	}
}

