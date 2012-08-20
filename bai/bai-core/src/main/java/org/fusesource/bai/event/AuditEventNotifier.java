/*
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.bai.event;

import java.util.Arrays;
import java.util.EventObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Producer;
import org.apache.camel.management.PublishEventNotifier;
import org.apache.camel.management.event.AbstractExchangeEvent;
import org.apache.camel.management.event.ExchangeCompletedEvent;
import org.apache.camel.management.event.ExchangeCreatedEvent;
import org.apache.camel.management.event.ExchangeFailedEvent;
import org.apache.camel.management.event.ExchangeRedeliveryEvent;
import org.apache.camel.management.event.ExchangeSendingEvent;
import org.apache.camel.management.event.ExchangeSentEvent;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ServiceHelper;
import org.apache.camel.util.URISupport;
import org.fusesource.bai.AuditConstants;

/**
 * { _id: <breadcrumbId>,
     exchanges: [
     { timestamp: <timestamp>,
        endpointUri: <uri>,
        in: <inMessage>,
        out: <outMessage>
     },
     { timestamp: <timestamp>,
        endpointUri: <uri>,
        in: <inMessage>,
        out: <outMessage>
     },
     { timestamp: <timestamp>,
        endpointUri: <uri>,
        in: <inMessage>,
        out: <outMessage>
     }
   ],
   
   failures: [
     { timestamp: <timestamp>,
       error: <exception and message>
     }
   ],
   
   redeliveries: 
     { endpoint: [timestamps],
       endpoint: [timestamps]
     }
   ],
   
   
}
 * @author raul
 *
 */
public class AuditEventNotifier extends PublishEventNotifier {

    // by default accept all
	private List<String> inRegex = Arrays.asList(".*");
	private List<String> outRegex = Arrays.asList(".*");
	private List<String> failureRegex = Arrays.asList(".*");
	private List<String> redeliveryRegex = Arrays.asList(".*");
	
    private CamelContext camelContext;
    private Endpoint endpoint;
    private String endpointUri;
    private Producer producer;

	public AuditEventNotifier() {
		setIgnoreCamelContextEvents(true);
		setIgnoreRouteEvents(true);
		setIgnoreServiceEvents(true);
	}
	
	@Override
	public boolean isEnabled(EventObject event) {
        EventObject coreEvent = event;
        if (event instanceof AuditEvent) {
            AuditEvent auditEvent = (AuditEvent) event;
            coreEvent = auditEvent.event;
        }
        List<String> compareWith = null;
        if (coreEvent instanceof ExchangeSendingEvent || coreEvent instanceof ExchangeCreatedEvent) {
            compareWith = inRegex;
        } else if (coreEvent instanceof ExchangeSentEvent || coreEvent instanceof ExchangeCompletedEvent) {
            compareWith = outRegex;
        } else if (coreEvent instanceof ExchangeRedeliveryEvent) {
            compareWith = redeliveryRegex;
        }
        // logic if it's a failure is different; we compare against Exception
        else if (coreEvent instanceof ExchangeFailedEvent) {
            ExchangeFailedEvent failedEvent = (ExchangeFailedEvent) coreEvent;
            String exceptionClassName = failedEvent.getExchange().getException().getClass().getCanonicalName();
            return testRegexps(exceptionClassName, failureRegex);
        }
        // TODO: Failure handled
        String uri = endpointUri(event);
        return uri == null || compareWith == null ? false : testRegexps(uri, compareWith);

    }

    private String endpointUri(EventObject event) {
        if (event instanceof AuditEvent) {
            AuditEvent auditEvent = (AuditEvent) event;
            return auditEvent.endpointURI;
        } else if (event instanceof AbstractExchangeEvent) {
            AbstractExchangeEvent ae = (AbstractExchangeEvent) event;
            Endpoint endpoint = ae.getExchange().getFromEndpoint();
            if (endpoint != null) {
                return endpoint.getEndpointUri();
            }
        }
        return null;
    }

    private boolean testRegexps(String endpointURI, List<String> regexps) {
        // if the endpoint URI is null, we have an event that is not related to an endpoint, e.g. a failure in a processor; audit it
        if (endpointURI == null) {
            return true;
        }
		for (String regex : regexps) {
			if (endpointURI.matches(regex)) {
				return true;
			}
		}
		return false;
	}
	
    /**
     * Add a unique dispatchId property to the original Exchange, which will come back to us later.
     * Camel does not correlate the individual sends/dispatches of the same exchange to the same endpoint, e.g.
     * Exchange X sent to http://localhost:8080, again sent to http://localhost:8080... When both happen in parallel, and are marked in_progress in BAI, when the Sent or Completed
     * events arrive, BAI won't know which record to update (ambiguity)
     * So to overcome this situation, we enrich the Exchange with a DispatchID only when Created or Sending
     */
	@Override
    public void notify(EventObject event) throws Exception {
        AuditEvent auditEvent = null;
        AbstractExchangeEvent ae = null;
        if (event instanceof AuditEvent) {
            auditEvent = (AuditEvent) event;
            ae = auditEvent.event;
        } else if (event instanceof AbstractExchangeEvent) {
            ae = (AbstractExchangeEvent) event;
            auditEvent = new AuditEvent(ae.getExchange(), ae);
        }

        if (ae == null || auditEvent == null) {
            log.debug("Ignoring events like " + event + " as its neither a AbstractExchangeEvent or AuditEvent");
            return;
        }
	    if (event instanceof ExchangeSendingEvent || event instanceof ExchangeCreatedEvent) {
	        ae.getExchange().setProperty(AuditConstants.DISPATCH_ID, ae.getExchange().getContext().getUuidGenerator().generateUuid());
	    }
	    
	    // only notify when we are started
        if (!isStarted()) {
            log.debug("Cannot publish event as notifier is not started: {}", event);
            return;
        }

        // only notify when camel context is running
        if (!camelContext.getStatus().isStarted()) {
            log.debug("Cannot publish event as CamelContext is not started: {}", event);
            return;
        }

        Exchange exchange = producer.createExchange();
        exchange.getIn().setBody(auditEvent);

        // make sure we don't send out events for this as well
        // mark exchange as being published to event, to prevent creating new events
        // for this as well (causing a endless flood of events)
        exchange.setProperty(Exchange.NOTIFY_EVENT, Boolean.TRUE);
        try {
            producer.process(exchange);
        } finally {
            // and remove it when its done
            exchange.removeProperty(Exchange.NOTIFY_EVENT);
        }
    }

    /**
	 * Substitute all arrays with CopyOnWriteArrayLists
	 */
	@Override
	protected void doStart() throws Exception {
		inRegex = new CopyOnWriteArrayList<String>(inRegex);
		outRegex = new CopyOnWriteArrayList<String>(outRegex);
		failureRegex = new CopyOnWriteArrayList<String>(failureRegex);
		redeliveryRegex = new CopyOnWriteArrayList<String>(redeliveryRegex);
	    
		ObjectHelper.notNull(camelContext, "camelContext", this);
        if (endpoint == null && endpointUri == null) {
            throw new IllegalArgumentException("Either endpoint or endpointUri must be configured");
        }

        if (endpoint == null) {
            endpoint = camelContext.getEndpoint(endpointUri);
        }

        producer = endpoint.createProducer();
        ServiceHelper.startService(producer);

	}

	public List<String> getInRegex() {
		return inRegex;
	}

	public void setInRegex(List<String> inRegex) {
		this.inRegex = inRegex;
	}

	public List<String> getOutRegex() {
		return outRegex;
	}

	public void setOutRegex(List<String> outRegex) {
		this.outRegex = outRegex;
	}

	public List<String> getFailureRegex() {
		return failureRegex;
	}

	public void setFailureRegex(List<String> failureRegex) {
		this.failureRegex = failureRegex;
	}

	public List<String> getRedeliveryRegex() {
		return redeliveryRegex;
	}

	public void setRedeliveryRegex(List<String> redeliveryRegex) {
		this.redeliveryRegex = redeliveryRegex;
	}
	
	public CamelContext getCamelContext() {
        return camelContext;
    }

    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public String getEndpointUri() {
        return endpointUri;
    }

    public void setEndpointUri(String endpointUri) {
        this.endpointUri = endpointUri;
    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopService(producer);
    }

    @Override
    public String toString() {
        return "PublishEventNotifier[" + (endpoint != null ? endpoint : URISupport.sanitizeUri(endpointUri)) + "]";
    }

}