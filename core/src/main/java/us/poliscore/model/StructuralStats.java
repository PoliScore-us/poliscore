package us.poliscore.model;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.Getter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import us.poliscore.model.bill.StructuralAnalysis;
import us.poliscore.model.dynamodb.StructuralStatsAttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;

@Data
@DynamoDbBean
@RegisterForReflection
public class StructuralStats {
	
	protected Map<StructuralAnalysis, Double> stats = new HashMap<StructuralAnalysis, Double>();
	
	@Getter(onMethod=@__({@JsonIgnore, @DynamoDbIgnore}))
	@JsonIgnore
	protected int totalSummed;
	
	@DynamoDbConvertedBy(StructuralStatsAttributeConverter.class)
	public Map<StructuralAnalysis, Double> getStats()
	{
		return this.stats;
	}
	
	@JsonIgnore
	public Double getStat(StructuralAnalysis issue)
	{
		return getStat(issue, 0.0f);
	}
	
	@JsonIgnore
	public double getStat(StructuralAnalysis issue, double defaultValue)
	{
		if (issue == null)
			return defaultValue;
		
		return stats.getOrDefault(issue, defaultValue);
	}
	
	
	public void removeStat(StructuralAnalysis issue)
	{
		stats.remove(issue);
	}
	
	public boolean hasStat(StructuralAnalysis issue)
	{
		return stats.containsKey(issue);
	}
	
	public void setStat(StructuralAnalysis issue, double value)
	{
		stats.put(issue, value);
	}
	
	public void addStat(StructuralAnalysis issue, double value)
	{
		stats.put(issue, getStat(issue) + value);
		totalSummed = totalSummed + 1;
	}
	
//	public StructrualStats sum(StructrualStats incoming)
//	{
//		return sum(incoming, 1.0f);
//	}
//	
//	public StructrualStats sum(StructrualStats incoming, float weight)
//	{
//		StructrualStats result = new StructrualStats();
//		
//		for (StructuralAnalysis issue : StructuralAnalysis.values())
//		{
//			if (!incoming.hasStat(issue) && !hasStat(issue)) continue;
//			
//			result.setStat(issue, getStat(issue) + incoming.getStat(issue));
//		}
//		
//		result.totalSummed = sumWeightMap(incoming.asWeightMap(weight));
//		
//		return result;
//	}
//	
	public StructuralStats divide(double divisor)
	{
		StructuralStats result = new StructuralStats();
		
		for (StructuralAnalysis issue : stats.keySet())
		{
			result.setStat(issue, (double)getStat(issue) / divisor);
		}
		
		return result;
	}
	
	public StructuralStats divideByTotalSummed() {
		return divide(totalSummed);
	}
	
	public StructuralStats multiply(double multiplier)
	{
		StructuralStats result = new StructuralStats();
		
		for (StructuralAnalysis issue : stats.keySet())
		{
			result.setStat(issue, (double)getStat(issue) * multiplier);
		}
		
		return result;
	}
	
	public StructuralStats addAll(Map<StructuralAnalysis, Boolean> structuralAnalysisPassFail) {
		StructuralStats result = new StructuralStats();
		
		for (StructuralAnalysis issue : structuralAnalysisPassFail.keySet())
		{
			double val = structuralAnalysisPassFail.get(issue) ? 1 : 0;
			result.setStat(issue, (double)getStat(issue) + val);
		}
		
		result.totalSummed = totalSummed + 1;
		
		return result;
	}
	
//	public DoubleIssueStats toDoubleIssueStats()
//	{
//		DoubleIssueStats result = new DoubleIssueStats();
//		
//		for (StructuralAnalysis issue : stats.keySet())
//		{
//			result.setStat(issue, getStat(issue));
//		}
//		result.totalSummed = totalSummed;
//		
//		return result;
//	}
//	
//	protected Map<StructuralAnalysis, Double> asWeightMap(double weight)
//	{
//		val result = new HashMap<StructuralAnalysis, Double>();
//		
//		for (StructuralAnalysis issue : stats.keySet())
//		{
//			result.put(issue, weight);
//		}
//		
//		return result;
//	}
//	
//	protected Map<StructuralAnalysis, Double> sumWeightMap(Map<StructuralAnalysis, Double> incoming)
//	{
//		if (totalSummed == null) { return incoming; }
//		
//		val result = new HashMap<StructuralAnalysis, Double>();
//		
//		for (StructuralAnalysis issue : StructuralAnalysis.values())
//		{
//			if (!incoming.containsKey(issue) && !totalSummed.containsKey(issue)) continue;
//			
//			result.put(issue, incoming.getOrDefault(issue, 0d) + totalSummed.getOrDefault(issue, 0d));
//		}
//		
//		return result;
//	}
//
//	@Override
//	public String toString()
//	{
//		StringBuilder sb = new StringBuilder();
//		
//	    sb.append(String.join("\n", stats.keySet().stream().sorted(new IssueStatsComparator()).map(issue ->"-" + issue.getName() + ": " + formatStatValue(getStat(issue))).toList()));
//		
//		return sb.toString();
//	}
//	
//	private String formatStatValue(int val)
//	{
//		return (val > 0 ? "+" : "") + String.valueOf(val);
//	}
//
//	@JsonIgnore
//	public int getRating() {
//		return getStat(StructuralAnalysis.OverallBenefitToSociety);
//	}
//	
//	public class IssueStatsComparator implements Comparator<StructuralAnalysis> {
//
//	    @Override
//	    public int compare(StructuralAnalysis a, StructuralAnalysis b) {
//	    	if (a.equals(StructuralAnalysis.OverallBenefitToSociety) && !b.equals(StructuralAnalysis.OverallBenefitToSociety)) {
//	    		return -1;
//	    	} else if (!a.equals(StructuralAnalysis.OverallBenefitToSociety) && b.equals(StructuralAnalysis.OverallBenefitToSociety)) {
//	    		return 1;
//	    	} else {
//	    		return Integer.valueOf(getStat(b)).compareTo(getStat(a));
//	    	}
//	    }
//
//	}
}
