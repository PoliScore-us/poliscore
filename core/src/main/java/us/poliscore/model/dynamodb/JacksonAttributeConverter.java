package us.poliscore.model.dynamodb;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.val;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.AIInterpretationMetadata;
import us.poliscore.model.legislator.Legislator.LegislativeTerm;
import us.poliscore.model.legislator.LegislatorBillInteraction;
import us.poliscore.model.session.SessionInterpretation.PartyInterpretation;

public class JacksonAttributeConverter <T> implements AttributeConverter<T> {

	private static Logger logger = LoggerFactory.getLogger(JacksonAttributeConverter.class);
	
    protected final Class<? extends T> clazz;
    protected final JavaType javaType;
    protected static final ObjectMapper mapper = PoliscoreUtil.getObjectMapper();

    static {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public JacksonAttributeConverter(Class<? extends T> clazz) {
        this.clazz = clazz;
        this.javaType = mapper.getTypeFactory().constructType(clazz);
    }

    public JacksonAttributeConverter(Class<? extends T> clazz, JavaType javaType) {
        this.clazz = clazz;
        this.javaType = javaType;
    }

    @Override
    public AttributeValue transformFrom(T input) {
        try {
            return AttributeValue
                    .builder()
                    .s(mapper.writeValueAsString(input))
                    .build();
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Unable to serialize object", e);
        }
    }

	@Override
    public T transformTo(AttributeValue input) {
        try {
        	return mapper.readValue(input.s(), this.javaType);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Unable to parse object", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public EnhancedType type() {
        return EnhancedType.of((Class<T>) this.clazz);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
    
    public static class CompressedJacksonAttributeConverter <T> extends JacksonAttributeConverter<T> {

		public CompressedJacksonAttributeConverter(Class<T> clazz) {
			super(clazz);
		}
		
		@Override
		@SneakyThrows
	    public AttributeValue transformFrom(T input) {
			@Cleanup val baos = new ByteArrayOutputStream();
			@Cleanup val zos = new GZIPOutputStream(baos);
			zos.write(mapper.writeValueAsString(input).getBytes());
			zos.close();
			
            return AttributeValue
                    .builder()
                    .b(SdkBytes.fromByteArray(baos.toByteArray()))
                    .build();
	    }

	    @Override
	    @SneakyThrows
	    public T transformTo(AttributeValue input) {
	    	try {
		    	@Cleanup val bais = new GZIPInputStream(new ByteArrayInputStream(input.b().asByteArray()));
		    	
	        	return mapper.readValue(bais.readAllBytes(), this.clazz);
	    	}
	    	catch (Exception e) {
	    		logger.error("Error transforming compressed attribute value", e);
	    		return this.clazz.newInstance();
	    	}
	    }
	    
	    @Override
	    public AttributeValueType attributeValueType() {
	        return AttributeValueType.B;
	    }
    	
    }
    
    public static class JacksonHashSetConverter extends JacksonAttributeConverter<HashSet> {

        public JacksonHashSetConverter() {
            super(HashSet.class);
        }
    }
    
    public static class LegislatorBillInteractionConverter extends JacksonAttributeConverter<LegislatorBillInteraction> {

        public LegislatorBillInteractionConverter() {
            super(LegislatorBillInteraction.class);
        }
    }
    
    public static class AIInterpretationMetadataConverter extends JacksonAttributeConverter<AIInterpretationMetadata> {
    	
    	public AIInterpretationMetadataConverter() {
    		super(AIInterpretationMetadata.class);
    	}
    }
    
    public static class LegislatorLegislativeTermSortedSetConverter extends JacksonAttributeConverter<TreeSet<LegislativeTerm>> {
    	
    	@SuppressWarnings("unchecked")
    	public LegislatorLegislativeTermSortedSetConverter() {
    		super((Class<TreeSet<LegislativeTerm>>) (Class<?>) TreeSet.class,
    				mapper.getTypeFactory().constructCollectionType(TreeSet.class, LegislativeTerm.class));
    	}
    }
    
    public static class CompressedPartyStatsConverter extends CompressedJacksonAttributeConverter<PartyInterpretation> {
    	
    	public CompressedPartyStatsConverter() {
    		super(PartyInterpretation.class);
    	}
    }
}
