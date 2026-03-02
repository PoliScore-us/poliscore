package us.poliscore.parsing;

import static org.joox.JOOX.$;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;

/**
 * Slices by TEXT NODE boundaries so XPath start/end can rehydrate the same content.
 *
 * Offset encoding (only when a single text node exceeds budget):
 *   start = "<xpath>/text()[k]@<startOffset>"
 *   end   = "<xpath>/text()[k]@<endOffsetExclusive>"
 *
 * If you never have giant single text nodes, you can drop the @offset bits.
 */
public class XMLTextNodeBillSlicer implements BillSlicer {

  protected Bill bill;

  @Override
  @SneakyThrows
  public List<BillSlice> slice(Bill bill, BillText btx, int maxSectionLength) {
    this.bill = bill;

    Node doc = (Node) toDoc(btx.getXml());

    String title = getTitlePrefix(doc);

    val body = $(doc).find("legis-body");
    if (StringUtils.isNotBlank(title) && body.isNotEmpty()) {
      doc = body.get().get(0);
    }

    int budget = maxSectionLength - title.length();
    if (budget < 1) budget = 1;

    List<TextAtom> atoms = new ArrayList<>();
    collectTextAtoms(doc, atoms);

    List<BillSlice> slices = atomsToSlices(atoms, budget);

    int i = 0;
    for (BillSlice s : slices) {
      s.setBill(bill);
      s.setSliceIndex(i++);
      s.setText(title + s.getText());
    }

    return slices;
  }

  protected String getTitlePrefix(Node doc) {
    val title = $(doc).find("metadata title");
    if (title.isEmpty()) return "";
    return "Bill title: " + title.text() + "\nSection content:\n";
  }

  /**
   * Collect non-whitespace TEXT_NODE atoms in document order.
   */
  protected void collectTextAtoms(Node node, List<TextAtom> out) {
    if (node == null) return;

    short t = node.getNodeType();

    if (t == Node.TEXT_NODE) {
      String txt = node.getNodeValue();
      if (txt != null && !txt.trim().isEmpty()) {
        out.add(new TextAtom(node, txt));
      }
      return;
    }

    // Skip metadata/title subtree if you already prefix title separately
    // (optional; keeps slice content from duplicating the title)
    if (t == Node.ELEMENT_NODE && "metadata".equals(node.getNodeName())) {
      return;
    }

    val kids = node.getChildNodes();
    for (int i = 0; i < kids.getLength(); i++) {
      collectTextAtoms(kids.item(i), out);
    }
  }

  protected List<BillSlice> atomsToSlices(List<TextAtom> atoms, int budget) {
    List<BillSlice> out = new ArrayList<>();
    if (atoms.isEmpty()) return out;

    int i = 0;
    while (i < atoms.size()) {
      StringBuilder cur = new StringBuilder();

      String startRef = null;
      String endRef = null;

      while (i < atoms.size()) {
        TextAtom a = atoms.get(i);

        // If single atom itself is too big, emit it in offset chunks.
        if (a.text.length() > budget) {
          // flush current slice first
          if (cur.length() > 0) {
            out.add(buildSlice(startRef, endRef, cur.toString()));
            cur.setLength(0);
            startRef = null;
            endRef = null;
          }

          String base = xpathOf(a.node); // this is the TEXT_NODE xpath
          int off = 0;
          while (off < a.text.length()) {
            int end = Math.min(a.text.length(), off + budget);
            String piece = a.text.substring(off, end);

            String sRef = base + "@" + off;
            String eRef = base + "@" + end;

            out.add(buildSlice(sRef, eRef, piece));
            off = end;
          }

          i++;
          break;
        }

        int extra = (cur.length() == 0) ? 0 : 1; // newline
        if (cur.length() + extra + a.text.length() > budget) break;

        if (cur.length() > 0) cur.append("\n");
        cur.append(a.text);

        if (startRef == null) startRef = xpathOf(a.node);
        endRef = xpathOf(a.node);

        i++;
      }

      if (cur.length() > 0) {
        out.add(buildSlice(startRef, endRef, cur.toString()));
      }
    }

    return out;
  }

  protected BillSlice buildSlice(String xpathStart, String xpathEnd, String sectionText) {
    BillSlice s = new BillSlice();
    s.setBill(this.bill);
    s.setStart(xpathStart);
    s.setEnd(xpathEnd);
    s.setText(sectionText);
    return s;
  }

  /**
   * XPath for ELEMENT_NODE and TEXT_NODE.
   * - Element: /a[1]/b[2]/c[1]
   * - Text:    /a[1]/b[2]/c[1]/text()[k]
   */
  protected static String xpathOf(Node node) {
    if (node == null) return "";

    if (node.getNodeType() == Node.TEXT_NODE) {
      Node parent = node.getParentNode();
      String parentXpath = xpathOf(parent);

      int k = 0;
      val kids = parent.getChildNodes();
      for (int i = 0; i < kids.getLength(); i++) {
        Node kid = kids.item(i);
        if (kid.getNodeType() == Node.TEXT_NODE) {
          k++;
          if (kid == node) break;
        }
      }

      return parentXpath + "/text()[" + k + "]";
    }

    if (node.getNodeType() != Node.ELEMENT_NODE) {
      // Walk up until we hit an element
      return xpathOf(node.getParentNode());
    }

    Node parent = node.getParentNode();
    if (parent == null || parent.getNodeType() == Node.DOCUMENT_NODE) {
      return "/" + node.getNodeName() + "[1]";
    }

    int idx = 0;
    val kids = parent.getChildNodes();
    for (int i = 0; i < kids.getLength(); i++) {
      Node kid = kids.item(i);
      if (kid.getNodeType() == Node.ELEMENT_NODE && kid.getNodeName().equals(node.getNodeName())) {
        idx++;
        if (kid == node) break;
      }
    }

    return xpathOf(parent) + "/" + node.getNodeName() + "[" + idx + "]";
  }

  protected static class TextAtom {
    final Node node;
    final String text;
    TextAtom(Node node, String text) { this.node = node; this.text = text; }
  }

  @SneakyThrows
  public static Document toDoc(String xml) {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setIgnoringElementContentWhitespace(true);
    factory.setValidating(false);

    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
    factory.setFeature("http://xml.org/sax/features/namespaces", false);
    factory.setFeature("http://xml.org/sax/features/validation", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}