package us.poliscore.tooling;


import java.io.IOException;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import lombok.val;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.service.GovernmentDataService;
import us.poliscore.service.storage.LocalCachedS3Service;

@QuarkusMain(name="S3DataPatcher")
public class S3DataPatcher implements QuarkusApplication {
	
	@Inject
	private GovernmentDataService data;
	
	@Inject
	private LocalCachedS3Service s3;
	
	protected void process() throws IOException
	{
		val dataset = data.importDataset(LegislativeNamespace.US_COLORADO, 2025);
		
		dataset.optimizeExists(s3, BillInterpretation.class);
		
		long count = 0;
		
		for (var bill : dataset.query(Bill.class))
		{
//			val oldId = bill.getId().replace(Bill.ID_CLASS_PREFIX, BillInterpretation.ID_CLASS_PREFIX).replace(dataset.getSession().getKey() + "/", "");
//			val newId = BillInterpretation.generateId(bill.getId(), null);
//			
//			val interpOp = s3.get(oldId, BillInterpretation.class);
//			if (interpOp.isEmpty()) continue;
//			val interp = interpOp.get();
//			
//			interp.setId(newId);
//			interp.setBillId(bill.getId());
//			
//			for (int i = 0; i < interp.getSliceInterpretations().size(); ++i)
//			{
//				var slice = interp.getSliceInterpretations().get(i);
//				
//				val oldSliceId = bill.getId().replace(Bill.ID_CLASS_PREFIX, BillInterpretation.ID_CLASS_PREFIX).replace(dataset.getSession().getKey() + "/", "");
//				val newSliceId = BillInterpretation.generateId(bill.getId(), slice.getOrigin(), i);
//				
//				slice.setId(newSliceId);
//				slice.setBillId(bill.getId());
//				
//				s3.put(slice);
//				s3.delete(oldSliceId, BillInterpretation.class);
//				
//				count++;
//			}
//			
//			s3.put(interp);
//			s3.delete(oldId, BillInterpretation.class);
			
			count++;
		}
		
		System.out.println("Program complete. Patched " + count + " interpretations");
	}
	
	@Override
	public int run(String... args) throws Exception {
	  process();
	  
	  Quarkus.waitForExit();
	  return 0;
	}
	
	public static void main(String[] args) {
		Quarkus.run(S3DataPatcher.class, args);
		Quarkus.asyncExit(0);
	}
}
