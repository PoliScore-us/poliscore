package us.poliscore.model.dynamodb;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import us.poliscore.model.bill.StructuralAnalysis;

public class StructuralAnalysisPassFailAttributeConverter implements AttributeConverter<Map<StructuralAnalysis, Boolean>> {

    @Override
    public AttributeValue transformFrom(final Map<StructuralAnalysis, Boolean> input) {
        if (input == null || input.isEmpty()) {
            return AttributeValue.builder().m(Collections.emptyMap()).build();
        }

        Map<String, AttributeValue> attributeValueMap = input.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> AttributeValue.builder().bool(e.getValue()).build()
                ));

        return AttributeValue.builder().m(attributeValueMap).build();
    }

    @Override
    public Map<StructuralAnalysis, Boolean> transformTo(final AttributeValue input) {
        if (input == null || input.m() == null || input.m().isEmpty()) {
            return Collections.emptyMap();
        }

        return input.m().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> getEnumClassKeyByString(e.getKey()),
                        e -> e.getValue().bool()
                ));
    }

    private StructuralAnalysis getEnumClassKeyByString(final String key) {
        try {
            return StructuralAnalysis.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return StructuralAnalysis.DISTRIBUTIONAL_IMPACT_FAIRNESS;
        }
    }

    @Override
    public EnhancedType<Map<StructuralAnalysis, Boolean>> type() {
        return EnhancedType.mapOf(StructuralAnalysis.class, Boolean.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.M;
    }
}
