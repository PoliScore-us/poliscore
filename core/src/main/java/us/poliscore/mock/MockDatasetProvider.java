package us.poliscore.mock;

import lombok.val;
import lombok.extern.jbosslog.JBossLog;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreDataset.DeploymentConfig;
import us.poliscore.dataset.DatasetProvider;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.CongressionalSession;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;

@JBossLog
public class MockDatasetProvider implements DatasetProvider {

	@Override
	public PoliscoreDatasetIF importDataset(DeploymentConfig ref) {
		val cses = CongressionalSession.fromYear(ref.getYear());
		LegislativeSession session = new LegislativeSession(true, cses.getStartDate(), cses.getEndDate(), String.valueOf(ref.getYear()), ref.getNamespace());
		PoliscoreDataset dataset = new PoliscoreDataset(session, ref);
		
		var billGen = new BillMockDataGenerator(dataset);
        for(var b : billGen.generate(5)) dataset.put(b);
        
        var gen = new LegislatorMockDataGenerator(dataset);
        for (var l : gen.generate(5)) dataset.put(l);
        
        log.info("Generated a mock database with " + dataset.count(Legislator.class) + " legislators and " + dataset.count(Bill.class) + " bills.");
        
        return dataset;
	}

//	@Override
//	public LegislativeSession getPreviousRegularSession(LegislativeSession current) {
//		return null;
//	}

	@Override
	public void syncS3LegislatorImages(PoliscoreDatasetIF dataset) {
		// Nothing to do
		
	}

	@Override
	public void syncS3BillText(PoliscoreDatasetIF dataset) {
		// Nothing to do
	}

}
