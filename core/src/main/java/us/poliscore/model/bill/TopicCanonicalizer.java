package us.poliscore.model.bill;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TopicCanonicalizer {

	private static final String SYNONYM_RESOURCE = "/topic-synonyms.json";
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
	private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
	private static final Set<String> ACCESSORY_TOKENS = Set.of(
			"act", "acts", "administration", "authorization", "authorizations", "ban", "bans", "bill", "bills",
			"commission", "committee", "concerning", "design", "establishment", "expansion", "for", "fund",
			"funding", "grant", "grants", "implementation", "improvement", "measure", "measures", "office", "on",
			"policy", "policies", "program", "programs", "prohibit", "prohibited", "prohibiting", "prohibition",
			"prohibitions", "proposal", "proposals", "reform", "regarding", "related", "relating", "restriction",
			"restrictions", "rule", "rules", "study", "studies", "task", "the", "to");
	private static final Set<String> FLUFF_WORDS = Set.of( // Fluff words always get stripped
			"federal funding for", "tabulation process for", "implementation", "prohibition of", "in federal elections");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static volatile TopicCanonicalizer instance;

	private final Map<String, String> canonicalByNormalizedAlias;
	private final List<AliasEntry> aliasesBySpecificity;

	private TopicCanonicalizer(Map<String, List<String>> synonyms) {
		Map<String, String> aliasMap = new LinkedHashMap<>();
		List<AliasEntry> aliases = new ArrayList<>();

		for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
			String canonical = cleanDisplayTopic(entry.getKey());
			String canonicalNormalized = normalizeTopic(canonical);
			if (StringUtils.isBlank(canonicalNormalized)) {
				continue;
			}

			addAlias(aliasMap, aliases, canonical, canonicalNormalized);
			for (String alias : entry.getValue()) {
				String aliasNormalized = normalizeTopic(alias);
				if (StringUtils.isNotBlank(aliasNormalized)) {
					addAlias(aliasMap, aliases, canonical, aliasNormalized);
				}
			}
		}

		aliases.sort(Comparator
				.comparingInt((AliasEntry alias) -> alias.tokens().size()).reversed()
				.thenComparingInt(alias -> alias.normalized().length()).reversed());
		this.canonicalByNormalizedAlias = Map.copyOf(aliasMap);
		this.aliasesBySpecificity = List.copyOf(aliases);
	}

	public static List<String> canonicalizeTopics(List<String> topics) {
		if (topics == null || topics.isEmpty()) {
			return new ArrayList<>();
		}

		TopicCanonicalizer canonicalizer = get();
		List<String> firstPass = topics.stream()
				.map(canonicalizer::canonicalize)
				.filter(StringUtils::isNotBlank)
				.toList();
		List<String> collapsed = canonicalizer.collapseNearDuplicates(firstPass);
		LinkedHashSet<String> unique = new LinkedHashSet<>(collapsed);
		return new ArrayList<>(unique);
	}

	public static String canonicalizeTopic(String topic) {
		return get().canonicalize(topic);
	}

	public static String normalizeTopic(String topic) {
		if (StringUtils.isBlank(topic)) {
			return "";
		}
		
		for(String fluff : FLUFF_WORDS)
			topic = topic.replaceAll(fluff, "");

		return MULTI_SPACE.matcher(NON_ALNUM.matcher(Normalizer.normalize(topic, Normalizer.Form.NFKD)
						.replaceAll("\\p{M}", "")
						.toLowerCase(Locale.ROOT))
				.replaceAll(" "))
				.replaceAll(" ")
				.trim();
	}

	private static TopicCanonicalizer get() {
		TopicCanonicalizer local = instance;
		if (local == null) {
			synchronized (TopicCanonicalizer.class) {
				local = instance;
				if (local == null) {
					local = new TopicCanonicalizer(loadSynonyms());
					instance = local;
				}
			}
		}
		return local;
	}

	private String canonicalize(String rawTopic) {
		String normalized = normalizeTopic(rawTopic);
		if (StringUtils.isBlank(normalized)) {
			return "";
		}

		String direct = canonicalByNormalizedAlias.get(normalized);
		if (direct != null) {
			return direct;
		}

		List<String> topicTokens = tokens(normalized);
		for (AliasEntry alias : aliasesBySpecificity) {
			if (containsSubsequence(topicTokens, alias.tokens()) && hasOnlyAccessoryRemainder(topicTokens, alias.tokens())) {
				return alias.canonical();
			}
		}

		return cleanDisplayTopic(rawTopic);
	}

	private List<String> collapseNearDuplicates(List<String> topics) {
		List<String> result = new ArrayList<>();
		for (String topic : topics) {
			String collapsed = topic;
			List<String> topicTokens = tokens(normalizeTopic(topic));
			for (String existing : result) {
				List<String> existingTokens = tokens(normalizeTopic(existing));
				if (containsAllTokens(topicTokens, existingTokens) && hasOnlyAccessoryRemainder(topicTokens, existingTokens)) {
					collapsed = existing;
					break;
				}
				if (containsAllTokens(existingTokens, topicTokens) && hasOnlyAccessoryRemainder(existingTokens, topicTokens)) {
					int index = result.indexOf(existing);
					result.set(index, topic);
					collapsed = topic;
					break;
				}
			}
			result.add(collapsed);
		}
		return result;
	}

	private static void addAlias(Map<String, String> aliasMap, List<AliasEntry> aliases, String canonical, String aliasNormalized) {
		aliasMap.putIfAbsent(aliasNormalized, canonical);
		aliases.add(new AliasEntry(canonical, aliasNormalized, tokens(aliasNormalized)));
	}

	private static Map<String, List<String>> loadSynonyms() {
		try (InputStream input = TopicCanonicalizer.class.getResourceAsStream(SYNONYM_RESOURCE)) {
			if (input == null) {
				return Map.of();
			}
			return MAPPER.readValue(input, new TypeReference<Map<String, List<String>>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("Unable to load " + SYNONYM_RESOURCE, e);
		}
	}

	private static String cleanDisplayTopic(String topic) {
		String cleaned = normalizeTopic(topic);
		if (StringUtils.isBlank(cleaned)) {
			return "";
		}
		return cleaned;
	}

	private static boolean containsSubsequence(List<String> tokens, List<String> candidate) {
		if (candidate.isEmpty() || candidate.size() > tokens.size()) {
			return false;
		}
		for (int start = 0; start <= tokens.size() - candidate.size(); start++) {
			boolean matches = true;
			for (int i = 0; i < candidate.size(); i++) {
				if (!tokens.get(start + i).equals(candidate.get(i))) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsAllTokens(List<String> tokens, List<String> candidate) {
		return tokens.containsAll(candidate);
	}

	private static boolean hasOnlyAccessoryRemainder(List<String> tokens, List<String> canonicalTokens) {
		List<String> remaining = new ArrayList<>(tokens);
		for (String token : canonicalTokens) {
			remaining.remove(token);
		}
		return remaining.stream().allMatch(ACCESSORY_TOKENS::contains);
	}

	private static List<String> tokens(String normalizedTopic) {
		if (StringUtils.isBlank(normalizedTopic)) {
			return List.of();
		}
		return Arrays.stream(normalizedTopic.split(" "))
				.map(TopicCanonicalizer::normalizeToken)
				.filter(StringUtils::isNotBlank)
				.toList();
	}

	private static String normalizeToken(String token) {
		if (token.length() > 4 && token.endsWith("ies")) {
			return token.substring(0, token.length() - 3) + "y";
		}
		if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) {
			return token.substring(0, token.length() - 1);
		}
		return token;
	}

	private record AliasEntry(String canonical, String normalized, List<String> tokens) {
	}
}
