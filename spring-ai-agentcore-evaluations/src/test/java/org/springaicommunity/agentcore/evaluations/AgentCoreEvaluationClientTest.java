/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */

package org.springaicommunity.agentcore.evaluations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationResultContent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentCoreEvaluationClient}. Focuses on contract-level behaviour: span
 * serialisation preserves nested structure and null handling, and API errors are carried
 * through to the result model.
 */
class AgentCoreEvaluationClientTest {

	@Test
	void convertsNestedSpansAndNullsToDocumentFaithfully() {
		BedrockAgentCoreClient sdk = mock(BedrockAgentCoreClient.class);
		when(sdk.evaluate(any(EvaluateRequest.class))).thenReturn(EvaluateResponse.builder().build());

		Map<String, Object> span = new HashMap<>();
		span.put("traceId", "t1");
		span.put("flags", 1);
		span.put("parentSpanId", null);
		span.put("attributes", Map.of("list", List.of(1, "two", 3.5)));

		new AgentCoreEvaluationClient(sdk).evaluate("Builtin.Helpfulness", List.of(span));

		ArgumentCaptor<EvaluateRequest> captor = ArgumentCaptor.forClass(EvaluateRequest.class);
		org.mockito.Mockito.verify(sdk).evaluate(captor.capture());
		List<Document> sent = captor.getValue().evaluationInput().sessionSpans();

		assertThat(sent).hasSize(1);
		Map<String, Document> root = sent.getFirst().asMap();
		assertThat(root.get("traceId").asString()).isEqualTo("t1");
		assertThat(root.get("flags").asNumber().intValue()).isEqualTo(1);
		assertThat(root.get("parentSpanId").isNull()).isTrue();
		List<Document> list = root.get("attributes").asMap().get("list").asList();
		assertThat(list.get(0).asNumber().intValue()).isEqualTo(1);
		assertThat(list.get(1).asString()).isEqualTo("two");
		assertThat(list.get(2).asNumber().doubleValue()).isEqualTo(3.5);
	}

	@Test
	void propagatesErrorCodeAndErrorMessageFromSdk() {
		BedrockAgentCoreClient sdk = mock(BedrockAgentCoreClient.class);
		EvaluationResultContent errored = EvaluationResultContent.builder()
			.evaluatorId("Builtin.Helpfulness")
			.errorCode("AgentSpanMappingException")
			.errorMessage("bad span")
			.build();
		when(sdk.evaluate(any(EvaluateRequest.class)))
			.thenReturn(EvaluateResponse.builder().evaluationResults(errored).build());

		List<EvaluationResult> results = new AgentCoreEvaluationClient(sdk).evaluate("Builtin.Helpfulness",
				List.of(Map.of("k", "v")));

		assertThat(results).hasSize(1);
		EvaluationResult r = results.getFirst();
		assertThat(r.isError()).isTrue();
		assertThat(r.errorCode()).isEqualTo("AgentSpanMappingException");
		assertThat(r.errorMessage()).isEqualTo("bad span");
	}

}
