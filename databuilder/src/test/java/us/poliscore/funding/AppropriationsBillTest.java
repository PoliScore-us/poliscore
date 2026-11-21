package us.poliscore.funding;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import us.poliscore.model.bill.FundingSnapshot.FundingRow;

class AppropriationsBillTest {
    private static final String FY25_CR = "hr1968enr.xml.gz";
    private static final String AZ_ADMINISTRATION = "DEPARTMENT OF ADMINISTRATION";
    private static final String CO_AGRICULTURE = "AGRICULTURE";
    private static final String GA_SENATE = "Georgia Senate";
    private static final String NM_JUDICIAL = "JUDICIAL";
    private static final String TX_GENERAL_GOVERNMENT = "General Government";

    @ParameterizedTest
    @MethodSource("newMexicoVersions")
    void readsNewMexicoJudicialAppropriationsFromEachFullVersion(String file, long judicialFunding, int rowCount) throws IOException {
        var rows = BillFunding.extract(document(file));
        assertEquals(rowCount, rows.size());
        var judicial = rows.stream().filter(row -> row.label().equals(NM_JUDICIAL)).findFirst().orElseThrow();
        assertEquals(2027, judicial.year());
        assertEquals(judicialFunding, judicial.amount());
    }

    static Stream<Arguments> newMexicoVersions() {
        return Stream.of(
                Arguments.of("hb2-2026-introduced.nm.txt.gz", 549_844_400L, 11),
                Arguments.of("hb2-2026-enrolled.nm.txt.gz", 548_225_700L, 9));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsTheFullTexasBillRecapWithOrWithoutExtraLayoutLines(boolean extraLayoutLines) throws IOException {
        String document = document("sb1-89r-enrolled.tx.txt.gz");
        if (extraLayoutLines) {
            document = document.replace("ARTICLE II - Health and Human Services",
                    "\n".repeat(22) + "ARTICLE II - Health and Human Services");
        }
        var rows = BillFunding.extract(document);
        assertEquals(20, rows.size());
        var generalGovernment = rows.stream().filter(row -> row.label().equals(TX_GENERAL_GOVERNMENT)).toList();
        assertEquals(List.of(2026, 2027), generalGovernment.stream().map(BillFunding.Line::year).toList());
        assertEquals(List.of(6_152_967_349L, 5_313_484_867L),
                generalGovernment.stream().map(BillFunding.Line::amount).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"congressionalBills", "stateBills"})
    void extractsAndComparesOfficialBillText(String bill, String fixture, String account, int fiscalYear,
            long amount, String priorFixture, Long priorAmount) throws IOException {
        var lines = BillFunding.extract(document(fixture));
        assertFalse(lines.isEmpty(), bill);
        var previous = priorFixture == null ? List.<FundingRow>of() : baseline(priorFixture);
        var result = BillFunding.compareFunding(lines, previous).stream()
                .filter(row -> row.label().endsWith(account) && row.year() == fiscalYear && row.current() == amount)
                .findFirst().orElseThrow(() -> new AssertionError("Missing FY " + fiscalYear + " " + account + " at " + amount));
        assertEquals(priorAmount, result.prior());
        assertEquals(priorAmount == null ? null : priorFixture, result.priorBill());
        if (priorAmount != null) {
            assertEquals(amount - priorAmount, result.current() - result.prior());
        }
    }

    static Stream<Arguments> congressionalBills() {
        return Stream.of(
            congress("H.R. 4016", "hr4016rh", "Operation and maintenance, navy", 2026, 71_739_379_000L, 73_657_268_000L),
            congress("H.R. 4213", "hr4213rh", "DISASTER RELIEF FUND", 2026, 26_474_000_000L, 22_510_000_000L),
            congress("H.R. 4553", "hr4553rh", "Weapons Activities", 2026, 20_661_993_000L, 19_293_000_000L),
            congress("H.R. 5166", "hr5166rh", "COMMITTEE ON FOREIGN INVESTMENT IN THE UNITED STATES FUND", 2026, 21_000_000L, null),
            congress("H.R. 5371", "hr5371enr", "MEDICAL SERVICES", 2027, 59_858_000_000L, 75_039_000_000L),
            congress("H.R. 6938", "hr6938enr", "OPERATION OF THE NATIONAL PARK SYSTEM", 2026, 2_877_195_000L, 2_894_424_000L),
            congress("H.R. 7148", "hr7148enr", "Operation and maintenance, navy", 2026, 74_723_177_000L, 73_657_268_000L),
            congress("S. 2256", "s2256rs", "COMMODITY ASSISTANCE PROGRAM", 2026, 516_070_000L, 516_070_000L),
            congress("S. 2257", "s2257rs", "Salaries, Officers and Employees", 2026, 314_143_000L, 320_227_000L),
            congress("S. 2354", "s2354rs", "FEDERAL PRISONER DETENTION", 2026, 2_236_000_000L, 2_236_000_000L),
            congress("S. 2431", "s2431rs", "OPERATION OF THE NATIONAL PARK SYSTEM", 2026, 2_869_424_000L, 2_894_424_000L),
            congress("S. 2465", "s2465rs", "FACILITIES AND EQUIPMENT", 2026, 4_000_000_000L, 3_176_250_000L),
            congress("S. 2572", "s2572rs", "Operation and maintenance, navy", 2026, 76_440_024_000L, 73_657_268_000L),
            congress("S. 2587", "s2587rs", "NIH INNOVATION ACCOUNT, CURES ACT", 2026, 226_000_000L, 127_000_000L)
        );
    }

    static Stream<Arguments> stateBills() {
        return Stream.of(
            Arguments.of("AZ SB 1735 (2025)", "sb1735-law.az.html", AZ_ADMINISTRATION, 2026, 301_326_900L, "hb2897-law.az.html", 237_989_400L),
            Arguments.of("AZ HB 2947 (2025)", "hb2947p.az.html", AZ_ADMINISTRATION, 2026, 247_650_300L, "hb2897-law.az.html", 237_989_400L),
            Arguments.of("AZ HB 4138 (2026)", "hb4138p.az.html", AZ_ADMINISTRATION, 2027, 297_824_500L, "sb1735-law.az.html", 301_326_900L),
            Arguments.of("AZ SB 1847 (2026)", "sb1847-law.az.html", AZ_ADMINISTRATION, 2027, 299_933_300L, "sb1735-law.az.html", 301_326_900L),
            Arguments.of("CO SB 23-214", "SB23-214.co.txt", CO_AGRICULTURE, 2023, 66_625_053L, null, null),
            Arguments.of("CO HB 24-1430", "HB24-1430.co.txt", CO_AGRICULTURE, 2024, 76_675_630L, "SB23-214.co.txt", 66_625_053L),
            Arguments.of("CO SB 25-206", "SB25-206.co.txt", CO_AGRICULTURE, 2025, 77_793_462L, "HB24-1430.co.txt", 76_675_630L),
            Arguments.of("CO HB 26-1410", "HB26-1410.co.txt", CO_AGRICULTURE, 2026, 78_305_667L, "SB25-206.co.txt", 77_793_462L),
            Arguments.of("GA HB 916 (FY2025)", "hb916.ga.txt", GA_SENATE, 2025, 16_234_408L, null, null),
            Arguments.of("GA HB 68 (FY2026)", "hb68.ga.txt", GA_SENATE, 2026, 17_714_273L, "hb916.ga.txt", 16_234_408L),
            Arguments.of("GA HB 973 (amended FY2026)", "hb973.ga.txt", GA_SENATE, 2026, 18_090_468L, "hb916.ga.txt", 16_234_408L),
            Arguments.of("GA HB 974 (FY2027)", "hb974.ga.txt", GA_SENATE, 2027, 20_900_914L, "hb973.ga.txt", 18_090_468L),
            Arguments.of("NM HB 2 (2023)", "23.nm.txt", NM_JUDICIAL, 2024, 441_270_900L, null, null),
            Arguments.of("NM HB 2 (2024)", "24.nm.txt", NM_JUDICIAL, 2025, 483_820_500L, "23.nm.txt", 441_270_900L),
            Arguments.of("NM HB 2 (2025)", "25.nm.txt", NM_JUDICIAL, 2026, 509_332_900L, "24.nm.txt", 483_820_500L),
            Arguments.of("NM HB 2 (2026)", "26.nm.txt", NM_JUDICIAL, 2027, 548_225_700L, "25.nm.txt", 509_332_900L),
            Arguments.of("TX HB 1 (86R)", "86R.tx.txt", TX_GENERAL_GOVERNMENT, 2020, 4_336_400_948L, null, null),
            Arguments.of("TX SB 1 (87R)", "87R.tx.txt", TX_GENERAL_GOVERNMENT, 2022, 3_736_569_404L, "86R.tx.txt", 3_093_597_673L),
            Arguments.of("TX HB 1 (88R)", "88R.tx.txt", TX_GENERAL_GOVERNMENT, 2024, 6_672_359_474L, "87R.tx.txt", 3_183_012_921L),
            Arguments.of("TX SB 1 (89R)", "89R.tx.txt", TX_GENERAL_GOVERNMENT, 2026, 6_152_967_349L, "88R.tx.txt", 4_188_328_028L)
        );
    }

    private static Arguments congress(String bill, String fixture, String account, int fiscalYear, long amount, Long prior) {
        return Arguments.of("119th Congress " + bill, fixture + ".xml.gz", account, fiscalYear, amount, FY25_CR, prior);
    }

    private static List<FundingRow> baseline(String fixture) throws IOException {
        var lines = BillFunding.extract(document(fixture));
        assertFalse(lines.isEmpty(), fixture);
        return lines.stream().map(row ->
                new FundingRow(row.label(), row.amount(), null, fixture, row.year(), row.evidence())).toList();
    }

    static String document(String fixture) throws IOException {
        try (InputStream source = Objects.requireNonNull(AppropriationsBillTest.class.getResourceAsStream("/funding/" + fixture), fixture);
             InputStream text = fixture.endsWith(".gz") ? new GZIPInputStream(source) : source) {
            return new String(text.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
