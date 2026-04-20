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
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentCoreEvaluationAdvisor}.
 *
 * @author Andrei Shakirin
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreEvaluationAdvisorTest {

	@Mock
	private AgentCoreEvaluationClient client;

	@Test
	void shouldBuildWithDefaults() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).build();

		assertThat(advisor).isNotNull();
		assertThat(advisor.getName()).isEqualTo("AgentCoreEvaluationAdvisor");
		assertThat(advisor.getOrder()).isEqualTo(1000);
	}

	@Test
	void shouldBuildWithCustomEvaluators() {
		List<String> evaluators = List.of("Builtin.Helpfulness", "Builtin.Correctness");

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client)
			.evaluatorIds(evaluators)
			.build();

		assertThat(advisor).isNotNull();
	}

	@Test
	void shouldBuildWithCustomOrder() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).order(500).build();

		assertThat(advisor.getOrder()).isEqualTo(500);
	}

	@Test
	void shouldBuildWithCallback() {
		AtomicReference<EvaluationEvent> capturedEvent = new AtomicReference<>();

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client)
			.callback(capturedEvent::set)
			.build();

		assertThat(advisor).isNotNull();
	}

	@Test
	void shouldBuildWithSampleRate() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).sampleRate(0.5).build();

		assertThat(advisor).isNotNull();
	}

	@Test
	void shouldClampSampleRateToValidRange() {
		// Sample rate > 1.0 should be clamped to 1.0
		AgentCoreEvaluationAdvisor advisor1 = AgentCoreEvaluationAdvisor.builder(client).sampleRate(1.5).build();
		assertThat(advisor1).isNotNull();

		// Sample rate < 0.0 should be clamped to 0.0
		AgentCoreEvaluationAdvisor advisor2 = AgentCoreEvaluationAdvisor.builder(client).sampleRate(-0.5).build();
		assertThat(advisor2).isNotNull();
	}

	@Test
	void shouldBuildWithAsyncDisabled() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).async(false).build();

		assertThat(advisor).isNotNull();
	}

	@Test
	void shouldPublishResultsAndMetricsOnSuccess() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentCoreEvaluationMetrics metrics = new AgentCoreEvaluationMetrics(registry);
		AtomicReference<EvaluationEvent> captured = new AtomicReference<>();

		EvaluationResult result = new EvaluationResult("Builtin.Helpfulness", 0.83, "Very Helpful", "ok", 100, 20,
				null);
		when(client.evaluateAll(anyList(), anyList())).thenReturn(List.of(result));

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client)
			.async(false)
			.metrics(metrics)
			.callback(captured::set)
			.build();

		ChatClientRequest request = request("What is the capital of France?");
		ChatClientResponse response = response("Paris.");
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		when(chain.nextCall(any())).thenReturn(response);

		advisor.adviseCall(request, chain);

		@SuppressWarnings("unchecked")
		List<EvaluationResult> stored = (List<EvaluationResult>) response.context()
			.get(AgentCoreEvaluationAdvisor.EVALUATION_RESULTS_KEY);
		assertThat(stored).containsExactly(result);
		assertThat(captured.get()).isNotNull();
		assertThat(captured.get().results()).containsExactly(result);
		Counter count = registry.find("agentcore.evaluation.count")
			.tag("evaluator", "Builtin.Helpfulness")
			.tag("label", "Very Helpful")
			.counter();
		assertThat(count).isNotNull();
		assertThat(count.count()).isEqualTo(1.0);
	}

	@Test
	void shouldRecordErrorWhenResultHasErrorCode() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentCoreEvaluationMetrics metrics = new AgentCoreEvaluationMetrics(registry);

		EvaluationResult errored = new EvaluationResult("Builtin.Helpfulness", null, null, null, null, null,
				"AgentSpanMappingException");
		when(client.evaluateAll(anyList(), anyList())).thenReturn(List.of(errored));

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client)
			.async(false)
			.metrics(metrics)
			.build();

		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		when(chain.nextCall(any())).thenReturn(response("hello"));
		advisor.adviseCall(request("hi"), chain);

		Counter errors = registry.find("agentcore.evaluation.errors")
			.tag("evaluator", "Builtin.Helpfulness")
			.tag("error_code", "AgentSpanMappingException")
			.counter();
		assertThat(errors).isNotNull();
		assertThat(errors.count()).isEqualTo(1.0);
	}

	@Test
	void shouldAggregateStreamingChunksForEvaluation() {
		AtomicReference<List<?>> capturedSpans = new AtomicReference<>();
		when(client.evaluateAll(anyList(), anyList())).thenAnswer(inv -> {
			capturedSpans.set(inv.getArgument(1));
			return List.of();
		});

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).async(false).build();

		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(any())).thenReturn(Flux.just(response("Hello, "), response("world"), response("!")));

		List<ChatClientResponse> out = advisor.adviseStream(request("hi"), chain).collectList().block();

		assertThat(out).hasSize(3);
		assertThat(capturedSpans.get().toString()).contains("Hello, world!");
	}

	@Test
	void streamingShouldEmitChunksBeforeSourceCompletes() {
		when(client.evaluateAll(anyList(), anyList())).thenReturn(List.of());

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).async(false).build();

		Sinks.Many<ChatClientResponse> source = Sinks.many().unicast().onBackpressureBuffer();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(any())).thenReturn(source.asFlux());

		java.util.List<ChatClientResponse> received = new java.util.ArrayList<>();
		Disposable sub = advisor.adviseStream(request("hi"), chain).subscribe(received::add);

		source.tryEmitNext(response("one"));
		source.tryEmitNext(response("two"));
		// Source has NOT completed yet; downstream must already have both chunks.
		assertThat(received).hasSize(2);
		// Evaluation must not have fired yet.
		verify(client, never()).evaluateAll(anyList(), anyList());

		source.tryEmitComplete();
		sub.dispose();
		verify(client, times(1)).evaluateAll(anyList(), anyList());
	}

	@Test
	void streamingShouldNotEvaluateWhenSampleRateIsZero() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client)
			.async(false)
			.sampleRate(0.0)
			.build();

		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(any())).thenReturn(Flux.just(response("a"), response("b"), response("c")));

		advisor.adviseStream(request("hi"), chain).collectList().block();

		verify(client, never()).evaluateAll(anyList(), anyList());
	}

	@Test
	void streamingShouldSkipEvaluationOnCancellation() {
		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client).async(false).build();

		Sinks.Many<ChatClientResponse> source = Sinks.many().unicast().onBackpressureBuffer();
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		when(chain.nextStream(any())).thenReturn(source.asFlux());

		Disposable sub = advisor.adviseStream(request("hi"), chain).subscribe();
		source.tryEmitNext(response("partial"));
		sub.dispose();

		verify(client, never()).evaluateAll(anyList(), anyList());
	}

	@Test
	void asyncModeShouldRecordMetricsAfterHandlerReturns() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AgentCoreEvaluationMetrics metrics = new AgentCoreEvaluationMetrics(registry);
		EvaluationResult result = new EvaluationResult("Builtin.Helpfulness", 0.83, "Very Helpful", "ok", 100, 20,
				null);
		when(client.evaluateAll(anyList(), anyList())).thenReturn(List.of(result));

		AgentCoreEvaluationAdvisor advisor = AgentCoreEvaluationAdvisor.builder(client)
			.async(true)
			.metrics(metrics)
			.build();

		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		when(chain.nextCall(any())).thenReturn(response("Paris."));

		// Handler returns immediately; the metric is populated on another thread.
		advisor.adviseCall(request("hi"), chain);

		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			Counter counter = registry.find("agentcore.evaluation.count")
				.tag("evaluator", "Builtin.Helpfulness")
				.tag("label", "Very Helpful")
				.counter();
			assertThat(counter).isNotNull();
			assertThat(counter.count()).isEqualTo(1.0);
		});
	}

	private static ChatClientRequest request(String userText) {
		return ChatClientRequest.builder()
			.prompt(new Prompt(new UserMessage(userText)))
			.context(new HashMap<>())
			.build();
	}

	private static ChatClientResponse response(String assistantText) {
		ChatResponse chat = new ChatResponse(List.of(new Generation(new AssistantMessage(assistantText))));
		return ChatClientResponse.builder().chatResponse(chat).context(new HashMap<>()).build();
	}

}
