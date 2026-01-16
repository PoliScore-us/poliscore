package us.poliscore.model;

import java.util.ArrayList;
import java.util.List;

import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;

public class BuildReport {
	
	public List<Bill> interpretedBills = new ArrayList<Bill>();
	
	public List<Legislator> interpretedLegislators = new ArrayList<Legislator>();
	
	public List<Throwable> fatalErrors = new ArrayList<Throwable>();
	
	public void fatal(Throwable t) { fatalErrors.add(t); }
	
	public boolean hasFatal() { return !fatalErrors.isEmpty(); }
	
	@Override
	public String toString() {
		String result = "interpreted " + interpretedBills.size() + " bills and " + interpretedLegislators.size() + " legislators.";
		
		if (interpretedBills.size() > 0)
			result += "\n\n" + String.join("\n", interpretedBills.stream().map(b -> b.getId()).toList());
		
		if (interpretedLegislators.size() > 0)
			result += "\n\n" + String.join("\n", interpretedLegislators.stream().map(b -> b.getId()).toList());
		
		for (Throwable t : fatalErrors) {
			t.printStackTrace();
		}
		
		return result;
	}
	
}
