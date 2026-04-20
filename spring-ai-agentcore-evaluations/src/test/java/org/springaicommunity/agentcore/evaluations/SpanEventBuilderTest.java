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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpanEventBuilder}.
 *
 * <p>
 * Verifies the public contract required by the AgentCore Evaluate API: a span/event pair
 * carrying session metadata and the user/assistant payloads.
 *
 * @author Andrei Shakirin
 */
class SpanEventBuilderTest {

	private static final String TRACE_ID = "trace123";

	private static final String SESSION_ID = "session456";

	@Test
	void shouldProduceSpanAndEventLinkedByTraceAndSpanId() {
		List<Map<String, Object>> sessionSpans = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.promptEvent("What is the capital of France?")
			.completionEvent("Paris.")
			.buildSessionSpans();

		assertThat(sessionSpans).hasSize(2);
		Map<String, Object> span = sessionSpans.get(0);
		Map<String, Object> event = sessionSpans.get(1);

		assertThat(span.get("traceId")).isEqualTo(TRACE_ID);
		assertThat(event.get("traceId")).isEqualTo(span.get("traceId"));
		assertThat(event.get("spanId")).isEqualTo(span.get("spanId"));
	}

	@Test
	void spanShouldCarrySessionAndScope() {
		Map<String, Object> span = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.buildSessionSpans()
			.getFirst();

		assertThat(attributes(span)).containsEntry("session.id", SESSION_ID);
		assertThat(scope(span)).containsEntry("name", SpanEventBuilder.SCOPE_NAME);
	}

	@Test
	void eventShouldCarryPromptAndCompletionPayloads() {
		Map<String, Object> event = SpanEventBuilder.agentInvocation(TRACE_ID, SESSION_ID)
			.promptEvent("What is the capital of France?")
			.completionEvent("Paris.")
			.buildSessionSpans()
			.get(1);

		// The body shape is the Strands ADOT format: the plain prompt/completion text
		// must be findable anywhere in the serialized body (Strands wraps user content
		// as a JSON array and output as a message object).
		String bodyStr = event.get("body").toString();
		assertThat(bodyStr).contains("What is the capital of France?");
		assertThat(bodyStr).contains("Paris.");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> attributes(Map<String, Object> span) {
		return (Map<String, Object>) span.get("attributes");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> scope(Map<String, Object> span) {
		return (Map<String, Object>) span.get("scope");
	}

}
