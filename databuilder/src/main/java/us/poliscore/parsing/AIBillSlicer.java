package us.poliscore.parsing;

import static org.joox.JOOX.$;

import java.io.StringReader;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.joox.Match;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.bill.InterpretationRequest;
import us.poliscore.model.AIAggregateInterpretationMetadata;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillInterpretation;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.service.OpenAIService;
import us.poliscore.service.storage.LocalCachedS3Service;

/**
 * This slicer gets AI to identify sections semantically throughout the bill and then return sections to split across. It's an interesting concept, and there was ultimately some
 * success, however this concept will have to be revisited later if there's interest since it's too complicated for now. With 119/hr1 ai split the bill into related sections
 * (i.e. defense, agriculture, finance, etc) which was interesting, however it was found that one section in the bill (agriculture) itself was simply gigantic.
 * 
 * This slicer was abandoned (for now) because it split the bill into ten sections (but the XML slicer only needs 2) and because the ultimate utility in this approach comes from
 * exploring the bill in depth for the end user. That sort of exploration might also be nice to have related bills? I don't know. I kinda just ran out of resources and abandoned this.
 * 
 * 
 * TODO : Use the new OpenAIModel.estimateTokenCount method
 * TODO : Audit to make sure its using the new SLICER_MODEL correctly (as opposed to the new 'model' input param)
 */
@ApplicationScoped
public class AIBillSlicer implements BillSlicer {
	public static final String prompt = """
You are an employee of a non-partisan congressional auditor. You are to be given a very large bill text. Your job is to split this text up into a list of non-overlapping "sub-bills" for independent evaluation. Each sub-bill need not necessarily correspond to an individual "section" (or other 'node' related concept) in the XML; simply try to split it up into a few related "sub-bills". Each "sub-bill" must not be longer than 800000 characters, or 1/4 of the total bill length. Your list must be comprehensive; it must cover the bill text in its entirety. Do not include boiler-plate or summary sections, we are only interested in sections which have policy impact.
Your output should be ONLY a machine-readable JSON array (with no wrapper-text or dangling explanation), with JSON objects, where each JSON object has the following properties:
1. name - a name for the section
2. description - a short description of the section
3. start - an XML node reference to the start of the section of the format: tag[@id='X']
4. end - an XML node reference to the end of the section of the format: tag[@id='X']
			""";
	
	public static final OpenAIModel SLICER_MODEL = OpenAIModel.GPT41;
	
	@Inject private OpenAIService ai;
	
	@Inject private LocalCachedS3Service s3;
	
	@Inject ObjectMapper mapper;
	
	private OpenAIModel model;
	
	record AiSliceResponse(String name, String description, String start, String end) { }

	@Override
	@SneakyThrows
	public List<BillSlice> slice(Bill bill, BillText btx, OpenAIModel model) {
		this.model = model;
		
		// If it's been sliced before, we have to return the slice as-is.
		var interp = s3.get(BillInterpretation.generateId(bill.getId(), null), BillInterpretation.class).orElse(null);
		
		if (interp != null && interp.getMetadata() != null && interp.getMetadata() instanceof AIAggregateInterpretationMetadata) {
			var sliceMeta = ((AIAggregateInterpretationMetadata)interp.getMetadata());
			
			var slices = sliceMeta.getSlices();
			
			for (int i = 0; i < slices.size(); i++) {
			    var s = slices.get(i);
			    
			    String sliceXml = extractXmlRangeWithJoox(
	                    btx.getXml(),
	                    s.getStart(),
	                    s.getEnd()
	            );
			    
			    s.setText(sliceXml);
			    s.setBill(bill);
			}
			
			return slices;
		}
		
		var slices = newSlice(bill, btx);
		
		// Save the slices in a persistent way so that we can reconstruct them later
		interp = new BillInterpretation();
		interp.setId(BillInterpretation.generateId(bill.getId(), null));
		interp.setBill(bill);
		interp.setMetadata(OpenAIService.metadata(slices));
		s3.put(interp);
		
		return slices;
	}
	
	@SneakyThrows
	protected List<BillSlice> newSlice(Bill bill, BillText btx) {
		
		
		RetryPolicy<List<BillSlice>> retryPolicy = RetryPolicy.<List<BillSlice>>builder()
			    .withBackoff(2, 9, ChronoUnit.SECONDS)
			    .withJitter(0.25)
			    .withMaxRetries(3)
			    .onRetry(e -> Log.warn("Retrying due to invalid json return"))
			    .onFailure(e -> Log.error("Retries exhausted", e.getException()))
			    .build();
		
		return Failsafe.with(retryPolicy).get(() -> {
			return newSliceInRetry(bill, btx);
		});
	}
	
	@SneakyThrows
	protected List<BillSlice> newSliceInRetry(Bill bill, BillText btx) {
		try {
			var request = new InterpretationRequest(new CustomData("aislicer/" + bill.getId()), prompt, btx.getXml(), SLICER_MODEL, null);
			
			var response = ai.chat(request);
			
			List<AiSliceResponse> aiSlices = mapper.readValue(response.content(), new TypeReference<List<AiSliceResponse>>() {} );
			
			List<BillSlice> slices = new ArrayList<>();
			
			var doc = parseXmlNoDtd(btx.getXml());
	
			for (int i = 0; i < aiSlices.size(); i++) {
			    var s = aiSlices.get(i);
			    
			    Resolved start = resolveNodeWithCanonical(doc, s.start());
			    Resolved end   = resolveNodeWithCanonical(doc, s.end());

			    if (start.node == null || end.node == null) {
			        throw new IllegalArgumentException("Could not resolve slice i=" + i + " start=" + s.start() + " end=" + s.end());
			    }
			    if (!isEndReachableFromStart(start.node, end.node)) {
			        throw new IllegalArgumentException("Bad order slice i=" + i + " start=" + s.start() + " end=" + s.end());
			    }

			    // build XML from nodes (you can factor this into a helper that takes start/end nodes)
			    String sliceXml = extractXmlRangeWithJoox(btx.getXml(), start.canonicalXpath, end.canonicalXpath);
			    
			    if (sliceXml.length() > OpenAIModel.GPT51.getContextWindowStringLength())
			    	throw new RuntimeException("Slice size too large! " + sliceXml.length() + " > " + OpenAIModel.GPT51.getContextWindowStringLength());
			    
			    slices.add(new BillSlice(
			            bill,
			            sliceXml,
			            i,
			            s.name(),
			            s.description(),
			            start.canonicalXpath,
			            end.canonicalXpath
			    ));
			}
	
			return slices;
		} catch (Throwable t) {
			t.printStackTrace();
			throw t;
		}
	}
	
	protected String extractXmlRangeWithJoox(String xml, String startXpath, String endXpath) {
	    try {
	        Document doc = parseXmlNoDtd(xml);

	        Resolved start = resolveNodeWithCanonical(doc, startXpath);
	        Resolved end   = resolveNodeWithCanonical(doc, endXpath);

	        if (start.node == null || end.node == null) {
	            throw new IllegalArgumentException(
	                "Could not resolve XPath(s) even with ID fallback. start=" + startXpath + " end=" + endXpath
	            );
	        }

	        if (!isEndReachableFromStart(start.node, end.node)) {
	            throw new IllegalArgumentException(
	                "End node is not reachable from start in document order. start=" + startXpath + " end=" + endXpath
	            );
	        }

	        List<Node> collected = new ArrayList<>();
	        Node current = start.node;

	        while (current != null) {
	            collected.add(current);
	            if (current.isSameNode(end.node)) break;
	            current = nextNodeInDocumentOrder(current);
	        }

	        StringBuilder out = new StringBuilder();
	        for (Node n : collected) out.append($(n).toString()).append("\n");
	        
	        if (out.toString().trim().length() < OpenAIModel.GPT51.getContextWindowStringLength())
	        	return out.toString().trim();
	        else {
	        	out = new StringBuilder();
		        for (Node n : collected) out.append($(n).text()).append("\n");
		        return out.toString().trim();
	        }

	    } catch (Exception e) {
	        throw new RuntimeException("Failed to extract XML range via jOOX", e);
	    }
	}

	private Document parseXmlNoDtd(String xml) throws Exception {
	    var dbf = DocumentBuilderFactory.newInstance();
	    dbf.setNamespaceAware(true);

	    // Disable DTD loading
	    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
	    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
	    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
	    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);

	    // Prevent XXE
	    dbf.setXIncludeAware(false);
	    dbf.setExpandEntityReferences(false);

	    return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
	}

	record Resolved(Node node, String canonicalXpath) {}

	private Resolved resolveNodeWithCanonical(Document doc, String xpathHint) {
	    // 1) Try verbatim XPath
	    Match m = $(doc).xpath(xpathHint);
	    if (!m.isEmpty()) {
	        return new Resolved(m.get(0), xpathHint);
	    }

	    // 2) Salvage by LAST @id in the XPath
	    String id = extractLastIdFromXpath(xpathHint);
	    if (id == null) return new Resolved(null, null);

	    String tag = extractLastTagFromXpath(xpathHint);

	    if (tag != null) {
	        String canonical = "//" + tag + "[@id='" + escapeForXpathLiteral(id) + "']";
	        Match byTagAndId = $(doc).xpath(canonical);
	        if (!byTagAndId.isEmpty()) return new Resolved(byTagAndId.get(0), canonical);
	    }

	    String canonical = "//*[@id='" + escapeForXpathLiteral(id) + "']";
	    Match byAnyId = $(doc).xpath(canonical);
	    if (!byAnyId.isEmpty()) return new Resolved(byAnyId.get(0), canonical);

	    return new Resolved(null, null);
	}

	private String extractLastIdFromXpath(String xpath) {
	    Matcher matcher = ID_PATTERN.matcher(xpath);
	    String last = null;
	    while (matcher.find()) last = matcher.group(1);
	    return last;
	}

	private static final Pattern ID_PATTERN = Pattern.compile("@id\\s*=\\s*['\"]([^'\"]+)['\"]");
	private String extractIdFromXpath(String xpath) {
	    Matcher matcher = ID_PATTERN.matcher(xpath);
	    if (matcher.find()) return matcher.group(1);
	    return null;
	}

	// Extracts the last element step name from an xpath like /a/b/c[@id='x']/d -> "d"
	private String extractLastTagFromXpath(String xpath) {
	    // Remove trailing predicates to find the last step
	    // Example: /bill/legis-body/title[@id='X']/section[@id='Y'] -> "section"
	    String[] parts = xpath.split("/");
	    for (int i = parts.length - 1; i >= 0; i--) {
	        String p = parts[i].trim();
	        if (p.isEmpty()) continue;
	        // Drop predicates: "section[@id='Y']" -> "section"
	        int bracket = p.indexOf('[');
	        String tag = (bracket >= 0) ? p.substring(0, bracket) : p;
	        tag = tag.trim();

	        // Ignore axes, functions, wildcards
	        if (tag.isEmpty() || tag.equals("*") || tag.contains("(") || tag.contains("::")) continue;

	        // If namespaces appear (e.g., ns:section), keep as-is; caller may still fail without ns mapping
	        return tag;
	    }
	    return null;
	}

	private String escapeForXpathLiteral(String value) {
	    // Minimal: assume IDs won't contain quotes. If they might, we can build concat().
	    return value;
	}

	private boolean isEndReachableFromStart(Node start, Node end) {
	    Node cur = start;
	    while (cur != null) {
	        if (cur.isSameNode(end)) return true;
	        cur = nextNodeInDocumentOrder(cur);
	    }
	    return false;
	}

	protected Node nextNodeInDocumentOrder(Node node) {
	    if (node.getFirstChild() != null) return node.getFirstChild();

	    Node cur = node;
	    while (cur != null) {
	        if (cur.getNextSibling() != null) return cur.getNextSibling();
	        cur = cur.getParentNode();
	    }
	    return null;
	}
	
}
