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
package co.elastic.apm.agent.hazelcast;

import co.elastic.apm.agent.hazelcast.helper.HazelcastHelper;
import co.elastic.apm.agent.impl.GlobalTracer;
import co.elastic.apm.agent.impl.transaction.Span;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import javax.annotation.Nullable;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;

public class HazelcastLockInstrumentation extends HazelcastInstrumentation {

    public static final String instrumentedMethod = "getLock";

    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return not(isInterface()).and(hasSuperType(named("com.hazelcast.core.HazelcastInstance")));
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return named(instrumentedMethod)
            .and(isPublic())
            .and(takesArgument(0, is(String.class)));
    }

    public static class AdviceClass {

        private static final HazelcastHelper helper = new HazelcastHelper(GlobalTracer.get());

        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
        public static Object onEnter(@Advice.Argument(0) String name) {
            return helper.startSpan(instrumentedMethod + ": " + name);
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onExit(@Nullable @Advice.Enter Object spanObj, @Advice.Thrown Throwable thrown) {
            if (spanObj instanceof Span) {
                Span span = (Span) spanObj;
                span.deactivate()
                    .captureException(thrown)
                    .end();
            }
        }
    }
}
