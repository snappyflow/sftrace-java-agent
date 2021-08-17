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

import java.io.Serializable;
import java.sql.Statement;

import javax.annotation.Nullable;
import javax.persistence.Entity;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.SharedSessionContract;
import org.hibernate.Transaction;

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

/**
 * Creates spans for Hibernate {@link SharedSessionContract} execution
 */
public abstract class SessionInstrumentation extends HibernateInstrumentation {

    private final ElementMatcher<? super MethodDescription> methodMatcher;

    SessionInstrumentation(ElementMatcher<? super MethodDescription> methodMatcher) {
        this.methodMatcher = methodMatcher;
    }

    @Override
    public ElementMatcher<? super NamedElement> getTypeMatcherPreFilter() {
        return nameStartsWith("org.hibernate");
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
    	
    	Junction<TypeDescription> supTypes = hasSuperType(named("org.hibernate.SharedSessionContract"));
        return not(isInterface()).and(supTypes);
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return methodMatcher;
    }
	
    /**
     * Instruments:
     * <ul>
     *     <li>{@link Session#get(String, Serializable)} </li>
     *     <li>{@link Session#get(String, Serializable, LockMode)} </li>
     *     <li>{@link Session#get(String, Serializable, LockOptions)} </li>
     * </ul>
     */
    //@SuppressWarnings("DuplicatedCode")
    public static class ReadObjectsInstrumentation extends SessionInstrumentation {

        public ReadObjectsInstrumentation(ElasticApmTracer tracer) {
            super(
                named("get")
                	.and(takesArgument(0, String.class))
                    .and(isPublic())
            );
        }

        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
        public static Object onBeforeExecute(@Advice.This SharedSessionContract session, 
        									 @Advice.Origin("#m") String methodName,
                                             @Advice.Argument(0) String entityName) {

            return hh.createHibernateSpan(tracer.getActive(),methodName+" "+entityName,
            		hh.HIB_SPAN_TYPE,hh.HIB_SPAN_TYPE,hh.HIB_SPAN_ACTION_QUERY,//Use subtype for action as well.
            		session,entityName,methodName);
        }


        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onAfterExecute(@Advice.This SharedSessionContract session,
                                          @Advice.Enter @Nullable Object span,
                                          @Advice.Thrown @Nullable Throwable t) {
        	hh.logMsg("After Executing GET Method:", "session="+session,"Span="+span,
        			"throwable"+t);
            if (span == null) {
                return;
            }

            ((Span) span).captureException(t)
                .deactivate()
                .end();
        }
    }
    
    /**
     * Instruments: CUD(Create, Update, Delete) operations
     * <ul>
     *     <li>{@link Session#get(String, Serializable)} </li>
     *     <li>{@link Session#get(String, Serializable, LockMode)} </li>
     *     <li>{@link Session#get(String, Serializable, LockOptions)} </li>
     *     
     *     "save",
                    "replicate",
                    "saveOrUpdate",
                    "update",
                    "merge",
                    "persist",
                    "lock",
                    "refresh",
                    "insert",
                    "delete",
     * </ul>
     */
    //@SuppressWarnings("DuplicatedCode")
    public static class CUDInstrumentation extends SessionInstrumentation {

        public CUDInstrumentation(ElasticApmTracer tracer) {
            super(
                named("save").or(named("saveOrUpdate")).or(named("update"))
                	.or(named("merge")).or(named("persist")).or(named("refresh"))
                	.or(named("delete"))
                	.and(takesArgument(0, Object.class))
                    .and(isPublic())
            );
        }

        @Nullable
        @Advice.OnMethodEnter(suppress = Throwable.class, inline = false)
        public static Object onBeforeExecute(@Advice.This Object session, @Advice.Origin("#m") String methodName,
                                             @Advice.Argument(0) Object entityObject) {
        	
        	String entityName = entityObject.getClass().getSimpleName();
        	return hh.createHibernateSpan(tracer.getActive(),methodName+" "+entityName,
        			hh.HIB_SPAN_TYPE,hh.HIB_SPAN_TYPE,hh.HIB_SPAN_ACTION_QUERY,//Use subtype for action as well.
            		session,entityObject,methodName);
        }


        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onAfterExecute(@Advice.This Object session,
                                          @Advice.Enter @Nullable Object span,
                                          @Advice.Thrown @Nullable Throwable t) {
        	
        	hh.logMsg("After Executing CUD methods:", "session="+session,"Span="+span,
        								"throwable="+t);
            if (span == null) {
                return;
            }

            try {
        		//Exception while closing the session
        		if( t !=null ) ((Span) span).captureException(t);
        	
        	}finally {
        		((Span) span).deactivate().end();
        	}
        }
    }
    
    /**
     * Instruments:
     * <ul>
     *     <li>{@link Session#beginTransaction())} </li>  
     *     <li?{@link org.hibernate.SessionBuilder#openSession()</li>
     *     Gives more accurate details on open session.
     *     after this method "Session openSession()" execution
     *     Create a span for session open
    
     * </ul>
     */
    //@SuppressWarnings("DuplicatedCode")
    public static class SessionOpenInstrumentation extends SessionInstrumentation {

        public SessionOpenInstrumentation(ElasticApmTracer tracer) {
            super(
            		named("beginTransaction").or(named("getTransaction"))
            			.and(returns(named("org.hibernate.Transaction")))
            			.and(isPublic())
            );
        }


        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onAfterExecute(@Advice.This SharedSessionContract session, 
        								  @Advice.Origin("#m") String methodName,
                                          @Advice.Thrown @Nullable Throwable t,
                                          @Advice.Return Transaction transaction) {
        	  
        	String str = "After :"+methodName;
        	AbstractSpan<?> parentSpan = tracer.getActive();
        	hh.logMsg(str,"methodName="+methodName,"session="+session,
        								"transaction="+transaction,"parentSpan="+parentSpan,"throwable="+t);
        	if ( parentSpan == null ) return;
        	
        	TraceContext tc = parentSpan.getTraceContext();
        	AbstractContext absCtx = parentSpan.getContext();
        	hh.logMsg(str,"TraceContext="+tc,"abstractContext="+absCtx);
        	
        	Span span = tracer.getActiveSpan();
        	hh.logMsg("tracer.getActiveSpan="+span);
        	
        	if( t != null ) {
        		hh.logMsg("Exception capture error span:");
        		span.captureException(t).deactivate().end();
        		return;
        	}
        	
        	if( hh.isObjectAlreadyCreated(session) ) {
        		hh.logMsg("Session already created so don't create span again.");
        	}
        	else {
        		hh.createHibernateSpan(tracer.getActive(),
        				hh.HIB_SPAN_TYPE+" "+hh.HIB_SPANNAME_SESSION,//SPAN Name
                		hh.HIB_SPAN_TYPE,hh.HIB_SUB_TYPE,hh.HIB_SPAN_ACTION_CREATE,
                		session,null,methodName);
        	}
        }
    }
    
    /**
     * Instruments:
     * <ul>
     *     <li>{@link Session#close())} </li>    
    
     * </ul>
     */
    //@SuppressWarnings("DuplicatedCode")
    public static class SessionCloseInstrumentation extends SessionInstrumentation {

        public SessionCloseInstrumentation(ElasticApmTracer tracer) {
            super(
                named("close").and(takesArguments(0))
                    .and(isPublic())
            );
        }


        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onSessionClose(@Advice.This SharedSessionContract session,
                                         // @Advice.Enter @Nullable Object span,
                                          @Advice.Thrown @Nullable Throwable t) {
            
        	AbstractSpan<?> parentSpan = tracer.getActive();
        	hh.logMsg("onSessionClose:","parentSpan=",parentSpan,"session=",session,"throwable=",t);
        	
        	org.hibernate.internal.SessionImpl si = (org.hibernate.internal.SessionImpl)session;
        	hh.logMsg("Session Identifier:",si.getSessionIdentifier().toString(),"Session IdentityHashcode:",System.identityHashCode(session));
        	
        	Span spanFromCache = hh.getSpanForObject(session);
        	hh.logMsg("spanFromCache = {}",spanFromCache);
        	if (  spanFromCache == null) {
        		//No Span for this Session in the cache.
        		// Close without session is not possible. Meaning it is duplicate call. 
        		// Span is already close. should be ignored.
        		hh.logMsg("Span might have already closed. No Action required.");
        		return;
        	}
        	if (parentSpan == null) {
                return;
            }
        	//Just for information.
        	Span span = tracer.getActiveSpan();
        	hh.logMsg("Current span {}",span);
        	
        	try {
        		
        		//Exception while closing the session
        		if( t !=null ) {
        			spanFromCache.captureException(t);
        		}
        	
        	}finally {
        		//Since Session is closed 
        		//Remove it from the cache && DeActivate and End the span.
        		hh.logMsg("Name of the span getting closed ",spanFromCache.toString());
        		
        		spanFromCache.deactivate().end();
        		hh.removeObjectSpan(session);
        	}

        }
    }
}
