/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.developers.msa.hola;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;

import org.apache.deltaspike.core.api.config.ConfigResolver;

import feign.Logger;
import feign.Logger.Level;
import feign.hystrix.HystrixFeign;
import feign.jackson.JacksonDecoder;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.swagger.annotations.ApiOperation;

@Path("/")
public class HolaResource {

	@Inject
	private Tracer tracer;

    @GET
    @Path("/hola")
    @Produces("text/plain")
    @ApiOperation("Returns the greeting in Spanish")
    public String hola(@Context HttpHeaders headers) {
    	SpanContext spanCtx = tracer.extract(Format.Builtin.TEXT_MAP,
                new HttpHeadersExtractAdapter(headers.getRequestHeaders()));

        try (Span serverSpan = tracer.buildSpan("GET")
                .asChildOf(spanCtx)
                .withTag("http.url", "/api/hola")
                .start()) {
	        return hola();
        }
    }

    protected String hola() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");
        String translation = ConfigResolver
            .resolve("hello")
            .withDefault("Hola de %s")
            .logChanges(true)
            // 5 Seconds cache only for demo purpose
            .cacheFor(TimeUnit.SECONDS, 5)
            .getValue();
        return String.format(translation, hostname);
    }

    @GET
    @Path("/hola-chaining")
    @Produces("application/json")
    @ApiOperation("Returns the greeting plus the next service in the chain")
    public List<String> holaChaining(@Context HttpHeaders headers) {
    	SpanContext spanCtx = tracer.extract(Format.Builtin.TEXT_MAP,
                new HttpHeadersExtractAdapter(headers.getRequestHeaders()));

        try (Span serverSpan = tracer.buildSpan("CalledHolaChaining")
                .asChildOf(spanCtx)
                .withTag("http.url", "/api/hola-chaining")
                .start()) {
            List<String> greetings = new ArrayList<>();
            greetings.add(hola());
            
            try (Span clientSpan = tracer.buildSpan("CallAlohaChaining")
                    .asChildOf(serverSpan)
                    .start()) {
                Map<String,Object> h = new HashMap<>();
                tracer.inject(clientSpan.context(), Format.Builtin.TEXT_MAP,
                        new HttpHeadersInjectAdapter(h));
                greetings.addAll(getNextService().aloha(h));
            }

            return greetings;
        }
    }

    @GET
    @Path("/health")
    @Produces("text/plain")
    @ApiOperation("Used to verify the health of the service")
    public String health() {
        return "I'm ok";
    }

    /**
     * This is were the "magic" happens: it creates a Feign, which is a proxy interface for remote calling a REST endpoint with
     * Hystrix fallback support.
     *
     * @return The feign pointing to the service URL and with Hystrix fallback.
     */
    private AlohaService getNextService() {
        final String serviceName = "aloha";
        String url = String.format("http://%s:8080/", serviceName);
        AlohaService fallback = (headers) -> Collections.singletonList("Aloha response (fallback)");
        return HystrixFeign.builder()
            .logger(new Logger.ErrorLogger()).logLevel(Level.BASIC)
            .decoder(new JacksonDecoder())
            .target(AlohaService.class, url, fallback);
    }

    public final class HttpHeadersExtractAdapter implements TextMap {
        private final Map<String,String> map;

        public HttpHeadersExtractAdapter(final Map<String,List<String>> multiValuedMap) {
        	// Convert to single valued map
            this.map = new HashMap<>();
            multiValuedMap.forEach((k,v) -> map.put(k, v.get(0)));
        }

        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
            return map.entrySet().iterator();
        }

        @Override
        public void put(String key, String value) {
            throw new UnsupportedOperationException("TextMapInjectAdapter should only be used with Tracer.extract()");
        }
    }

    public final class HttpHeadersInjectAdapter implements TextMap {
        private final Map<String,Object> map;

        public HttpHeadersInjectAdapter(final Map<String,Object> map) {
            this.map = map;
        }

        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
            throw new UnsupportedOperationException("TextMapInjectAdapter should only be used with Tracer.inject()");
        }

        @Override
        public void put(String key, String value) {
            this.map.put(key, value);
        }
    }

}
