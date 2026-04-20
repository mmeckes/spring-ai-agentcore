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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AgentCore Evaluations.
 *
 * @param enabled whether evaluation is enabled (default: false, opt-in)
 * @param region AWS region for the Evaluate API
 * @param evaluatorIds list of evaluator IDs to run (e.g., "Builtin.Helpfulness")
 * @param async whether to run evaluations asynchronously (default: true). Boxed so that
 * {@code null} means "not set" and we apply the intended default; a primitive
 * {@code boolean} would silently default to {@code false} when the property is omitted
 * from the environment.
 * @param metricsEnabled whether to publish Micrometer metrics (default: true)
 * @param sampleRate sampling rate for evaluations (0.0-1.0, default: 1.0 = evaluate all).
 * Boxed so that {@code null} means "not set" and we apply the intended default; a
 * primitive {@code double} would silently default to {@code 0.0} (skip everything).
 */
@ConfigurationProperties(AgentCoreEvaluationProperties.CONFIG_PREFIX)
public record AgentCoreEvaluationProperties(boolean enabled, String region, List<String> evaluatorIds, Boolean async,
		Boolean metricsEnabled, Double sampleRate) {

	public static final String CONFIG_PREFIX = "spring.ai.agentcore.evaluations";

	public static final List<String> DEFAULT_EVALUATOR_IDS = List.of("Builtin.Helpfulness");

	public AgentCoreEvaluationProperties {
		if (evaluatorIds == null || evaluatorIds.isEmpty()) {
			evaluatorIds = DEFAULT_EVALUATOR_IDS;
		}
		if (async == null) {
			async = Boolean.TRUE;
		}
		if (metricsEnabled == null) {
			metricsEnabled = Boolean.TRUE;
		}
		if (sampleRate == null || sampleRate < 0.0 || sampleRate > 1.0) {
			sampleRate = 1.0;
		}
	}

}
