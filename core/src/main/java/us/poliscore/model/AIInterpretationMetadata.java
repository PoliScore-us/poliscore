package us.poliscore.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@Data
@DynamoDbBean
@AllArgsConstructor
@NoArgsConstructor
public class AIInterpretationMetadata extends InterpretationMetadata {
	
	@NonNull
	protected String provider;
	
	@NonNull
	protected String model;
	
	/**
	 * When set to true, indicates that the interpretation was produced by an ai agent with access to a web search tool.
	 */
	protected boolean webSearchAgent;
	
	@NonNull
	protected int promptVersion;
	
	@NonNull
	protected LocalDate date;
	
	public static AIInterpretationMetadata construct(String provider, String model, int promptVersion, boolean webSearchAgent)
	{
		AIInterpretationMetadata meta = new AIInterpretationMetadata();
		meta.setProvider(provider);
		meta.setModel(model);
		meta.setPromptVersion(promptVersion);
		meta.setDate(LocalDate.now());
		meta.setWebSearchAgent(webSearchAgent);
		return meta;
	}
	
}
