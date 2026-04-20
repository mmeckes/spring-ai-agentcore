/*
 * Copyright 2025-2025 the original author or authors.
 */
package org.springaicommunity.agentcore.evaluations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;

/**
 * Live probe for the AgentCore Evaluate API. Enabled via
 * {@code AGENTCORE_EVAL_PROBE=true}.
 */
@EnabledIfEnvironmentVariable(named = "AGENTCORE_EVAL_PROBE", matches = "true")
class AgentCoreEvaluationClientProbeIT {

	private static final String USER = "What is the capital of France?";

	private static final String ASSISTANT = "The capital of France is Paris.";

	private static final String EVALUATOR_ID = System.getenv().getOrDefault("AGENTCORE_EVAL_ID", "Builtin.Helpfulness");

	private static final String REGION = System.getenv().getOrDefault("AGENTCORE_EVAL_REGION", "us-east-1");

	private final BedrockAgentCoreClient sdk = BedrockAgentCoreClient.builder().region(Region.of(REGION)).build();

	private final AgentCoreEvaluationClient client = new AgentCoreEvaluationClient(sdk);

	@Test
	void probe() {
		probe("B1-nested-content", body("content"));
		probe("B2-content-string", body("content-string"));
		probe("B3-content-text", body("content-text"));
		probe("B4-messages-flat", body("flat"));
		probe("B5-messages-content-parts-array", body("parts-array"));
		probe("B6-content-list-text-objs", body("list-text-objs"));
		probe("B7-input-output-as-strings", body("io-strings"));
		probe("B8-single-messages-list", body("single-messages-list"));
	}

	private Map<String, Object> body(String variant) {
		return switch (variant) {
			case "content-string" ->
				Map.of("input", Map.of("messages", List.of(Map.of("role", "user", "content", USER))), "output",
						Map.of("messages", List.of(Map.of("role", "assistant", "content", ASSISTANT))));
			case "content" -> Map.of("input",
					Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("content", USER)))), "output",
					Map.of("messages", List.of(Map.of("role", "assistant", "content", Map.of("content", ASSISTANT)))));
			case "content-text" -> Map.of("input",
					Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("text", USER)))), "output",
					Map.of("messages", List.of(Map.of("role", "assistant", "content", Map.of("text", ASSISTANT)))));
			case "flat" -> Map.of("messages", List.of(Map.of("role", "user", "content", USER),
					Map.of("role", "assistant", "content", ASSISTANT)));
			case "parts-array" -> Map.of("input",
					Map.of("messages",
							List.of(Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", USER))))),
					"output", Map.of("messages", List.of(Map.of("role", "assistant", "content",
							List.of(Map.of("type", "text", "text", ASSISTANT))))));
			case "list-text-objs" -> Map.of("input",
					Map.of("messages", List.of(Map.of("role", "user", "content", List.of(Map.of("text", USER))))),
					"output", Map.of("messages",
							List.of(Map.of("role", "assistant", "content", List.of(Map.of("text", ASSISTANT))))));
			case "io-strings" -> Map.of("input", USER, "output", ASSISTANT);
			case "single-messages-list" -> Map.of("input", List.of(Map.of("role", "user", "content", USER)), "output",
					List.of(Map.of("role", "assistant", "content", ASSISTANT)));
			default -> throw new IllegalArgumentException(variant);
		};
	}

	private void probe(String name, Map<String, Object> body) {
		String traceId = hex(16);
		String spanId = hex(8);
		long t = System.currentTimeMillis() * 1_000_000L;
		Map<String, Object> span = buildSpan(traceId, spanId, t);
		Map<String, Object> log = buildLog(traceId, spanId, t);
		log.put("body", body);
		try {
			List<EvaluationResult> r = client.evaluate(EVALUATOR_ID, List.of(span, log));
			EvaluationResult res = r.isEmpty() ? null : r.getFirst();
			System.out.printf("[%s] size=%d score=%s err=%s%n", name, r.size(), res == null ? null : res.score(),
					res == null ? null : res.errorCode());
		}
		catch (Exception ex) {
			System.out.printf("[%s] %s: %s%n", name, ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	private static Map<String, Object> buildSpan(String traceId, String spanId, long t) {
		Map<String, Object> s = new HashMap<>();
		s.put("resource", Map.of("attributes", Map.of()));
		s.put("scope", Map.of("name", "strands.telemetry.tracer", "version", ""));
		s.put("traceId", traceId);
		s.put("spanId", spanId);
		s.put("parentSpanId", null);
		s.put("flags", 1);
		s.put("name", "invoke_agent Strands Agents");
		s.put("kind", "INTERNAL");
		s.put("startTimeUnixNano", t);
		s.put("endTimeUnixNano", t + 1);
		s.put("durationNano", 1);
		Map<String, Object> attrs = new HashMap<>();
		attrs.put("gen_ai.operation.name", "invoke_agent");
		attrs.put("gen_ai.agent.name", "Strands Agents");
		attrs.put("gen_ai.system", "strands-agents");
		attrs.put("session.id", "probe-session");
		s.put("attributes", attrs);
		s.put("status", Map.of("code", "OK"));
		return s;
	}

	private static Map<String, Object> buildLog(String traceId, String spanId, long t) {
		Map<String, Object> l = new HashMap<>();
		l.put("resource", Map.of("attributes", Map.of()));
		l.put("scope", Map.of("name", "strands.telemetry.tracer"));
		l.put("traceId", traceId);
		l.put("spanId", spanId);
		l.put("flags", 1);
		l.put("timeUnixNano", t);
		l.put("observedTimeUnixNano", t + 100_000L);
		l.put("severityNumber", 9);
		l.put("severityText", "");
		l.put("attributes", Map.of("event.name", "strands.telemetry.tracer"));
		l.put("body", Map.of("input",
				Map.of("messages", List.of(Map.of("role", "user", "content", Map.of("content", USER)))), "output",
				Map.of("messages", List.of(Map.of("role", "assistant", "content", Map.of("content", ASSISTANT))))));
		return l;
	}

	private static String hex(int bytes) {
		byte[] b = new byte[bytes];
		new Random().nextBytes(b);
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

}
