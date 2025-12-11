package us.poliscore.model.dynamodb;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import us.poliscore.model.bill.StructuralAnalysis;

public class StructuralAnalysisExplainAttributeConverter implements AttributeConverter<Map<StructuralAnalysis, String>> {

    @Override
    public AttributeValue transformFrom(final Map<StructuralAnalysis, String> input) {
        if (input == null || input.isEmpty()) {
            return AttributeValue.builder().m(Collections.emptyMap()).build();
        }

        Map<String, AttributeValue> attributeValueMap = input.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> {
                            String v = e.getValue();
                            if (v == null) v = "";
                            return AttributeValue.builder().s(v).build();
                        }
                ));

        return AttributeValue.builder().m(attributeValueMap).build();
    }

    @Override
    public Map<StructuralAnalysis, String> transformTo(final AttributeValue input) {
        if (input == null || input.m() == null || input.m().isEmpty()) {
            return Collections.emptyMap();
        }

        return input.m().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> getEnumClassKeyByString(e.getKey()),
                        e -> {
                            AttributeValue av = e.getValue();
                            return av.s() == null ? "" : av.s();
                        }
                ));
    }

    private StructuralAnalysis getEnumClassKeyByString(final String key) {
        try {
            return StructuralAnalysis.valueOf(key);
        } catch (IllegalArgumentException ex) {
            // Fallback if something weird is in the DB
            return StructuralAnalysis.DISTRIBUTIONAL_IMPACT_FAIRNESS;
        }
    }

    @Override
    public EnhancedType<Map<StructuralAnalysis, String>> type() {
        return EnhancedType.mapOf(StructuralAnalysis.class, String.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.M;
    }
}
