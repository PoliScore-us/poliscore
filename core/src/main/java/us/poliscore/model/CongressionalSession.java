package us.poliscore.model;

import java.time.LocalDate;
import lombok.Value;

/** Logical dataset years stay calendar-aligned; constitutional term dates are separate. */
@Value
public class CongressionalSession {
    public static final CongressionalSession S117 = of(117);
    public static final CongressionalSession S118 = of(118);
    public static final CongressionalSession S119 = of(119);
    public static final CongressionalSession S120 = of(120);

    int number;
    LocalDate startDate;
    LocalDate endDate;

    private CongressionalSession(int number) {
        if (number < 1 || number > 4105) throw new IllegalArgumentException("Invalid Congress: " + number);
        this.number = number;
        int year = 1789 + 2 * (number - 1);
        this.startDate = LocalDate.of(year, 1, 1);
        this.endDate = LocalDate.of(year + 1, 12, 31);
    }

    public static CongressionalSession of(Integer number) {
        if (number == null) throw new IllegalArgumentException("Congress is required");
        return new CongressionalSession(number);
    }

    public static CongressionalSession fromYear(Integer year) {
        if (year == null || year < 1789) throw new IllegalArgumentException("Invalid congressional year: " + year);
        return of(Math.floorDiv(year - 1789, 2) + 1);
    }

    public int getStartYear() { return startDate.getYear(); }
    public int getEndYear() { return endDate.getYear(); }
    public LocalDate getTermStartDate() { return LocalDate.of(getStartYear(), 1, 3); }
    public LocalDate getTermEndDate() { return LocalDate.of(getEndYear() + 1, 1, 3); }
    public boolean isOver() { return !LocalDate.now().isBefore(getTermEndDate()); }
}
