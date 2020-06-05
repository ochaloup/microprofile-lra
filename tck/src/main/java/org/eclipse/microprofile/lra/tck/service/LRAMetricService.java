/*
 *******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.eclipse.microprofile.lra.tck.service;

import org.eclipse.microprofile.lra.annotation.LRAStatus;

import javax.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Metric service is a storage container that test beans may use to store
 * data about processing.
 * It stores number of call types (defined by {@link LRAMetric}) per LRA id per participant.
 */
@ApplicationScoped
public class LRAMetricService {

    private Map<URI, Map<String, LRAMetric>> metricsPerLra = new HashMap<>();

    /**
     * It increments counter of the metric type for particular LRA id and particular participant class
     * which is translated to fully qualified class name as String.
     */
    public void incrementMetric(LRAMetricType name, URI lraId, Class<?> participantClazz) {
        incrementMetric(name, lraId, participantClazz.getName());
    }

    /**
     * It increments counter of the metric type for particular LRA id and particular participant's name.
     */
    public void incrementMetric(LRAMetricType name, URI lraId, String participant) {
        metricsPerLra.putIfAbsent(lraId, new HashMap<>());
        metricsPerLra.get(lraId).putIfAbsent(participant, new LRAMetric());
        metricsPerLra.get(lraId).get(participant).increment(name);
    }

    /**
     * Returns count number for particular metric type regardless of the LRA id or the participant's name.
     */
    public int getMetric(LRAMetricType name) {
        AtomicInteger result = new AtomicInteger();

        metricsPerLra.values().forEach(participantMap ->
            participantMap.values().forEach(metric -> result.addAndGet(metric.get(name))));

        return result.get();
    }

    /**
     * Returns count number for particular metric type filtered by LRA id and the participant's name.
     */
    public int getMetric(LRAMetricType metric, URI lraId, String participant) {
        if (metricsPerLra.containsKey(lraId) && metricsPerLra.get(lraId).containsKey(participant)) {
            return metricsPerLra.get(lraId).get(participant).get(metric);
        } else {
            return 0;
        }
    }

    /**
     * Clear the metric storage as whole.
     */
    public void clear() {
        metricsPerLra.clear();
    }

    /**
     * TODO if the current PR is acceptable then delete the old isLRAFinished method
     * (which tests whether an LRA is active by making an attempt to enlist with it
     * which some spec implementations report as a stack trace WARNING if the LRA is
     * no longer active).
     *
     * @param lraId the LRA id to test
     * @param resourceName name of the resource that the metrics parameter applies to
     * @return whether or not an LRA has finished
     */
    boolean isLRAFinished(URI lraId, String resourceName) {
        LRAStatus metricStatus = null;
        if (getMetric(LRAMetricType.Closed, lraId, resourceName) >= 1) {
            metricStatus = LRAStatus.Closed;
        } else if (getMetric(LRAMetricType.FailedToClose, lraId, resourceName) >= 1) {
            metricStatus = LRAStatus.FailedToClose;
        } else if (getMetric(LRAMetricType.Cancelled, lraId, resourceName) >= 1) {
            metricStatus = LRAStatus.Cancelled;
        } else if (getMetric(LRAMetricType.FailedToCancel, lraId, resourceName) >= 1) {
            metricStatus = LRAStatus.FailedToCancel;
        }
        return metricStatus != null;
    }

    /**
     * A class to hold all of the metrics gathered in the context of a single LRA.
     * We need stats per LRA since a misbehaving test may leave an LRA in need of
     * recovery which means that the compensate/complete call will continue to be
     * called when subsequent tests run - ie it is not possible to fully tear down
     * a failing test.
     */
    private static class LRAMetric {
        private Map<LRAMetricType, AtomicInteger> metrics = Arrays.stream(LRAMetricType.values())
            .collect(Collectors.toMap(Function.identity(), t -> new AtomicInteger(0)));

        void increment(LRAMetricType metric) {
            if (metrics.containsKey(metric)) {
                metrics.get(metric).incrementAndGet();
            } else {
                throw new IllegalStateException("Cannot increment metric type " + metric.name());
            }
        }

        int get(LRAMetricType metric) {
            if (metrics.containsKey(metric)) {
                return metrics.get(metric).get();
            }

            return -1;
        }
    }

}
