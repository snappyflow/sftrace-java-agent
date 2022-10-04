/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.apm.agent.hazelcast.helper;
import co.elastic.apm.agent.impl.Tracer;
import javax.annotation.Nullable;
import co.elastic.apm.agent.impl.GlobalTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Span;

public class HazelcastHelper {
	private static final Logger logger = LoggerFactory.getLogger(HazelcastHelper.class);

    private final Tracer tracer;

    public HazelcastHelper(Tracer tracer) {
        this.tracer = tracer;
    }

    @Nullable
    public Span startSpan(String command) {
        AbstractSpan<?> activeSpan = GlobalTracer.get().getActive();
        if (activeSpan == null) {
            return null;
        }

        Span span = activeSpan.createExitSpan();
        if (span == null) {
            return null;
        }

        span.withName(command)
            .withType("db")
            .withSubtype("hazelcast")
            .withAction("query");
        span.getContext().getServiceTarget()
            .withType("hazelcast");
        return span.activate();
    }

}
