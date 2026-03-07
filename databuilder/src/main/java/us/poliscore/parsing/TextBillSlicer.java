package us.poliscore.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import us.poliscore.ai.OpenAIModel;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillPrompt;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;
import us.poliscore.service.TokenEstimatorService;

@ApplicationScoped
public class TextBillSlicer implements BillSlicer {

    private static final int OVERLAP_SIZE = 200;

    // Used when we need to shrink an oversized candidate quickly
    private static final double SHRINK_FACTOR = 0.75;

    // Used to probe upward once we have a fitting candidate
    private static final int GROW_STEP = 1000;

    // Preferred snapping window near the end of a slice
    private static final int BOUNDARY_WINDOW = 200;
    
    @Inject TokenEstimatorService tokenEstimatorService;
    private int promptTokens;

    @Override
    public List<BillSlice> slice(Bill bill, BillText text, OpenAIModel model) {
        this.promptTokens = tokenEstimatorService.estimateTokenCount(BillPrompt.slicePrompt + "\n");

        final List<BillSlice> slices = new ArrayList<>();
        final String fullText = text.getText();
        final int totalLength = fullText.length();

        int start = 0;
        int sliceNum = 0;

        while (start < totalLength) {
            int end = findEfficientEnd(model, fullText, start);

            if (end <= start) {
                end = Math.min(start + 1, totalLength);
            }

            String sectionText = fullText.substring(start, end);

            BillSlice slice = new BillSlice();
            slice.setSliceIndex(sliceNum++);
            slice.setBill(bill);
            slice.setText(sectionText);
            slice.setStart(String.valueOf(start));
            slice.setEnd(String.valueOf(end));
            slices.add(slice);

            int nextStart = end - OVERLAP_SIZE;
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }

        return slices;
    }

    /**
     * Faster than full binary search over the whole remaining document.
     *
     * Strategy:
     * 1. Start with a rough char-length guess.
     * 2. Shrink fast until it fits.
     * 3. Grow in moderate steps to reclaim unused space.
     * 4. Binary search only the final small gap.
     */
    private int findEfficientEnd(OpenAIModel model, String fullText, int start) {
        final int remaining = fullText.length() - start;
        if (remaining <= 0) {
            return start;
        }

        int end = Math.min(start + initialCharGuess(model, fullText, start), fullText.length());

        // If guess is too large, shrink quickly.
        while (end > start && exceedsLength(model, fullText, start, end)) {
            int currentSize = end - start;
            int nextSize = Math.max(1, (int) (currentSize * SHRINK_FACTOR));
            int nextEnd = start + nextSize;

            if (nextEnd >= end) {
                nextEnd = end - 1;
            }

            end = nextEnd;
        }

        if (end <= start) {
            return Math.min(start + 1, fullText.length());
        }

        // Grow upward in chunks while there's room.
        int low = end;
        int high = end;

        while (high < fullText.length()) {
            int candidate = Math.min(high + GROW_STEP, fullText.length());
            if (exceedsLength(model, fullText, start, candidate)) {
                break;
            }
            low = candidate;
            high = candidate;
        }

        // Binary search only the small remaining interval.
        int upperBound = Math.min(high + GROW_STEP, fullText.length());
        if (upperBound < low) {
            upperBound = low;
        }

        int best = low;
        int left = low + 1;
        int right = upperBound;

        while (left <= right) {
            int mid = left + (right - left) / 2;
            if (exceedsLength(model, fullText, start, mid)) {
                right = mid - 1;
            } else {
                best = mid;
                left = mid + 1;
            }
        }

        return snapToBoundary(fullText, start, best);
    }

    /**
     * Rough estimate to avoid starting from something absurdly large or tiny.
     * This does not need to be perfect.
     */
    private int initialCharGuess(OpenAIModel model, String fullText, int start) {
        int remainingChars = fullText.length() - start;
        int availableTokens = model.getContextWindowTokens() - promptTokens;

        if (availableTokens <= 0) {
            return 1;
        }

        // Conservative chars/token heuristic.
        // For legislative text, 3-5 chars/token is often reasonable.
        int estimated = availableTokens * 4;

        return Math.max(1, Math.min(estimated, remainingChars));
    }

    private int snapToBoundary(String fullText, int start, int end) {
        if (end <= start || end >= fullText.length()) {
            return end;
        }

        int windowStart = Math.max(start, end - BOUNDARY_WINDOW);
        String window = fullText.substring(windowStart, end);

        int splitInWindow = lastIndexOfRegex(window, "\\s");
        if (splitInWindow == -1) {
            return end;
        }

        int snapped = windowStart + splitInWindow;
        return snapped > start ? snapped : end;
    }

    private boolean exceedsLength(OpenAIModel model, String fullText, int start, int end) {
        String text = fullText.substring(start, end);
        return exceedsLength(model, text);
    }

    private boolean exceedsLength(OpenAIModel model, String text) {
        return promptTokens + tokenEstimatorService.estimateTokenCount(text) > model.getContextWindowTokens();
    }

    public static List<String> sliceRaw(String text) {
        int mid = text.length() / 2;

        int windowStart = Math.max(0, mid - 200);
        int windowEnd = Math.min(text.length(), mid + 200);

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

    public static int indexOfRegex(String str, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(str);
        if (m.find()) {
            return m.start();
        }
        return -1;
    }

    public static int lastIndexOfRegex(String str, String toFind) {
        Pattern pattern = Pattern.compile(toFind);
        Matcher matcher = pattern.matcher(str);

        int lastIndex = -1;
        while (matcher.find()) {
            lastIndex = matcher.start();
        }

        return lastIndex;
    }
}