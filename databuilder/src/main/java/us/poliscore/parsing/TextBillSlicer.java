package us.poliscore.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;

public class TextBillSlicer implements BillSlicer {

    // Overlap in characters between slices for context continuity
    private static final int OVERLAP_SIZE = 200;

    // TODO : Slice at natural language boundaries (such as the end of a sentence, whatever)
    @Override
    public List<BillSlice> slice(Bill bill, BillText text, int maxSectionLength) {
        final List<BillSlice> slices = new ArrayList<>();
        final String fullText = text.getText();
        final int totalLength = fullText.length();

        int start = 0;
        int sliceNum = 0;

        while (start < totalLength) {
            int end = Math.min(start + maxSectionLength, totalLength);
            String sectionText = fullText.substring(start, end);

            BillSlice slice = new BillSlice();
            slice.setSliceIndex(sliceNum++);
            slice.setBill(bill);
            slice.setText(sectionText);
            slice.setStart(String.valueOf(start));
            slice.setEnd(String.valueOf(end));
            slices.add(slice);

            // Advance to next slice with overlap, but never go backwards or repeat
            int nextStart = end - OVERLAP_SIZE;
            if (nextStart <= start) {
                nextStart = end; // fallback if overlap is too large
            }
            start = nextStart;
        }

        return slices;
    }
    
    public static List<String> sliceRaw(String text) {
        int mid = text.length() / 2;

        int windowStart = Math.max(0, mid - 200);
        int windowEnd   = Math.min(text.length(), mid + 200);

        String window = text.substring(windowStart, windowEnd);

        int splitInWindow = lastIndexOfRegex(window, "\\s");
        if (splitInWindow == -1) {
            return Arrays.asList(text.substring(0, mid), text.substring(mid));
        }

        int split = windowStart + splitInWindow;
        return Arrays.asList(
            text.substring(0, split),
            text.substring(split + 1)
        );
    }
	
	public static int indexOfRegex(String str, String regex)
	{
		Pattern p = Pattern.compile(regex);  // insert your pattern here
		Matcher m = p.matcher(str);
		if (m.find()) {
		   return m.start();
		}
		return -1;
	}
	
	/**
	 * Version of lastIndexOf that uses regular expressions for searching.
	 * 
	 * @param str String in which to search for the pattern.
	 * @param toFind Pattern to locate.
	 * @return The index of the requested pattern, if found; NOT_FOUND (-1) otherwise.
	 */
	public static int lastIndexOfRegex(String str, String toFind)
	{
	    Pattern pattern = Pattern.compile(toFind);
	    Matcher matcher = pattern.matcher(str);
	    
	    // Default to the NOT_FOUND constant
	    int lastIndex = -1;
	    
	    // Search for the given pattern
	    while (matcher.find())
	    {
	        lastIndex = matcher.start();
	    }
	    
	    return lastIndex;
	}
}
