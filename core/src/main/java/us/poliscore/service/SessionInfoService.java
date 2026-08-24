package us.poliscore.service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import lombok.SneakyThrows;
import lombok.val;
import us.poliscore.PoliscoreCompositeDataset;
import us.poliscore.PoliscoreDataset;
import us.poliscore.PoliscoreUtil;
import us.poliscore.dataset.PoliscoreDatasetIF;
import us.poliscore.model.LegislativeNamespace;
import us.poliscore.model.LegislativeSession;

public class SessionInfoService {

    private static volatile List<LegislativeSession> cachedSessions;
    private static final TypeReference<List<LegislativeSession>> SESSIONS_TYPE =
            new TypeReference<List<LegislativeSession>>() {};

    // Lazily obtain the CDI-managed ObjectMapper once and cache it.
    private static volatile ObjectMapper mapper;

    public static ObjectMapper mapper() {
        ObjectMapper local = mapper;
        if (local != null) return local;

        synchronized (SessionInfoService.class) {
            if (mapper == null) {
                // Try Quarkus CDI first (keeps all Quarkus customizations)
                try {
                    InstanceHandle<ObjectMapper> handle = Arc.container().instance(ObjectMapper.class);
                    if (handle.isAvailable()) {
                        mapper = handle.get();
                    } else {
                        // Fall back to a self-built mapper with the same key modules/flags
                        mapper = MapperFallbackFactory.create();
                    }
                } catch (Throwable t) {
                    // Arc might not be running (e.g., in certain tests); fall back
                    mapper = MapperFallbackFactory.create();
                }
            }
            return mapper;
        }
    }
    
    @SneakyThrows
	public static void writeSessions(List<PoliscoreDatasetIF> datasets, File path) {
		val result = buildSessions(datasets);
		writeSessionMetadata(result, path);
	}

    @SneakyThrows
	public static void writeSessionMetadata(List<LegislativeSession> sessions, File path) {
		cachedSessions = List.copyOf(sessions);
		System.out.println("Writing sessions to " + path.getAbsolutePath());
		FileUtils.write(path, PoliscoreUtil.getObjectMapper().writeValueAsString(sessions), "UTF-8");
	}
    
    @SneakyThrows
	public static List<LegislativeSession> buildSessions(List<PoliscoreDatasetIF> datasets) {
		val result = sessionsForDatasets(datasets);
		cachedSessions = result;
		
		return result;
	}

    public static List<LegislativeSession> sessionsForDatasets(List<PoliscoreDatasetIF> datasets) {
        val result = new ArrayList<LegislativeSession>();

        for (var dataset : datasets) {
            if (dataset instanceof PoliscoreCompositeDataset composite) {
                for (var child : composite.getDatasets()) {
                    result.add(((PoliscoreDataset) child).getSession());
                }
            } else {
                result.add(((PoliscoreDataset) dataset).getSession());
            }
        }

        return result;
    }

    @SneakyThrows
    public static List<LegislativeSession> getSessions() {
        if (cachedSessions == null) {
            synchronized (SessionInfoService.class) {
                if (cachedSessions == null) {
                    try (var in = SessionInfoService.class.getResourceAsStream("/sessions.json")) {
                        String json = IOUtils.toString(in, StandardCharsets.UTF_8);
                        cachedSessions = mapper().readValue(json, SESSIONS_TYPE);
                    }
                }
            }
        }
        return cachedSessions;
    }
    
    public static LegislativeSession lookupRegularSession(LegislativeNamespace namespace, String sessionCode) {
    	val session = lookupSession(namespace, sessionCode);
        return lookupRegularSession(namespace, session.getEndDate().getYear());
    }

    public static LegislativeSession sessionForId(String id) {
        String[] segments = id.split("/");
        if (segments.length < 4) {
            throw new IllegalArgumentException("Expected session-scoped id, got: " + id);
        }

        LegislativeNamespace namespace = LegislativeNamespace.of(segments[1] + "/" + segments[2]);
        String sessionCode = segments[3];
        return lookupSession(namespace, sessionCode);
    }
    
    public static LegislativeSession lookupSession(LegislativeNamespace namespace, String sessionCode) {
        return getSessions().stream()
                .filter(s -> s.getNamespace().equals(namespace) && s.getCode().equals(sessionCode))
                .findAny()
                .orElseThrow();
    }

    public static LegislativeSession lookupRegularSession(LegislativeNamespace namespace, int year) {
        return getSessions().stream()
                .filter(s -> s.getNamespace().equals(namespace) && s.isYearWithin(year) && s.isRegular())
                .findAny()
                .orElseThrow();
    }
    
    private static final class MapperFallbackFactory {
        static ObjectMapper create() {
            // JsonMapper.builder() is the modern way; mimic Quarkus defaults
            return JsonMapper.builder()
                    .addModule(new ParameterNamesModule())
                    .addModule(new Jdk8Module())
                    .addModule(new JavaTimeModule())
                    // Common Quarkus-like flags:
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
//                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .build();
        }
        private MapperFallbackFactory() {}
    }
}
