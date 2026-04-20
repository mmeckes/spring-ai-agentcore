/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.agentcore.evaluations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Advisor that evaluates agent responses using the AgentCore Evaluate API.
 *
 * <p>
 * Captures prompt/response pairs, builds OTel-compatible spans, and calls the Evaluate
 * API. Results are stored in the response context and optionally published via callbacks
 * and metrics.
 *
 * @author Andrei Shakirin
 */
public class AgentCoreEvaluationAdvisor implements CallAdvisor, StreamAdvisor {

	private static final Logger logger = LoggerFactory.getLogger(AgentCoreEvaluationAdvisor.class);

	/**
	 * Context key under which evaluation results are stored on the response.
	 * <p>
	 * Reliable only when {@code async=false}. In async mode, the handler returns before
	 * the evaluation completes, so the key may not be populated by the time the caller
	 * reads {@code response.context()}. Prefer the callback or metrics channels in async
	 * mode.
	 */
	public static final String EVALUATION_RESULTS_KEY = "agentcore.evaluation.results";

	private final AgentCoreEvaluationClient client;

	private final List<String> evaluatorIds;

	private final boolean async;

	private final double sampleRate;

	private final AgentCoreEvaluationMetrics metrics;

	private final Consumer<EvaluationEvent> callback;

	private final int order;

	private final Executor executor;

	/**
	 * Shared default executor used when the caller does not supply one. A single
	 * virtual-thread-per-task executor is kept for the JVM lifetime so that manually
	 * constructed advisors do not each leak their own executor. Production setups should
	 * inject a managed executor via the builder.
	 */
	private static final Executor DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	private AgentCoreEvaluationAdvisor(Builder builder) {
		this.client = builder.client;
		this.evaluatorIds = builder.evaluatorIds;
		this.async = builder.async;
		this.sampleRate = builder.sampleRate;
		this.metrics = builder.metrics;
		this.callback = builder.callback;
		this.order = builder.order;
		this.executor = builder.executor != null ? builder.executor : DEFAULT_EXECUTOR;
	}

	public static Builder builder(AgentCoreEvaluationClient client) {
		return new Builder(client);
	}

	public static class Builder {

		private final AgentCoreEvaluationClient client;

		private List<String> evaluatorIds = List.of("Builtin.Helpfulness");

		private boolean async = true;

		private double sampleRate = 1.0;

		private AgentCoreEvaluationMetrics metrics;

		private Consumer<EvaluationEvent> callback;

		private int order = 1000; // Run after most advisors

		private Executor executor;

		private Builder(AgentCoreEvaluationClient client) {
			Objects.requireNonNull(client, "AgentCoreEvaluationClient is required");
			this.client = client;
		}

		public Builder evaluatorIds(List<String> evaluatorIds) {
			this.evaluatorIds = evaluatorIds;
			return this;
		}

		public Builder async(boolean async) {
			this.async = async;
			return this;
		}

		public Builder sampleRate(double sampleRate) {
			this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
			return this;
		}

		public Builder metrics(AgentCoreEvaluationMetrics metrics) {
			this.metrics = metrics;
			return this;
		}

		public Builder callback(Consumer<EvaluationEvent> callback) {
			this.callback = callback;
			return this;
		}

		public Builder order(int order) {
			this.order = order;
			return this;
		}

		public Builder executor(Executor executor) {
			this.executor = executor;
			return this;
		}

		public AgentCoreEvaluationAdvisor build() {
			return new AgentCoreEvaluationAdvisor(this);
		}

	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		ChatClientResponse response = chain.nextCall(request);

		if (shouldSample()) {
			runEvaluation(request, response);
		}

		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		// Decide once, up-front, whether this turn is sampled. Chunks pass through
		// untouched so streaming to the caller is preserved; text is accumulated on the
		// side and the evaluation is triggered on completion.
		boolean sampled = shouldSample();
		StringBuilder text = sampled ? new StringBuilder() : null;
		AtomicReference<ChatClientResponse> last = new AtomicReference<>();

		return chain.nextStream(request).doOnNext(chunk -> {
			last.set(chunk);
			if (sampled) {
				String t = extractAssistantResponse(chunk);
				if (t != null) {
					text.append(t);
				}
			}
		}).doOnComplete(() -> {
			if (sampled && last.get() != null) {
				runEvaluation(request, aggregated(last.get(), text.toString()));
			}
		});
	}

	private ChatClientResponse aggregated(ChatClientResponse last, String text) {
		ChatResponse chat = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
		return ChatClientResponse.builder().chatResponse(chat).context(last.context()).build();
	}

	private boolean shouldSample() {
		return this.sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < this.sampleRate;
	}

	private void runEvaluation(ChatClientRequest request, ChatClientResponse response) {
		if (this.async) {
			CompletableFuture.runAsync(() -> doEvaluate(request, response), this.executor);
		}
		else {
			doEvaluate(request, response);
		}
	}

	private void doEvaluate(ChatClientRequest request, ChatClientResponse response) {
		String sessionId = extractSessionId(request);
		String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

		try {
			String userPrompt = extractUserPrompt(request);
			String assistantResponse = extractAssistantResponse(response);

			if (userPrompt == null || assistantResponse == null) {
				logger.debug("Skipping evaluation: missing prompt or response");
				return;
			}

			// Build OTel-compatible spans and events
			SpanEventBuilder spanBuilder = SpanEventBuilder.agentInvocation(traceId, sessionId)
				.promptEvent(userPrompt)
				.completionEvent(assistantResponse)
				.modelId(extractModelId(response));

			List<Map<String, Object>> spans = spanBuilder.buildSessionSpans();

			Instant start = Instant.now();
			List<EvaluationResult> results = this.client.evaluateAll(this.evaluatorIds, spans);
			Duration latency = Duration.between(start, Instant.now());

			// Record metrics
			if (this.metrics != null) {
				for (EvaluationResult r : results) {
					String evaluatorId = r.evaluatorId() != null ? r.evaluatorId() : "unknown";
					if (r.isError()) {
						this.metrics.recordError(evaluatorId, r.errorCode());
					}
					else {
						this.metrics.record(evaluatorId, r, latency);
					}
				}
			}

			// Store results in response context
			response.context().put(EVALUATION_RESULTS_KEY, results);

			// Invoke callback
			if (this.callback != null) {
				EvaluationEvent event = new EvaluationEvent(sessionId, traceId, results, Instant.now(), latency);
				this.callback.accept(event);
			}

			logger.debug("Evaluation completed: {} results for session {}", results.size(), sessionId);

		}
		catch (Exception e) {
			logger.error("Evaluation failed for session {}: {}", sessionId, e.getMessage());
			if (this.metrics != null) {
				for (String evaluatorId : this.evaluatorIds) {
					this.metrics.recordError(evaluatorId, e.getClass().getSimpleName());
				}
			}
		}
	}

	private String extractSessionId(ChatClientRequest request) {
		Object sessionId = request.context().get("sessionId");
		if (sessionId != null) {
			return sessionId.toString();
		}
		Object conversationId = request.context().get("conversationId");
		if (conversationId != null) {
			return conversationId.toString();
		}
		return UUID.randomUUID().toString();
	}

	private String extractUserPrompt(ChatClientRequest request) {
		for (Message msg : request.prompt().getInstructions()) {
			if (msg instanceof UserMessage) {
				return msg.getText();
			}
		}
		return null;
	}

	// ChatResponse.getResult() is declared non-null by Spring AI's @NonNullApi package
	// annotation but returns null when the generations list is empty. The guard below
	// is deliberate runtime defence against that contract-vs-source mismatch; the
	// suppression silences the false "always true" analyser warning.
	@SuppressWarnings("ConstantConditions")
	private String extractAssistantResponse(ChatClientResponse response) {
		if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
			return null;
		}
		Message output = response.chatResponse().getResult().getOutput();
		return (output instanceof AssistantMessage) ? output.getText() : null;
	}

	private String extractModelId(ChatClientResponse response) {
		if (response.chatResponse() == null) {
			return null;
		}
		String model = response.chatResponse().getMetadata().getModel();
		return (model != null && !model.isBlank()) ? model : null;
	}

	@Override
	public String getName() {
		return "AgentCoreEvaluationAdvisor";
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}
