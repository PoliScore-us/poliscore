package us.poliscore.model;

import java.io.IOException;
import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import us.poliscore.legiscan.view.LegiscanState;
import us.poliscore.model.LegislativeNamespace.NamespaceDeserializer;
import us.poliscore.model.LegislativeNamespace.NamespaceSerializer;

@Getter
@JsonSerialize(using = NamespaceSerializer.class)
@JsonDeserialize(using = NamespaceDeserializer.class)
public enum LegislativeNamespace {
	US_CONGRESS("us/congress"),
	US_ALABAMA("us/al"),
	US_ALASKA("us/ak"),
	US_ARIZONA("us/az"),
	US_ARKANSAS("us/ar"),
	US_CALIFORNIA("us/ca"),
	US_COLORADO("us/co"),
	US_CONNECTICUT("us/ct"),
	US_DELAWARE("us/de"),
	US_FLORIDA("us/fl"),
	US_GEORGIA("us/ga"),
	US_HAWAII("us/hi"),
	US_IDAHO("us/id"),
	US_ILLINOIS("us/il"),
	US_INDIANA("us/in"),
	US_IOWA("us/ia"),
	US_KANSAS("us/ks"),
	US_KENTUCKY("us/ky"),
	US_LOUISIANA("us/la"),
	US_MAINE("us/me"),
	US_MARYLAND("us/md"),
	US_MASSACHUSETTS("us/ma"),
	US_MICHIGAN("us/mi"),
	US_MINNESOTA("us/mn"),
	US_MISSISSIPPI("us/ms"),
	US_MISSOURI("us/mo"),
	US_MONTANA("us/mt"),
	US_NEBRASKA("us/ne"),
	US_NEVADA("us/nv"),
	US_NEW_HAMPSHIRE("us/nh"),
	US_NEW_JERSEY("us/nj"),
	US_NEW_MEXICO("us/nm"),
	US_NEW_YORK("us/ny"),
	US_NORTH_CAROLINA("us/nc"),
	US_NORTH_DAKOTA("us/nd"),
	US_OHIO("us/oh"),
	US_OKLAHOMA("us/ok"),
	US_OREGON("us/or"),
	US_PENNSYLVANIA("us/pa"),
	US_RHODE_ISLAND("us/ri"),
	US_SOUTH_CAROLINA("us/sc"),
	US_SOUTH_DAKOTA("us/sd"),
	US_TENNESSEE("us/tn"),
	US_TEXAS("us/tx"),
	US_UTAH("us/ut"),
	US_VERMONT("us/vt"),
	US_VIRGINIA("us/va"),
	US_WASHINGTON("us/wa"),
	US_WASHINGTON_DC("us/dc"),
	US_WEST_VIRGINIA("us/wv"),
	US_WISCONSIN("us/wi"),
	US_WYOMING("us/wy");

	private final String namespace;

	private LegislativeNamespace(String namespace) {
		this.namespace = namespace;
	}

	@JsonCreator
	public static LegislativeNamespace of(String namespace)
	{
		return Arrays.asList(LegislativeNamespace.values()).stream().filter(n -> n.getNamespace().equals(namespace)).findFirst().get();
	}

	public static LegislativeNamespace fromAbbreviation(String abbr) {
		if (abbr == null) {
			throw new IllegalArgumentException("Abbreviation cannot be null");
		}
		String normalized = abbr.trim().toLowerCase();
		String fullNamespace = normalized.equals("us") ? "us/congress" : "us/" + normalized;

		return of(fullNamespace);
	}
	
	public String toAbbreviation() {
		if (this.equals(US_CONGRESS)) return "US";
		
		return this.getNamespace().split("/")[1].toUpperCase();
	}
	
	// We can't override it because ddb uses it for serialization for some reason
//	public String toString() {
//		return namespace;
//	}
	
	@Override
    @JsonValue
    public String toString() {
    	return getNamespace();
    }
	
	public String getDescription() {
		if (this == US_CONGRESS) {
			return "Congress";
		}
		
		// Extract the state abbreviation from the namespace, e.g., "us/co" → "co"
		String abbr = namespace.substring(namespace.lastIndexOf('/') + 1).toUpperCase();

		try {
			LegiscanState state = LegiscanState.fromAbbreviation(abbr);
			return capitalizeFully(state.name().replace('_', ' '));
		} catch (IllegalArgumentException e) {
			// fallback for special cases like "us/dc"
			switch (abbr) {
				case "DC": return "Washington D.C.";
				default: return abbr;
			}
		}
	}

	// Helper to capitalize e.g., "NORTH_DAKOTA" → "North Dakota"
	private static String capitalizeFully(String input) {
		String[] parts = input.split(" ");
		StringBuilder result = new StringBuilder();
		for (String part : parts) {
			if (!part.isEmpty()) {
				result.append(part.substring(0, 1).toUpperCase());
				result.append(part.substring(1).toLowerCase());
				result.append(" ");
			}
		}
		return result.toString().trim();
	}
	
	@NoArgsConstructor
    public static class NamespaceDeserializer extends JsonDeserializer<LegislativeNamespace> {
        @Override
        public LegislativeNamespace deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String ns = p.getText();
            
            if (ns.startsWith("US_"))
            	return LegislativeNamespace.valueOf(ns);
            else
            	return LegislativeNamespace.of(ns);
        }
    }
    
    @NoArgsConstructor
    public static class NamespaceSerializer extends JsonSerializer<LegislativeNamespace> {
        @Override
        public void serialize(LegislativeNamespace value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toString());
        }
    }
    
    @NoArgsConstructor
    public static class NamespaceConverter implements AttributeConverter<LegislativeNamespace> {

        @Override
        public AttributeValue transformFrom(LegislativeNamespace input) {
            return AttributeValue.fromS(input.toString());
        }

        @Override
        public LegislativeNamespace transformTo(AttributeValue input) {
        	String ns = input.s();
            
            if (ns.startsWith("US_"))
            	return LegislativeNamespace.valueOf(ns);
            else
            	return LegislativeNamespace.of(ns);
        }

        @Override
        public EnhancedType<LegislativeNamespace> type() {
            return EnhancedType.of(LegislativeNamespace.class);
        }

        @Override
        public AttributeValueType attributeValueType() {
            return AttributeValueType.S;
        }
    }
}

