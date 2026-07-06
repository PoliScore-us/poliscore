package us.poliscore.model;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;

@Data
@RegisterForReflection
public class BuildReport {
	
	public List<Bill> interpretedBills = new ArrayList<Bill>();
	
	public List<Bill> interpretedBillsWithErrors = new ArrayList<Bill>();
	
	public List<Legislator> interpretedLegislators = new ArrayList<Legislator>();
	
	public List<Throwable> fatalErrors = new ArrayList<Throwable>();
	
	double totalProcessingCost = 0;
	
	int normalRequests = 0;
	
	int flexRequests = 0;
	
	public void fatal(Throwable t) { fatalErrors.add(t); }
	
	public boolean hasFatal() { return !fatalErrors.isEmpty(); }
	
	@Override
	public String toString() {
		String result = "interpreted " + interpretedBills.size() + " bills and " + interpretedLegislators.size() + " legislators, amounting to " + flexRequests + " flex and " + normalRequests + " requests.";
		
		if (interpretedBills.size() > 0)
			result += "\n\n" + String.join("\n", interpretedBills.stream().map(b -> b.getId()).toList());
		
		if (interpretedLegislators.size() > 0)
			result += "\n\n" + String.join("\n", interpretedLegislators.stream().map(b -> b.getId()).toList());
		
		if (interpretedBillsWithErrors.size() > 0) {
			result += "\n\nEncountered " + interpretedBillsWithErrors.size() + " errors while interpreting bills. Full list of errored bills:";
			result += "\n" + String.join("\n", interpretedBillsWithErrors.stream().map(b -> b.getId()).toList());
		}
		
		for (Throwable t : fatalErrors) {
		    StringWriter sw = new StringWriter();
		    t.printStackTrace(new PrintWriter(sw));

		    result += "\n\n=== FATAL ERROR ===\n";
		    result += sw.toString();
		}
		
		if (totalProcessingCost > 0)
			result += "\nTotal processing cost is $" + totalProcessingCost;
		
		return result;
	}
	
}
