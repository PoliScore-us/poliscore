package us.poliscore.parsing;

import static org.joox.JOOX.$;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.amazonaws.util.CollectionUtils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillPrompt;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.service.TokenEstimatorService;

/**
 * Even though this slicer is operating on XML, the return is the text content inside the XML. This helps us save on tokens and return larger slices.
 */
@ApplicationScoped
public class XMLBillSlicer implements BillSlicer {

	private int sliceIndex = 0;
	
	private String title;
	
	@Inject TokenEstimatorService tokenEstimatorService;
	
	@Override
	@SneakyThrows
	public List<BillSlice> slice(Bill bill, BillText btx, OpenAIModel model) {
		Node doc = (Node) toDoc(btx.getXml());
		
		title = getTitle(doc);
		val body = $(doc).find("legis-body");
		if (StringUtils.isNotBlank(title) && body.isNotEmpty()) {
			doc = body.get().get(0);
		}
		
		val slices = divideAndConquer(bill, model, doc);
		
		if (slices.size() == 1) return slices;
		
		int i = 0;
		for (val s : slices) {
			s.setText(title + "\n" + s.getText());
			s.setSliceIndex(i++);
		}

		return slices;
	}
	
	protected String getTitle(Node doc) {
		val title = $(doc).find("metadata title");
		
		if (title.isEmpty()) {
			return "";
		}
		
		return "Bill title: " + title.text() + "\nSection content:\n";
	}
	
	protected List<BillSlice> divideAndConquer(Bill bill, OpenAIModel model, Node node) {
		val text = $(node).text();
		
		if (!exceedsLength(text, model)) {
			return Arrays.asList(buildSlice(bill, $(node).xpath(), $(node).xpath(), text));
		} else if (node.getChildNodes().getLength() == 0 && exceedsLength(text, model)) {
			val slices = TextBillSlicer.sliceRaw(text);
			val list = new ArrayList<BillSlice>();
			slices.forEach(s -> list.add(buildSlice(bill, $(node).xpath(), $(node).xpath(), s)));
			return list;
		}
		
		val sections = $(node).children().map(c -> divideAndConquer(bill, model, c.element())).stream().reduce(new ArrayList<BillSlice>(), (a,b) -> CollectionUtils.mergeLists(a, b));
		
		val result = new ArrayList<BillSlice>();
		int i = 0;
		while (i < sections.size()) {
			StringBuilder cur = new StringBuilder();
			String start = sections.get(i).getStart();
			
			while (i < sections.size() && exceedsLength(cur + "\n" + sections.get(i).getText(), model)) {
				if (cur.length() > 0) { cur.append("\n"); }
				
				cur.append(sections.get(i).getText());
				i++;
			}
			
			result.add(buildSlice(bill, start, sections.get(i-1).getEnd(), cur.toString()));
		}
		
		return result;
	}
	
	private boolean exceedsLength(String text, OpenAIModel model) {
		return tokenEstimatorService.estimateTokenCount(BillPrompt.slicePrompt, title + "\n" + text) > model.getContextWindowTokens();
	}

	private BillSlice buildSlice(Bill bill, String xpathStart, String xpathEnd, String sectionText) {
		BillSlice slice = new BillSlice();
		slice.setBill(bill);
		slice.setText(sectionText);
		slice.setStart(xpathStart);
		slice.setEnd(xpathEnd);
		return slice;
	}
	
	@SneakyThrows
	public static Document toDoc(String xml) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setIgnoringElementContentWhitespace(true);
		factory.setValidating(false);
//		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
		factory.setFeature("http://xml.org/sax/features/namespaces", false);
		factory.setFeature("http://xml.org/sax/features/validation", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
		
		return doc;
	}

}
