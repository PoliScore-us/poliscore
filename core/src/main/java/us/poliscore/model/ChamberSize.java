package us.poliscore.model;

import java.util.Map;

import us.poliscore.legiscan.view.LegiscanState;

public class ChamberSize {
	
	public static final Map<LegiscanState, Map<LegislativeChamber, Integer>> STATE_LEG_CHAMBER_SIZES = Map.ofEntries(
		    Map.entry(LegiscanState.ALABAMA, Map.of(LegislativeChamber.UPPER, 35, LegislativeChamber.LOWER, 105)),
		    Map.entry(LegiscanState.ALASKA, Map.of(LegislativeChamber.UPPER, 20, LegislativeChamber.LOWER, 40)),
		    Map.entry(LegiscanState.ARIZONA, Map.of(LegislativeChamber.UPPER, 30, LegislativeChamber.LOWER, 60)),
		    Map.entry(LegiscanState.ARKANSAS, Map.of(LegislativeChamber.UPPER, 35, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.CALIFORNIA, Map.of(LegislativeChamber.UPPER, 40, LegislativeChamber.LOWER, 80)),
		    Map.entry(LegiscanState.COLORADO, Map.of(LegislativeChamber.UPPER, 35, LegislativeChamber.LOWER, 65)),
		    Map.entry(LegiscanState.CONNECTICUT, Map.of(LegislativeChamber.UPPER, 36, LegislativeChamber.LOWER, 151)),
		    Map.entry(LegiscanState.DELAWARE, Map.of(LegislativeChamber.UPPER, 21, LegislativeChamber.LOWER, 41)),
		    Map.entry(LegiscanState.FLORIDA, Map.of(LegislativeChamber.UPPER, 40, LegislativeChamber.LOWER, 120)),
		    Map.entry(LegiscanState.GEORGIA, Map.of(LegislativeChamber.UPPER, 56, LegislativeChamber.LOWER, 180)),
		    Map.entry(LegiscanState.HAWAII, Map.of(LegislativeChamber.UPPER, 25, LegislativeChamber.LOWER, 51)),
		    Map.entry(LegiscanState.IDAHO, Map.of(LegislativeChamber.UPPER, 35, LegislativeChamber.LOWER, 70)),
		    Map.entry(LegiscanState.ILLINOIS, Map.of(LegislativeChamber.UPPER, 59, LegislativeChamber.LOWER, 118)),
		    Map.entry(LegiscanState.INDIANA, Map.of(LegislativeChamber.UPPER, 50, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.IOWA, Map.of(LegislativeChamber.UPPER, 50, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.KANSAS, Map.of(LegislativeChamber.UPPER, 40, LegislativeChamber.LOWER, 125)),
		    Map.entry(LegiscanState.KENTUCKY, Map.of(LegislativeChamber.UPPER, 38, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.LOUISIANA, Map.of(LegislativeChamber.UPPER, 39, LegislativeChamber.LOWER, 105)),
		    Map.entry(LegiscanState.MAINE, Map.of(LegislativeChamber.UPPER, 35, LegislativeChamber.LOWER, 151)),
		    Map.entry(LegiscanState.MARYLAND, Map.of(LegislativeChamber.UPPER, 47, LegislativeChamber.LOWER, 141)),
		    Map.entry(LegiscanState.MASSACHUSETTS, Map.of(LegislativeChamber.UPPER, 40, LegislativeChamber.LOWER, 160)),
		    Map.entry(LegiscanState.MICHIGAN, Map.of(LegislativeChamber.UPPER, 38, LegislativeChamber.LOWER, 110)),
		    Map.entry(LegiscanState.MINNESOTA, Map.of(LegislativeChamber.UPPER, 67, LegislativeChamber.LOWER, 134)),
		    Map.entry(LegiscanState.MISSISSIPPI, Map.of(LegislativeChamber.UPPER, 52, LegislativeChamber.LOWER, 122)),
		    Map.entry(LegiscanState.MISSOURI, Map.of(LegislativeChamber.UPPER, 34, LegislativeChamber.LOWER, 163)),
		    Map.entry(LegiscanState.MONTANA, Map.of(LegislativeChamber.UPPER, 50, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.NEBRASKA, Map.of(LegislativeChamber.UPPER, 49)),
		    Map.entry(LegiscanState.NEVADA, Map.of(LegislativeChamber.UPPER, 21, LegislativeChamber.LOWER, 42)),
		    Map.entry(LegiscanState.NEW_HAMPSHIRE, Map.of(LegislativeChamber.UPPER, 24, LegislativeChamber.LOWER, 400)),
		    Map.entry(LegiscanState.NEW_JERSEY, Map.of(LegislativeChamber.UPPER, 40, LegislativeChamber.LOWER, 80)),
		    Map.entry(LegiscanState.NEW_MEXICO, Map.of(LegislativeChamber.UPPER, 42, LegislativeChamber.LOWER, 70)),
		    Map.entry(LegiscanState.NEW_YORK, Map.of(LegislativeChamber.UPPER, 63, LegislativeChamber.LOWER, 150)),
		    Map.entry(LegiscanState.NORTH_CAROLINA, Map.of(LegislativeChamber.UPPER, 50, LegislativeChamber.LOWER, 120)),
		    Map.entry(LegiscanState.NORTH_DAKOTA, Map.of(LegislativeChamber.UPPER, 47, LegislativeChamber.LOWER, 94)),
		    Map.entry(LegiscanState.OHIO, Map.of(LegislativeChamber.UPPER, 33, LegislativeChamber.LOWER, 99)),
		    Map.entry(LegiscanState.OKLAHOMA, Map.of(LegislativeChamber.UPPER, 48, LegislativeChamber.LOWER, 101)),
		    Map.entry(LegiscanState.OREGON, Map.of(LegislativeChamber.UPPER, 30, LegislativeChamber.LOWER, 60)),
		    Map.entry(LegiscanState.PENNSYLVANIA, Map.of(LegislativeChamber.UPPER, 50, LegislativeChamber.LOWER, 203)),
		    Map.entry(LegiscanState.RHODE_ISLAND, Map.of(LegislativeChamber.UPPER, 38, LegislativeChamber.LOWER, 75)),
		    Map.entry(LegiscanState.SOUTH_CAROLINA, Map.of(LegislativeChamber.UPPER, 46, LegislativeChamber.LOWER, 124)),
		    Map.entry(LegiscanState.SOUTH_DAKOTA, Map.of(LegislativeChamber.UPPER, 35, LegislativeChamber.LOWER, 70)),
		    Map.entry(LegiscanState.TENNESSEE, Map.of(LegislativeChamber.UPPER, 33, LegislativeChamber.LOWER, 99)),
		    Map.entry(LegiscanState.TEXAS, Map.of(LegislativeChamber.UPPER, 31, LegislativeChamber.LOWER, 150)),
		    Map.entry(LegiscanState.UTAH, Map.of(LegislativeChamber.UPPER, 29, LegislativeChamber.LOWER, 75)),
		    Map.entry(LegiscanState.VERMONT, Map.of(LegislativeChamber.UPPER, 30, LegislativeChamber.LOWER, 150)),
		    Map.entry(LegiscanState.VIRGINIA, Map.of(LegislativeChamber.UPPER, 40, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.WASHINGTON, Map.of(LegislativeChamber.UPPER, 49, LegislativeChamber.LOWER, 98)),
		    Map.entry(LegiscanState.WASHINGTON_DC, Map.of(LegislativeChamber.UPPER, 13)), // Unicameral
		    Map.entry(LegiscanState.WEST_VIRGINIA, Map.of(LegislativeChamber.UPPER, 34, LegislativeChamber.LOWER, 100)),
		    Map.entry(LegiscanState.WISCONSIN, Map.of(LegislativeChamber.UPPER, 33, LegislativeChamber.LOWER, 99)),
		    Map.entry(LegiscanState.WYOMING, Map.of(LegislativeChamber.UPPER, 31, LegislativeChamber.LOWER, 62)),
		    Map.entry(LegiscanState.CONGRESS, Map.of(LegislativeChamber.UPPER, 100, LegislativeChamber.LOWER, 435)),
		    Map.entry(LegiscanState.AMERICAN_SAMOA, Map.of(LegislativeChamber.UPPER, 18, LegislativeChamber.LOWER, 21)),
		    Map.entry(LegiscanState.GUAM, Map.of(LegislativeChamber.UPPER, 15)), // Unicameral
		    Map.entry(LegiscanState.NORTHERN_MARIANA_ISLANDS, Map.of(LegislativeChamber.UPPER, 9, LegislativeChamber.LOWER, 20)),
		    Map.entry(LegiscanState.PUERTO_RICO, Map.of(LegislativeChamber.UPPER, 27, LegislativeChamber.LOWER, 51)),
		    Map.entry(LegiscanState.VIRGIN_ISLANDS, Map.of(LegislativeChamber.UPPER, 15)) // Unicameral
		);

	public static Integer getChamberSize(LegiscanState state, LegislativeChamber chamber) {
	    Map<LegislativeChamber, Integer> chambers = STATE_LEG_CHAMBER_SIZES.get(state);
	    if (chambers == null) throw new UnsupportedOperationException("state " + state.getAbbreviation() + " is unsupported.");
	    return chambers.get(chamber);
	}

}
