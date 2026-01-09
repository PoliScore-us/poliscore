package us.poliscore.bill;

import com.openai.models.ReasoningEffort;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterpretationRequest {

  private CustomData data;

  private String systemMsg;
  
  private String userMsg;
  
  private OpenAIModel requestedModel;
  
  private ReasoningEffort reasoningEffort;
  
}
