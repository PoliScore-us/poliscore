package us.poliscore.service;

import java.time.LocalDate;
import java.util.*;

/** Canonical profiles select the latest available record independently of input ordering. */
public final class LegislatorIndex {
    private LegislatorIndex() {}

    public static List<List<String>> canonicalRows(List<List<String>> rows, LocalDate today) {
        var selected = new HashMap<String, List<String>>();
        for (var row : rows) {
            if (row.isEmpty()) continue;
            String[] id = row.get(0).split("/", 5);
            if (id.length != 5 || !id[0].equals("LEG")) continue;
            try {
                var session = SessionInfoService.sessionForId(row.get(0));
                if (session.getActivationDate().isAfter(today)) continue;
                String person = id[1] + "/" + id[2] + "/" + id[4];
                var existing = selected.get(person);
                if (existing == null || session.getActivationDate().isAfter(
                        SessionInfoService.sessionForId(existing.get(0)).getActivationDate())) selected.put(person, row);
            } catch (NoSuchElementException missingMetadata) {
                // Unknown sessions cannot safely become canonical records.
            }
        }
        return selected.values().stream().sorted(Comparator.comparing(row -> row.size() > 1 ? row.get(1) : row.get(0))).toList();
    }
}
