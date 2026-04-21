package us.poliscore.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.val;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.InterpretationOrigin;
import us.poliscore.model.LegislativeSession;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * Two different interpretation strategies were tested for interpreting very large bills in "slices".
 * 
 * A) Summarize each slice and then interpret the summaries and ask AI to generate stats based on the summaries
 * B) Generate a small summary and also stats for each slice. Ask AI to summarize the summaries and then average all the stats for the final bill stats
 * 
 * These strategies were tested on BIL/us/congress/118/hr/8580 and it was determined that scenario A resulted in less accurate overall stats due to the fact
 * that each individual summary resulted in somewhat of a "telephone game" effect which resulted in a more "muted" outcome and which was less present when
 * averaging the stats from each slice.
 * 
 * Experimentations have also been made around concise versus longer responses, however the conclusion (with ChatGPT 4o) is that prompts without the "concise"
 * keyword tend to include a lot of wordy "filler" content without exposing much additional useful information from the bill. Longer form responses also suffer
 * from "header" content (the AI likes to have a paragraph for each tracked issue with a *** header *** format), however this can be avoided with a "include a
 * report without headers" phrasing.
 */
@ApplicationScoped
public class BillInterpretationService {
	
	@Inject
	protected OpenAIService ai;
	
	@Inject
	protected LocalCachedS3Service s3;
	
	@Inject
	protected BillService billService;
	
	@Inject
	private GovernmentDataService data;
	
	public Optional<BillInterpretation> getByBillId(String billId)
	{
		return getByBillId(billId, InterpretationOrigin.POLISCORE);
	}
	
	public Optional<BillInterpretation> getByBillId(String billId, InterpretationOrigin origin)
	{
		return s3.get(BillInterpretation.generateId(billId, origin, null), BillInterpretation.class);
	}
	
	public String getUserMsgForBill(Bill bill, String billText, OpenAIModel model) {
//		var userMsg = "Bill Text:\n" + billText;
//		
//		val op = s3.get(CBOBillAnalysis.generateId(bill.getId()), CBOBillAnalysis.class);
//		
//		if (op.isPresent()) {
//			userMsg = "Congressional Budget Office Analysis:\n" + op.get().getSummary() + "\n\n" + userMsg;
//		}
//		
//		return userMsg;
		
		// Dataset won't exist in deployed envs
		LegislativeSession session = SessionInfoService.sessionForId(bill.getId());
		
		String userMsg = "Today's Date: " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + "\n";
		userMsg += "Legislature: " + session.getDescription() + "\n";
		userMsg += "Bill: " + bill.getDescription() + "\n";
		userMsg += "Sponsor: " + bill.getSponsor().getName().getOfficial_full() + "\n";
		final String billTextMsg = "Official Bill Text:\n" + billText;
		
//		if (!DatabaseBuilder.AGENTIC_WEB_SEARCH) {
//			var pressInterps = billService.getPressInterps(bill.getId());
//			
//			if (pressInterps.size() > 0) {
//				String header = "References:\n" + "The following articles were pulled from a web search for this bill and were included to provide additional context for the interpretation. Their inclusion does not represent an endorsement of the opinions expressed from the source. Often a web search for a bill will reveal key legislative stakeholders, so view these articles with a skeptical eye. We want to prioritize what's best for all of America, not necessarily a few key stakeholders. Feel free to cite these sources using markdown link syntax in your long report if appropriate and relevant.\n\n";
//				
//				int context = billTextMsg.length() + header.length();
//				
//				for (int i = 0; i < pressInterps.size(); ++i)
//				{
//					var interp = pressInterps.get(i);
//					
//					String pressText = interp.getAuthor() + "(" + interp.getOrigin().getUrl() + ") - " + interp.getOrigin().getTitle() + ":\n" + interp.getLongExplain() + "\n\n";
//					
//					context += pressText.length();
//					
//					if (context < model.getContextWindowStringLength()) {
//						if (i == 0) {
//							userMsg = header;
//						}
//						
//						userMsg += pressText;
//					} else {
//						break;
//					}
//				}
//			}
//		}
		
		userMsg += billTextMsg;
		
		return userMsg;
	}
	
//	protected BillInterpretation getOrCreateAggregateInterpretation(Bill bill, IssueStats aggregateStats, String aggregateExplain, List<BillInterpretation> sliceInterps)
//	{
//		BillInterpretation bi = new BillInterpretation();
//		bi.setBill(bill);
//		
//		bi.setMetadata(OpenAIService.metadata());
//		bi.setSliceInterpretations(sliceInterps);
//		
//		String aiOut = ai.chat(aggregatePrompt, aggregateExplain);
//		new BillInterpretationParser(bi).parse(aiOut);
//		
//		bi.setIssueStats(aggregateStats);
//		bi.setId(BillInterpretation.generateId(bill.getId(), null));
//		
//		archive(bi);
//		
//		return bi;
//	}
	
//	public BillInterpretation getOrCreate(String billId)
//	{
//		val bill = billService.getById(billId).orElseThrow();
//		val interpId = BillInterpretation.generateId(bill.getId(), null);
//		val cached = s3.get(interpId, BillInterpretation.class);
//		
//		if (cached.isPresent())
//		{
//			return cached.get();
//		}
//		else
//		{
//			val interp = interpret(bill);
//			
//			return interp;
//		}
//	}
	
//	protected BillInterpretation interpret(Bill bill) throws MissingBillTextException
//	{
//		Log.info("Interpreting bill " + bill.getId() + " " + bill.getName());
//		
//		val billText = billService.getBillText(bill).orElseThrow(() -> new MissingBillTextException());
//		
//		bill.setText(billText);
//		
//		if (billText.getXml().length() >= OpenAIService.MAX_SECTION_LENGTH)
//    	{
//    		List<BillSlice> slices = new XMLBillSlicer().slice(bill, bill.getText(), OpenAIService.MAX_SECTION_LENGTH);
//    		List<AISliceInterpretationMetadata> sliceMetadata = new ArrayList<AISliceInterpretationMetadata>();
//    		List<BillInterpretation> sliceInterps = new ArrayList<BillInterpretation>();
//    		
//    		if (slices.size() == 0) throw new UnsupportedOperationException("Slicer returned zero slices?");
//    		else if (slices.size() == 1) {
//    			bill.getText().setXml(slices.get(0).getText()); // TODO : Hackity hack. This achieves our goal of treating it as the bill text but it's not actually xml
//    		} else {
//    			DoubleIssueStats billStats = new DoubleIssueStats();
//    			List<String> aggregateExplain = new ArrayList<String>();
//        		
//        		for (int i = 0; i < slices.size(); ++i)
//        		{
//        			BillSlice slice = slices.get(i);
//        			
//        			BillInterpretation sliceInterp = getOrCreateInterpretation(bill, slice);
//        			
//        			billStats = billStats.sum(sliceInterp.getIssueStats().toDoubleIssueStats());
//        			sliceMetadata.add((AISliceInterpretationMetadata) sliceInterp.getMetadata());
//        			
//        			sliceInterps.add(sliceInterp);
//        			aggregateExplain.add(sliceInterp.getShortExplain());
//        		}
//        		
//        		billStats = billStats.divideByTotalSummed();
//        		
//        		var bi = getOrCreateAggregateInterpretation(bill, billStats.toIssueStats(), String.join("\n", aggregateExplain), sliceInterps);
//        		
//        		return bi;
//    		}
//    	}
//		
//		var bi = getOrCreateInterpretation(bill, null);
//		
//    	return bi;
//	}
	
//	protected BillInterpretation getOrCreateInterpretation(Bill bill, BillSlice slice)
//	{
//		val id = BillInterpretation.generateId(bill.getId(), slice == null ? null : slice.getSliceIndex());
//		val cached = s3.get(id, BillInterpretation.class);
//		
//		if (cached.isPresent())
//		{
//			return cached.get();
//		}
//		else
//		{
//			BillInterpretation bi = new BillInterpretation();
//			bi.setBill(bill);
//			
//			String interpText;
//			if (slice == null)
//			{
//				interpText = ai.chat(slicePrompt, bill.getText().getXml());
//				bi.setMetadata(OpenAIService.metadata());
//			}
//			else
//			{
//				interpText = ai.chat(aggregatePrompt, slice.getText());
//				bi.setMetadata(OpenAIService.metadata(slice));
//			}
//			
//			new BillInterpretationParser(bi).parse(interpText);
//			bi.setId(id);
//			
//			archive(bi);
//			
//			return bi;
//		}
//	}
	
    protected void archive(BillInterpretation interp)
    {
    	s3.put(interp);
    }

	public boolean isInterpreted(@NonNull String billId) {
		val id = BillInterpretation.generateId(billId, InterpretationOrigin.POLISCORE, null);
		val exists = s3.exists(id, BillInterpretation.class);
		
		return exists;
		
//		if (!exists) return false;
//		
//		val aExists = s3.exists(CBOBillAnalysis.generateId(billId), CBOBillAnalysis.class);
//		
//		return !aExists || (aExists && s3.get(id, BillInterpretation.class).get().getBudgetChange10Yr() != null);
	}
	
	public boolean isInterpreted(@NonNull String billId, int sliceIndex) {
		val id = BillInterpretation.generateId(billId, InterpretationOrigin.POLISCORE, sliceIndex);
		return s3.exists(id, BillInterpretation.class);
	}
}
