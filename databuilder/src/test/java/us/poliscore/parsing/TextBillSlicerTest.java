package us.poliscore.parsing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillSlice;
import us.poliscore.model.bill.BillText;

class TextBillSlicerTest {

    @Test
    void testSlicesAreValidAndWithinBounds() {
        // Arrange
        Bill bill = new Bill();
        StringBuilder sb = new StringBuilder();

        // Create a ~10,000 character input (safe size)
        for (int i = 0; i < 500; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }

        String longText = sb.toString();
        BillText billText = new BillText();
        billText.setText(longText);

        TextBillSlicer slicer = new TextBillSlicer();
        int maxSectionLength = 1000;

        // Act
        List<BillSlice> slices = slicer.slice(bill, billText, maxSectionLength);

        // Assert
        assertFalse(slices.isEmpty(), "Slices should not be empty");

        for (BillSlice slice : slices) {
            assertNotNull(slice.getText(), "Slice text must not be null");
            assertTrue(slice.getText().length() <= maxSectionLength,
                "Slice must not exceed maxSectionLength");

            int start = Integer.parseInt(slice.getStart());
            int end = Integer.parseInt(slice.getEnd());

            assertTrue(start >= 0 && start < end, "Invalid start/end range");
            assertTrue(end <= longText.length(), "End out of bounds");

            // Check slice range is moving forward
            assertTrue(start >= 0 && start < end, "Slice start must be before end");
        }

        // Ensure coverage: final slice ends at or beyond original length
        BillSlice finalSlice = slices.get(slices.size() - 1);
        int finalEnd = Integer.parseInt(finalSlice.getEnd());
        assertTrue(finalEnd >= longText.length() - 1,
            "Final slice should reach end of original text");
    }
}
