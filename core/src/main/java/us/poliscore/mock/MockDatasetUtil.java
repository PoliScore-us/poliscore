package us.poliscore.mock;

import java.util.EnumMap;
import java.util.Random;

import lombok.val;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.IssueStats;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.TrackedIssue;

public class MockDatasetUtil {
	public static DeploymentConfig mockDeployment() {
		return new DeploymentConfig(LegislativeNamespace.US_CONGRESS, 2026);
	}
	
	public static PoliscoreDataset mockDataset() {
		var ref = mockDeployment();
    	val cses = CongressionalSession.fromYear(ref.getYear());
		LegislativeSession session = new LegislativeSession(true, cses.getStartDate(), cses.getEndDate(), String.valueOf(ref.getYear()), ref.getNamespace());
		PoliscoreDataset dataset = new PoliscoreDataset(session, ref);
		return dataset;
	}
	
	public static IssueStats issueStats(Random rnd) {
        // Heuristic: OverallBenefitToSociety drives rating; other issues vary around smaller magnitudes.
        var m = new EnumMap<TrackedIssue, Integer>(TrackedIssue.class);
        int overall = 10 + rnd.nextInt(81); // 10..90 positive-leaning for demos
        if (rnd.nextDouble() < 0.15) overall = -overall; // occasionally negative

        for (TrackedIssue issue : TrackedIssue.values()) {
            int v;
            if (issue == TrackedIssue.OverallBenefitToSociety) {
                v = overall;
            } else {
                // small/medium effects across issues
                int span = 40;
                v = rnd.nextInt(span + 1) - (span / 2); // roughly -20..+20
            }
            m.put(issue, Math.clamp(v, -100, 100));
        }

        // ---- Populate an IssueStats instance ----
        IssueStats stats = new IssueStats();
        // If your IssueStats API differs, adapt here accordingly.
        for (var e : m.entrySet()) {
            stats.setStat(e.getKey(), e.getValue());
        }
        return stats;
    }
}
