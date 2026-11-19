package us.poliscore.funding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.bill.*;
import us.poliscore.model.bill.FundingSnapshot.FundingRow;
import us.poliscore.service.storage.S3PersistenceService;
import us.poliscore.service.storage.repository.BillRepository;

@ApplicationScoped
public class BillFundingService {
    private static final Duration PAGE_BUDGET = Duration.ofSeconds(8);
    private static final Duration BUILD_BUDGET = Duration.ofMinutes(2);
    private final ExecutorService workers = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("funding-read-", 0).factory());
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Cache<String, Boolean> failures = Caffeine.newBuilder().maximumSize(4096)
            .expireAfterWrite(Duration.ofMinutes(1)).build();

    @Inject S3PersistenceService archive;
    @Inject BillRepository bills;

    @PreDestroy void stop() { workers.shutdownNow(); }

    record Budget(long end) {
        static Budget of(Duration duration) { return new Budget(System.nanoTime() + duration.toNanos()); }
        Duration left() {
            long nanos = end - System.nanoTime();
            if (nanos <= 0 || Thread.currentThread().isInterrupted()) throw new CancellationException("Funding deadline");
            return Duration.ofNanos(nanos);
        }
        int millis() { return Math.toIntExact(Math.max(1, left().toMillis())); }
    }

    static String key(BillText meta) {
        return "funding/" + BillText.generateId(meta.getBillId(), meta.getVersion()) + "/"
                + FundingSnapshot.EXTRACTOR_VERSION + "/" + FundingSnapshot.revision(meta) + ".json";
    }

    static boolean current(BillText meta) {
        return meta.getFunding() != null && meta.getFunding().appliesTo(meta);
    }

    /** Called before a new interpretation is archived or its bill is published. */
    public void prepare(Bill bill, BillText source, BillInterpretation interp) {
        try {
            if (interp.getFunding() != null && interp.getFunding().appliesTo(source)) {
                source.setFunding(interp.getFunding());
                return;
            }
            var snapshot = resolve(bill, source, Budget.of(BUILD_BUDGET));
            source.setFunding(snapshot);
            interp.setFunding(snapshot);
        } catch (Exception e) {
            interp.setFunding(null);
            Log.warnf("Funding unavailable while publishing %s (%s): %s", bill.getId(), source.getVersion(), e);
        }
    }

    public void forPage(Bill bill, String requestedVersion) {
        var budget = Budget.of(PAGE_BUDGET);
        var texts = bill.getTexts();
        var interpretation = bill.getInterpretation();
        if (StringUtils.isNotBlank(requestedVersion)) {
            interpretation = bill.getInterpretations().stream()
                    .filter(i -> requestedVersion.equalsIgnoreCase(i.getSourceBillTextVersion())).findFirst().orElse(null);
            if (interpretation == null) return;
        }
        String version = interpretation == null ? null : interpretation.getSourceBillTextVersion();
        // A legacy bill may have only its selected interpretation and an unversioned archived text.
        BillText meta = texts.stream().filter(t -> version != null && version.equalsIgnoreCase(t.getVersion()))
                .findFirst().orElse(null);
        if (meta == null && version == null && !texts.isEmpty()) meta = texts.last();
        boolean recoverMetadata = meta == null;
        if (recoverMetadata) meta = BillText.factory(bill.getId(), null, null, null, version, null);
        if (current(meta)) {
            if (interpretation != null) interpretation.setFunding(meta.getFunding());
            return;
        }
        if (interpretation != null && interpretation.getFunding() != null && interpretation.getFunding().appliesTo(meta)) {
            meta.setFunding(interpretation.getFunding());
            texts.add(meta);
            bill.setTexts(texts);
            return;
        }
        // Workers never mutate the bill being served, including after a timeout.
        meta.setFunding(null);
        if (interpretation != null) interpretation.setFunding(null);
        bill.setTexts(texts);
        BillText selected = meta;
        BillText completed = guarded(key(selected), budget, () -> {
            BillText source = recoverMetadata ? readText(bill.getId(), selected, budget).orElseThrow() : selected;
            var snapshot = resolve(bill, source, budget);
            var published = source.metadataOnly();
            published.setFunding(snapshot);
            boolean saved = recoverMetadata ? bills.restoreFundingText(bill, published, budget.millis())
                    : bills.saveFunding(bill.getId(), selected, snapshot, budget.millis());
            if (!saved) throw new IllegalStateException("Bill text changed during funding extraction");
            return published;
        });
        if (completed != null) {
            texts.remove(selected);
            texts.add(completed);
            bill.setTexts(texts);
            if (interpretation != null) interpretation.setFunding(completed.getFunding());
        }
    }

    <T> T guarded(String key, Budget budget, Callable<T> work) {
        if (failures.getIfPresent(key) != null || !active.add(key)) return null;
        var task = new FutureTask<T>(() -> {
            try {
                budget.left();
                return work.call();
            } catch (Exception e) {
                failures.put(key, true);
                throw e;
            }
        }) {
            @Override public void run() {
                try { super.run(); }
                finally { active.remove(key); }
            }
        };
        try {
            workers.execute(task);
        } catch (RejectedExecutionException busy) {
            active.remove(key);
            return null;
        }
        try {
            return task.get(budget.left().toNanos(), TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            failures.put(key, true);
            task.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.warnf("Serving bill without funding (%s): %s", key, e);
            return null;
        }
    }

    private FundingSnapshot resolve(Bill bill, BillText meta, Budget budget) throws Exception {
        var stored = archive.readJsonWithEtag(key(meta), FundingSnapshot.class, budget.left());
        var cached = stored.map(S3PersistenceService.ArchiveJson::value);
        if (cached.isPresent() && cached.get().appliesTo(meta)) {
            return cached.get();
        }
        BillText source = StringUtils.isNotBlank(meta.getDocument()) ? meta : readText(bill.getId(), meta, budget).orElseThrow();
        var lines = BillFunding.extract(source.getDocument());
        var priorYears = lines.stream().filter(line -> line.year() > 0).map(line -> line.year() - 1).collect(Collectors.toSet());
        List<FundingRow> previous = new ArrayList<>();
        var cited = new LinkedHashSet<String>();
        if (bill.getIntroducedDate() != null && !priorYears.isEmpty()) {
            String citations = BillFunding.citationText(source.getDocument());
            var candidatesForYear = bills.fundingCandidates(bill, budget.millis());
            for (var candidate : candidatesForYear) {
                var candidates = candidate.getTexts();
                var finalText = candidates.descendingSet().stream().filter(t -> isFinal(t.getVersion())).findFirst();
                if (finalText.isEmpty() && !candidates.isEmpty()) continue;
                var archived = readText(candidate.getId(), finalText.orElse(null), budget);
                if (archived.isEmpty()) continue;
                var prior = archived.get();
                if (!isFinal(prior.getVersion())) continue;
                if (BillFunding.citesPriorAppropriation(citations, prior.getDocument(), priorYears)) cited.add(candidate.getId());
                for (var line : BillFunding.extract(prior.getDocument())) {
                    previous.add(new FundingRow(line.label(), line.amount(), null, candidate.getId(), line.year(), line.evidence()));
                }
            }
        }
        var snapshot = new FundingSnapshot(FundingSnapshot.EXTRACTOR_VERSION, meta.getVersion(), FundingSnapshot.revision(meta),
                meta.getLegiscanId(), meta.getLastUpdate(), System.currentTimeMillis(), BillFunding.compareFunding(lines, previous), List.copyOf(cited));
        byte[] json = PoliscoreUtil.getObjectMapper().writeValueAsBytes(snapshot);
        archive.writeJsonIfUnchanged(key(meta), json, stored.map(S3PersistenceService.ArchiveJson::etag).orElse(null), budget.left());
        return snapshot;
    }

    private Optional<BillText> readText(String billId, BillText expected, Budget budget) {
        String version = expected == null ? null : expected.getVersion();
        String id = BillText.generateId(billId, version);
        var text = archive.readJson(id + ".json", BillText.class, budget.left());
        if (text.isEmpty() && version != null) {
            text = archive.readJson(BillText.generateId(billId) + ".json", BillText.class, budget.left());
        }
        if (text.isEmpty() && version == null) {
            // Some pre-metadata bills only have versioned archive objects.
            BillText latest = null;
            String prefix = id + "/";
            String token = null;
            do {
                var page = archive.listJsonPage(prefix, token, budget.left());
                for (var object : page.contents()) {
                    if (!object.key().endsWith(".json")) continue;
                    var candidate = archive.readJson(object.key(), BillText.class, budget.left()).orElseThrow();
                    if (billId.equals(candidate.getBillId()) && StringUtils.isNotBlank(candidate.getDocument())
                            && BillTextOrder.ASCENDING.compare(candidate, latest) > 0) latest = candidate;
                }
                token = page.nextContinuationToken();
            } while (token != null);
            text = Optional.ofNullable(latest);
        }
        if (text.isEmpty()) return Optional.empty();
        var found = text.get();
        if (!billId.equals(found.getBillId()) || StringUtils.isBlank(found.getDocument())
                || version != null && !version.equalsIgnoreCase(found.getVersion())
                || expected != null && expected.getLastUpdate() != null && !Objects.equals(expected.getLastUpdate(), found.getLastUpdate())
                || expected != null && expected.getLegiscanId() != null && !Objects.equals(expected.getLegiscanId(), found.getLegiscanId())) {
            throw new IllegalStateException("Archive does not match " + id);
        }
        return text;
    }

    private static boolean isFinal(String version) {
        return StringUtils.defaultString(version).toUpperCase(Locale.ROOT).matches("(ENR|ENROLLED|CHAPTERED|PL)(-[0-9]+)?");
    }
}
