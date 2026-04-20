package com.example.evaluations;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.evaluations.AgentCoreEvaluationAdvisor;
import org.springaicommunity.agentcore.evaluations.EvaluationResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chat controller demonstrating AgentCore Evaluations integration.
 *
 * <p>
 * Reads evaluation results from the response context and returns them in the HTTP
 * response. This works only when evaluations run synchronously (so results are in the
 * context before the handler returns). The constructor fails fast if the advisor is
 * configured to run asynchronously.
 */
@RestController
public class ChatController {

	private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

	private final ChatClient chatClient;

	private final boolean async;

	public ChatController(ChatClient.Builder chatClientBuilder, AgentCoreEvaluationAdvisor evaluationAdvisor,
			@Value("${spring.ai.agentcore.evaluations.async:true}") boolean async) {
		this.chatClient = chatClientBuilder.defaultAdvisors(evaluationAdvisor).build();
		this.async = async;
	}

	@PostConstruct
	void assertSyncEvaluation() {
		if (this.async) {
			throw new IllegalStateException(
					"This example reads evaluation results from the HTTP response and therefore requires "
							+ "spring.ai.agentcore.evaluations.async=false. "
							+ "Set it to false in application.properties, or switch to the callback pattern.");
		}
	}

	@PostMapping("/chat")
	public ChatResponse chat(@RequestBody ChatRequest request) {
		logger.info("Received chat request: {}", request.message());

		ChatClientResponse response = chatClient.prompt().user(request.message()).call().chatClientResponse();

		@SuppressWarnings("unchecked")
		List<EvaluationResult> evaluations = (List<EvaluationResult>) response.context()
			.getOrDefault(AgentCoreEvaluationAdvisor.EVALUATION_RESULTS_KEY, List.of());

		String content = response.chatResponse() != null && response.chatResponse().getResult() != null
				? response.chatResponse().getResult().getOutput().getText() : "";
		logger.info("Response generated with {} evaluation result(s)", evaluations.size());

		return new ChatResponse(content, evaluations);
	}

	public record ChatRequest(String message) {
	}

	public record ChatResponse(String content, List<EvaluationResult> evaluations) {
	}

}
