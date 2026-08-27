package us.poliscore.bill;

import com.openai.models.ReasoningEffort;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import us.poliscore.ai.BatchOpenAIRequest.CustomData;
import us.poliscore.ai.OpenAIModel;

@Data
@NoArgsConstructor
@Builder
public class InterpretationRequest {

  private CustomData data;

  private String systemMsg;
  
  private String userMsg;
  
  private OpenAIModel requestedModel;
  
  private ReasoningEffort reasoningEffort;

  /**
   * Optional override for OpenAI FLEX service tier usage.
   * Null means "use the service default decision logic."
   */
  private Boolean flex;

  /**
   * Optional class used to constrain a Responses API call to strict structured JSON output.
   */
  private Class<?> responseType;

  public Boolean getFlex() {
    return Boolean.TRUE.equals(flex);
  }

  public InterpretationRequest(CustomData data, String systemMsg, String userMsg, OpenAIModel requestedModel,
      ReasoningEffort reasoningEffort) {
    this(data, systemMsg, userMsg, requestedModel, reasoningEffort, null);
  }

  public InterpretationRequest(CustomData data, String systemMsg, String userMsg, OpenAIModel requestedModel,
      ReasoningEffort reasoningEffort, Boolean flex) {
    this(data, systemMsg, userMsg, requestedModel, reasoningEffort, flex, null);
  }

  public InterpretationRequest(CustomData data, String systemMsg, String userMsg, OpenAIModel requestedModel,
      ReasoningEffort reasoningEffort, Boolean flex, Class<?> responseType) {
    this.data = data;
    this.systemMsg = systemMsg;
    this.userMsg = userMsg;
    this.requestedModel = requestedModel;
    this.reasoningEffort = reasoningEffort;
    this.flex = flex;
    this.responseType = responseType;
  }
  
}
