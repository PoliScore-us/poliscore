package us.poliscore.referendum;

import java.io.*;
import java.nio.charset.Charset;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import us.poliscore.model.referendum.Referendum;

/** Exact-name matching to SOS-listed issue committees; never infers earmarking to a single measure. */
public final class ColoradoReferendumFinance {
    private ColoradoReferendumFinance() { }
    public static String url(int year) { return "https://tracer.sos.colorado.gov/PublicSite/Docs/BulkDataDownloads/" + year + "_ContributionData.csv.zip"; }
    public static void fetch(List<Referendum> measures, ReferendumDocuments documents) {
        try {
            for (var r : measures) r.getFunding().clear();
            for (int year = measures.getFirst().getElectionDate().getYear() - 1; year <= measures.getFirst().getElectionDate().getYear(); year++) {
                byte[] bytes = documents.get(url(year));
                try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    var entry = zip.getNextEntry();
                    if (entry == null || !entry.getName().endsWith(".csv")) throw new IllegalArgumentException("Expected TRACER contribution CSV");
                    parse(new InputStreamReader(zip, Charset.forName("windows-1252")), year, measures, ReferendumDocuments.hash(bytes));
                }
            }
            for (var r : measures) {
                // Later exports can contain amendments to earlier transactions.
                var byId = new LinkedHashMap<String, Referendum.Funding>();
                r.getFunding().forEach(f -> byId.put(f.getTransactionId(), f)); r.setFunding(new ArrayList<>(byId.values()));
                r.setFundingResearchStatus("PARTIAL_OFFICIAL_FILINGS");
                r.setFundingResearchNote("TRACER annual exports checked for the election year and preceding year, matched by exact normalized name to SOS-listed issue committees. "
                    + "Only itemized monetary contributions and returned contributions are included. Unitemized receipts, in-kind donations, LLC allocation rows, loans, transfers and independent spending are excluded. "
                    + "Amounts support the committee, which may campaign on multiple measures; they are not necessarily earmarked for this measure. No matches does not establish zero funding.");
            }
        } catch (Exception e) {
            for (var r : measures) {
                r.setFundingResearchStatus("SOURCE_UNAVAILABLE");
                r.setFundingResearchNote("Campaign-finance retrieval was incomplete: " + e.getClass().getSimpleName() + ". Any displayed records cover only successfully retrieved exports; no complete total is asserted.");
            }
        }
    }
    public static void parse(Reader reader, int year, List<Referendum> measures, String hash) throws Exception {
        var committeeMap = new HashMap<String, List<Referendum>>();
        for (var r : measures) for (var s : r.getStakeholders()) if ("REGISTERED_COMMITTEE".equals(s.getRole()))
            committeeMap.computeIfAbsent(normalize(s.getName()), ignored -> new ArrayList<>()).add(r);
        String referenceId = "finance-" + year;
        var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        var removed = new HashSet<String>();
        try (var parser = format.parse(reader)) {
            for (String header : List.of("CO_ID", "RecordID", "CommitteeName", "CommitteeType", "ContributionAmount", "ContributionDate", "LastName", "FirstName", "ContributionType", "Amended", "AmendedRecordID"))
                if (!parser.getHeaderMap().containsKey(header)) throw new IllegalArgumentException("TRACER layout changed: " + header);
            for (var row : parser) {
                var targets = committeeMap.get(normalize(row.get("CommitteeName")));
                if (targets == null || !"Issue Committee".equalsIgnoreCase(row.get("CommitteeType"))) continue;
                String amendedId = row.get("AmendedRecordID").strip(); if (!amendedId.isEmpty()) removed.add("co:" + amendedId);
                String type = row.get("ContributionType").strip();
                if (!Set.of("Monetary (Itemized)", "Returned Contributions").contains(type)) continue;
                if (row.isMapped("ReceiptType") && row.get("ReceiptType").toLowerCase(Locale.ROOT).contains("transfer")) continue;
                LocalDate date = LocalDate.parse(row.get("ContributionDate").substring(0, 10));
                BigDecimal amount = new BigDecimal(row.get("ContributionAmount"));
                for (var r : targets) {
                    if (date.isBefore(LocalDate.of(r.getElectionDate().getYear() - 1,1,1)) || date.isAfter(r.getElectionDate())) continue;
                    var f = new Referendum.Funding(); f.setTransactionId("co:" + row.get("RecordID"));
                    f.setContributor((row.get("FirstName").strip() + " " + row.get("LastName").strip()).strip());
                    f.setCommittee(row.get("CommitteeName")); f.setAmount(amount.abs()); f.setDate(date);
                    f.setTransactionType(type.equals("Returned Contributions") || amount.signum() < 0 ? "REFUND" : "CONTRIBUTION");
                    f.setReferenceId(referenceId); f.setReportingPeriod("TRACER export " + year + "; transaction date " + date);
                    f.setSuperseded("Y".equalsIgnoreCase(row.get("Amended"))); r.getFunding().add(f);
                }
            }
        }
        for (var r : measures) {
            r.getFunding().stream().filter(f -> removed.contains(f.getTransactionId())).forEach(f -> f.setSuperseded(true));
            var ref = new Referendum.Reference(); ref.setId(referenceId); ref.setUrl(url(year));
            ref.setAuthor("Colorado Secretary of State — TRACER"); ref.setTitle("Official contribution export " + year);
            ref.setType("GOVERNMENT"); ref.setStance("Neutral"); ref.setRetrievedAt(Instant.now()); ref.setContentHash(hash);
            ref.setLocation("RecordID in transaction records"); ref.setSummary("Itemized monetary contributions to registered issue committees; partial coverage.");
            ref.setEvidence("Official annual contribution export. Selected transaction records are embedded on the referendum. "
                + "Amended rows are excluded from totals. Amounts are committee receipts, not proven measure-specific spending. See funding research limitations.");
            r.getReferences().add(ref);
        }
    }
    static String normalize(String name) { return name.toUpperCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip(); }
}
