package us.poliscore.referendum;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.referendum.*;

class ColoradoReferendumFetcherTest {
    static byte[] fixture(String name) throws Exception {
        try (var in = ColoradoReferendumFetcherTest.class.getResourceAsStream("/referendums/" + name)) {
            if (in == null) throw new IllegalArgumentException(name); return in.readAllBytes();
        }
    }
    @Test void importsEntireCertifiedSlateFromOfficialFixturesWithoutNetwork() throws Exception {
        var fetcher = new ColoradoReferendumFetcher(); var manifest = fetcher.manifest(LocalDate.of(2026,11,3));
        var documents = new ReferendumDocuments() {
            @Override public byte[] get(String url) throws Exception {
                if (url.equals(manifest.path("inventoryUrl").asText())) return fixture("co-2026-certified.html");
                if (url.equals(manifest.path("blueBookUrl").asText())) return fixture("co-2026-bluebook.pdf");
                for (var m : manifest.path("measures")) if (url.equals(m.path("legalTextUrl").asText()))
                    return fixture(m.path("designation").asText().toLowerCase().replace(' ', '-') + ".pdf");
                if (url.contains("ContributionData.csv.zip")) {
                    var bytes = new java.io.ByteArrayOutputStream();
                    try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("contributions.csv"));
                        zip.write("CO_ID,RecordID,CommitteeName,CommitteeType,ContributionAmount,ContributionDate,LastName,FirstName,ContributionType,Amended,AmendedRecordID\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                    return bytes.toByteArray();
                }
                throw new AssertionError("Unexpected URL " + url);
            }
        };
        var values = fetcher.fetch(LocalDate.of(2026,11,3), documents);
        assertEquals(14, values.size());
        assertEquals(14, values.stream().map(Referendum::getId).distinct().count());
        for (var value : values) {
            assertEquals("REF/us/co/2026", value.getStorageBucket());
            assertTrue(value.getLegalText().length() > 100);
            assertFalse(value.getBlueBookFor().isBlank()); assertFalse(value.getBlueBookAgainst().isBlank());
            assertNotNull(value.getFiscalAssessment()); assertEquals(5, value.getReferences().size());
            assertEquals("PARTIAL_OFFICIAL_FILINGS", value.getFundingResearchStatus()); assertTrue(value.getFunding().isEmpty());
            assertFalse(value.getId().contains("local"));
        }
        var tax = values.stream().filter(r -> r.getDesignation().equals("Amendment 87")).findFirst().orElseThrow();
        assertTrue(tax.getPassageThreshold().contains("majority")); assertFalse(tax.getBallotQuestionTable().isEmpty());
        var first = values.getFirst();
        assertEquals("REF/us/co/2026-11-03/state/amendment-81", first.getId());
        assertTrue(first.getPassageThreshold().contains("55%"));
        assertEquals(2, first.getStakeholders().stream().filter(s -> s.getRole().equals("PROPONENT")).count());
        assertTrue(first.getStakeholders().stream().anyMatch(s -> s.getName().equals("Suzanne Taheri") && s.getRole().equals("PROPONENT")));
        assertTrue(first.getStakeholders().stream().anyMatch(s -> s.getName().equals("Brighter Colorado") && s.getStance().equals("For")));
        assertTrue(first.getStakeholders().stream().anyMatch(s -> s.getName().equals("Colorado Communities First") && s.getStance().equals("Against")));
        assertFalse(first.getBlueBookFor().contains("Arguments Against"));
        assertEquals("2026-11-03", PoliscoreUtil.getObjectMapper().valueToTree(first).path("electionDate").asText());
        assertTrue(PoliscoreUtil.getObjectMapper().valueToTree(first).path("fetchedAt").isTextual());
        var copy = PoliscoreUtil.getObjectMapper().readValue(PoliscoreUtil.getObjectMapper().writeValueAsString(first), Referendum.class);
        assertEquals(first.getId(), copy.getId()); assertEquals(first.getReferences().get(0).getContentHash(), copy.getReferences().get(0).getContentHash());
        assertTrue(values.stream().filter(r -> r.getDesignation().equals("Proposition NN")).findFirst().orElseThrow().getOriginatingLegislation().size() > 0);
    }
    @Test void refusesChangedInventoryRatherThanSilentlyDroppingMeasures() throws Exception {
        var fetcher = new ColoradoReferendumFetcher(); var manifest = fetcher.manifest(LocalDate.of(2026,11,3));
        String html = new String(fixture("co-2026-certified.html"), java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> fetcher.parseInventory(html.replace("Amendment 81</h3>", "Amendment 99</h3>"), manifest));
    }
    @Test void rejectsUnsupportedElectionAndIgnoresFundingInPolicyFingerprint() {
        assertFalse(new ColoradoReferendumFetcher().supports("us/ga", LocalDate.of(2026,11,3)));
        var r = new Referendum(); r.setLegalText("Current legal text"); r.setBallotQuestion("Approve?");
        String first = ColoradoReferendumFetcher.fingerprint(r); r.setFundingResearchNote("New filing");
        assertEquals(first, ColoradoReferendumFetcher.fingerprint(r));
        r.setLegalText("Changed legal text"); assertNotEquals(first, ColoradoReferendumFetcher.fingerprint(r));
    }
}
