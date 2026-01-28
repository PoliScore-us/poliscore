package us.poliscore.model.dynamodb;

import java.util.Map;
import java.util.stream.Collectors;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import us.poliscore.model.bill.StructuralAnalysis;

public class StructuralStatsAttributeConverter implements AttributeConverter<Map<StructuralAnalysis, Double>> {

  @Override
  public AttributeValue transformFrom(final Map<StructuralAnalysis, Double> input) {
    Map<String, AttributeValue> attributeValueMap = input.entrySet().stream()
            .collect(
                Collectors.toMap(
                    k -> k.getKey().name(),
                    v -> AttributeValue.builder().n(String.valueOf(v.getValue())).build()));
    return AttributeValue.builder().m(attributeValueMap).build();
  }

  @Override
  public Map<StructuralAnalysis, Double> transformTo(final AttributeValue input) {
    return input.m().entrySet().stream()
        .collect(
            Collectors.toMap(
                k -> getEnumClassKeyByString(k.getKey()), v -> Double.parseDouble(v.getValue().n())));
  }

  private StructuralAnalysis getEnumClassKeyByString(final String key) {
    StructuralAnalysis enumClass = StructuralAnalysis.valueOf(key);
    return enumClass != null ? enumClass : StructuralAnalysis.BUDGET;
  }

  @Override
  public EnhancedType<Map<StructuralAnalysis, Double>> type() {
    return EnhancedType.mapOf(StructuralAnalysis.class, Double.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.M;
  }
}