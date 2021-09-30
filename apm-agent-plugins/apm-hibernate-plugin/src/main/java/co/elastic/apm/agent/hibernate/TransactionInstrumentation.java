/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2020 Elastic and contributors
 * %%
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
 * #L%
 */
package co.elastic.apm.agent.hibernate;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.is;

import java.io.Serializable;
import java.sql.Statement;

import javax.annotation.Nullable;
import javax.persistence.Entity;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.SharedSessionContract;
import org.hibernate.Transaction;
import org.hibernate.resource.transaction.spi.TransactionStatus;

import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.context.AbstractContext;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import static co.elastic.apm.agent.hibernate.helper.HibernateHelper.*;

/**
 * Creates spans for JDBC {@link Statement} execution
 */
public abstract class TransactionInstrumentation extends HibernateInstrumentation {
	private final ElementMatcher<? super MethodDescription> methodMatcher;

	TransactionInstrumentation(ElementMatcher<? super MethodDescription> methodMatcher) {
		this.methodMatcher = methodMatcher;
	}

	@Override
	public ElementMatcher<? super NamedElement> getTypeMatcherPreFilter() {
		return nameStartsWith("org.hibernate");
	}

	@Override
	public ElementMatcher<? super TypeDescription> getTypeMatcher() {
		Junction<TypeDescription> type = hasSuperType(named("org.hibernate.Transaction"));
		return not(isInterface()).and(type);
	}

	@Override
	public ElementMatcher<? super MethodDescription> getMethodMatcher() {
		return methodMatcher;
	}

	// @SuppressWarnings("DuplicatedCode")
	public static class BeginInstrumentation extends TransactionInstrumentation {

		public BeginInstrumentation(ElasticApmTracer tracer) {
			super(named("begin").and(takesArguments(0)).and(isPublic()));
		}

		@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
		public static void onAfterExecute(@Advice.This Object transaction, @Advice.Origin("#m") String methodName,
				@Advice.Thrown @Nullable Throwable t) {

			String str = "After :" + methodName;
			AbstractSpan<?> parentSpan = tracer.getActive();
			hh.logMsg(str, "methodName=" + methodName, "parentSpan=" + parentSpan, "throwable=" + t);
			if (parentSpan == null)
				return;
			Span span = tracer.getActiveSpan();
			hh.logMsg("tracer.getActiveSpan=" + span);
			// if( span == null ) return;
			if (t != null) {
				hh.logMsg("Exception capture error span.");
				if (span == null) {
					parentSpan.captureException(t);
					return;
				}
				span.captureException(t).deactivate().end();
				return;
			}

			hh.createHibernateSpan(parentSpan, HIB_SUB_TYPE + " " + HIB_SPANNAME_TRANSACTION, // SPAN Name
					HIB_SPAN_TYPE, HIB_SUB_TYPE, HIB_SPAN_ACTION_CREATE, null, null, methodName);
		}
	}

	/**
	 * Instruments:
	 * <ul>
	 * <li?{@link org.hibernate.SessionBuilder#openSession()</li> Gives more
	 * accurate details on open session. after this method "Session openSession()"
	 * execution Create a span for session open
	 * 
	 * </ul>
	 */
	// @SuppressWarnings("DuplicatedCode")
	public static class CommitInstrumentation extends TransactionInstrumentation {

		public CommitInstrumentation(ElasticApmTracer tracer) {
			super(named("commit").and(takesArguments(0)).and(isPublic()));
		}

		@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
		public static void onAfterExecute(@Advice.This Object transaction, @Advice.Origin("#m") String methodName,
				@Advice.Thrown @Nullable Throwable t) {
			hh.handleTransactionAfterExecute(transaction,methodName, tracer.getActive(), tracer.getActiveSpan(), t);
		}
	}

	// @SuppressWarnings("DuplicatedCode")
	// Not used this to be deleted.
	public static class RollbackInstrumentation extends TransactionInstrumentation {

		public RollbackInstrumentation(ElasticApmTracer tracer) {
			super(named("rollback").and(takesArguments(0)).and(isPublic()));
		}

		@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
		public static void onAfterExecute(@Advice.This Object transaction,@Advice.Origin("#m") String methodName,
				@Advice.Thrown @Nullable Throwable t) {
			hh.handleTransactionAfterExecute(transaction, methodName, tracer.getActive(), tracer.getActiveSpan(), t);
		}
	}

}
