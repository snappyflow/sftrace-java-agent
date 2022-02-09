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
package co.elastic.apm.agent.hibernate.helper;

import static co.elastic.apm.agent.hibernate.helper.HibernateGlobalState.objectSpanMap;

import javax.annotation.Nullable;

import org.hibernate.Transaction;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import antlr.debug.Tracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Span;

public class HibernateHelper {

	private static final Logger logger = LoggerFactory.getLogger(HibernateHelper.class);
	public static final String HIB_SPAN_TYPE = "orm";
	public static final String HIB_SUB_TYPE = "hibernate";
	
	public static final String HIB_SPAN_ACTION_CREATE = "create";
	public static final String HIB_SPAN_ACTION_QUERY = "query";
	public static final String HIB_SPAN_ACTION_LOAD = "load";
	public static final String HIB_SPANNAME_ACTION_EXEC = "execute";
	
	public static final String HIB_SPANNAME_SESSION = "session";
	public static final String HIB_SPANNAME_TRANSACTION = "transaction";
	public static final String HIB_SPANNAME_ERROR = "error";
	
	
	/**
	 * Maps the provided Span with the session object.
	 * @param obj
	 * @param span
	 * @param methodName
	 */
	public void mapObjectSpan(Object obj, Span span, String methodName) {
		objectSpanMap.putIfAbsent(obj, span);
	}
	
	/**
	 * Returns the span associated to session
	 * @param obj
	 * @return
	 */
	public Span getSpanForObject(Object obj) {
		return objectSpanMap.get(obj);
	}
	
	/**
	 * Removes Span for the session soon after the session closes.
	 * @param obj
	 * @return
	 */
	public Span removeObjectSpan(Object obj) {
		return objectSpanMap.remove(obj);
	}
	
	/**
	 * To avoid duplicate sessions
	 * @param obj
	 * @return
	 */
	
	public boolean isObjectAlreadyCreated(Object obj) {
		return objectSpanMap.get(obj) != null;
	}

	

	public void logMsg(Object... objs) {
		for (Object obj : objs) {
			logger.debug("HIBERNATE: {}", obj);
		}
	}

	/**
	 * It creates a span 
	 * @param parent
	 * @param spanName
	 * @param spanType
	 * @param subType
	 * @param action
	 * @param object
	 * @param entity
	 * @param methodName
	 * @return
	 */
	@Nullable
	public Span createHibernateSpan(@Nullable AbstractSpan<?> parent, 
			String spanName, String spanType, String subType, String action,
			Object object, @Nullable Object entity, String methodName ) {
		
		String entityName = null;
		if ( entity !=null )
			entityName = (entity instanceof String) ? (String)entity :entity.getClass().getSimpleName();
		
		logMsg("Context Info:methodName,entityName, entity, session, parent.",
				methodName,entityName, entity, object, parent);
		
		if ( parent == null ) {
			logMsg("Null Parent Span...");
			return null;
		}
		
		Span span = parent.createSpan().activate();
		span.withName(spanName,Span.PRIO_METHOD_SIGNATURE);
		if (span.isSampled()) {
			logMsg("Span is sampled:",span);
			StringBuilder sn = span.getAndOverrideName(AbstractSpan.PRIO_DEFAULT);
			logMsg("SpanName=" + sn);
			
		}
		
		span.withType(spanType);
		span.withSubtype(subType);
		span.withAction(action);

		logMsg("SPAN=",span);
		//So that It will not create a duplicate Span, even if this call again and again.
		if( object != null)
			mapObjectSpan(object, span, methodName);
		return span;
	}
	
	public boolean isItTransactionSpan(Span span) {
		
		String name = span.getNameAsString();
		logMsg("SpanName is:",name);
		if( name == null) return false;
		return name.contains(HIB_SPANNAME_TRANSACTION);
	}
	
	public void handleTransactionAfterExecute(Object transaction, String methodName,AbstractSpan<?> parentSpan, Span span, Throwable t) {
		
		String str = "After :" + methodName;
		logMsg(str, "methodName=" + methodName, "parentSpan=" + parentSpan, "throwable=" + t);
		if (parentSpan == null)
			return;
		logMsg("tracer.getActiveSpan=" + span);
		
		if (transaction instanceof org.hibernate.engine.transaction.internal.TransactionImpl) {
			Transaction tr =  (org.hibernate.engine.transaction.internal.TransactionImpl)transaction;
			TransactionStatus trStatus = tr.getStatus();
			logMsg(trStatus);
		}
		
		if (span == null) {// Should not happen this.
			logMsg("Span shouldn't be null here");
			return;
		}
		
		
		try {
			if (t != null) {
				logMsg("Exception capture error span={}", span);
				span.captureException(t);
			}
		} finally {
			if( isItTransactionSpan(span) )
				span.deactivate().end();
		}
	}

}
