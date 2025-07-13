package us.poliscore.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import us.poliscore.legiscan.view.LegiscanState;

public class LegiscanStateConverter implements AttributeConverter<LegiscanState> {

    @Override
    public AttributeValue transformFrom(LegiscanState input) {
        return AttributeValue.fromS(input.toString());
    }

    @Override
    public LegiscanState transformTo(AttributeValue input) {
        return LegiscanState.fromAbbreviation(input.s());
    }

    @Override
    public EnhancedType<LegiscanState> type() {
        return EnhancedType.of(LegiscanState.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
