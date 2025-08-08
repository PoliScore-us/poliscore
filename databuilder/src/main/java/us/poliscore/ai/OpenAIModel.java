package us.poliscore.ai;

import java.util.NoSuchElementException;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OpenAIModel {
	
	public static final OpenAIModel GPT5 = new OpenAIModel("gpt-5", 400_000, 128_000, false, true, true);
	
	public static final OpenAIModel GPT41 = new OpenAIModel("gpt-4.1", 950_000, 32_768, true, true, false);
	
	public static final OpenAIModel GPT41mini = new OpenAIModel("gpt-4.1-mini", GPT41.getContextWindowTokens(), GPT41.getMaxOutputTokens(), true, false, false);
	
	public static final OpenAIModel GPT4o = new OpenAIModel("gpt-4o", 122_500, 14_000, true, true, false);
	
	public static final OpenAIModel o3 = new OpenAIModel("o3", 190_000, 95_000, false, true, true);
	
	public static final OpenAIModel o3DeepResearch = new OpenAIModel("o3-deep-research", 190_000, 95_000, false, true, false);
	
	public static final OpenAIModel DEFAULT_MODEL = GPT5;
	
	public static OpenAIModel fromString(String _id) {
		if (_id.equals(GPT41.getId())) {
			return GPT41;
		} else if (_id.equals(GPT4o.getId())) {
			return GPT4o;
		} else if (_id.equals(o3.getId())) {
			return o3;
		} else if (_id.equals(o3DeepResearch.getId())) {
			return o3DeepResearch;
		} else if (_id.equals(GPT41mini.getId())) {
			return GPT41mini;
		} else if (_id.equals(GPT5.getId())) {
			return GPT5;
		} else {
			throw new NoSuchElementException(_id);
		}
	}
	
	private String id;
	
	private int contextWindowTokens;
	
	private int maxOutputTokens;
	
	private boolean supportsTemperature;
	
	private boolean supportsSearch;
	
	private boolean isMaxEffort;
	
	public int getContextWindowStringLength() {
		return contextWindowTokens * 4;
	}
	
}
