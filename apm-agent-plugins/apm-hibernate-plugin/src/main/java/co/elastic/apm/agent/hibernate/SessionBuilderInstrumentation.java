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
package co.elastic.apm.agent.hibernate;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import javax.annotation.Nullable;

import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.transaction.AbstractSpan;
import co.elastic.apm.agent.impl.transaction.Span;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import static co.elastic.apm.agent.hibernate.helper.HibernateHelper.*;

/**
 * Creates spans for Hibernate {@link SessionBuilder} execution
 */
public abstract class SessionBuilderInstrumentation extends HibernateInstrumentation {

    private final ElementMatcher<? super MethodDescription> methodMatcher;

    SessionBuilderInstrumentation(ElementMatcher<? super MethodDescription> methodMatcher) {
        this.methodMatcher = methodMatcher;
    }

    @Override
    public ElementMatcher<? super NamedElement> getTypeMatcherPreFilter() {
        return nameStartsWith("org.hibernate");
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
    	
    	Junction<TypeDescription> type = hasSuperType(named("org.hibernate.SessionBuilder"));
        return not(isInterface()).and(type);
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return methodMatcher;
    }
    
    /**
     * Instruments:
     * <ul>
     *     <li?{@link org.hibernate.SessionBuilder#openSession()</li>
     *     Gives more accurate details on open session.
     *     after this method "Session openSession()" execution
     *     Create a span for session open
    
     * </ul>
     */
    //@SuppressWarnings("DuplicatedCode")
    public static class SessionOpenInstrumentation extends SessionBuilderInstrumentation {

        public SessionOpenInstrumentation(ElasticApmTracer tracer) {
            super(
            		named("openSession")
            			.and(returns(named("org.hibernate.Session")))
            			.and(isPublic())
            );
        }


        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class, inline = false)
        public static void onAfterExecute(@Advice.This Object sessionBuilder, 
        								  @Advice.Origin("#m") String methodName,
                                          @Advice.Thrown @Nullable Throwable t,
                                          @Advice.Return Object session) {
        	  
        	String str = "After :"+methodName;
        	AbstractSpan<?> parentSpan = tracer.getActive();
        	hh.logMsg(str,"methodName="+methodName,"sessionBuilder="+sessionBuilder,"session="+session,
        								"parentSpan="+parentSpan,"throwable="+t);
        	if ( parentSpan == null ) return;
        	
        	Span span = tracer.getActiveSpan();
        	hh.logMsg("tracer.getActiveSpan="+span);
        	
        	if( t != null ) {
        		hh.logMsg("Exception capture error span={}",span);
        		if( span != null)
        		span.captureException(t).deactivate().end();
        		return;
        	}
        	
        	if( hh.isObjectAlreadyCreated(session) ) {
        		hh.logMsg("Session already created so don't create span again.");
        	}
        	else {
        		hh.createHibernateSpan(tracer.getActive(),
        				HIB_SUB_TYPE+" "+HIB_SPANNAME_SESSION,//SPAN Name
                		HIB_SPAN_TYPE,HIB_SUB_TYPE,HIB_SPAN_ACTION_CREATE,
                		session,null,methodName);
        	}
        }

    }
   
}
