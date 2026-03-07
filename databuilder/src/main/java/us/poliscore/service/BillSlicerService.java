package us.poliscore.service;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.parsing.AIBillSlicer;
import us.poliscore.parsing.TextBillSlicer;
import us.poliscore.parsing.XMLBillSlicer;

@ApplicationScoped
public class BillSlicerService {
	
	@Inject private TextBillSlicer textSlicer;
	
	@Inject private XMLBillSlicer xmlSlicer;
	
	@Inject private AIBillSlicer aiSlicer;
	
	public List<BillSlice> slice(Bill bill, BillText btx, OpenAIModel model)
	{
		if (!StringUtils.isBlank(btx.getXml())) {
			return xmlSlicer.slice(bill, btx, model);
//			return aiSlicer.slice(bill, btx, maxSectionLength);
		} else {
			return textSlicer.slice(bill, btx, model);
		}
	}
}
