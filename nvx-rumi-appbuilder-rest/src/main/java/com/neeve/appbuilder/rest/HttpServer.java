/**
 * Copyright 2022 N5 Technologies, Inc
 *
 * This product includes software developed at N5 Technologies, Inc
 * (http://www.n5corp.com/) as well as software licenced to N5 Technologies,
 * Inc under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding
 * copyright ownership.
 *
 * N5 Technologies licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neeve.appbuilder.rest;

import jakarta.ws.rs.core.Application;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.glassfish.jersey.servlet.ServletContainer;

/**
 * Thin wrapper around an embedded Jetty 12 server hosting a Jersey
 * {@link Application} via {@link ServletContainer}. Separated from
 * {@link AppBuilderRestMain} so tests can spin up on an ephemeral port
 * without forking a process.
 */
public final class HttpServer {
    private final Server server;
    private final String host;
    private final int requestedPort;

    public HttpServer(String host, int port, Application application) {
        this.host = host;
        this.requestedPort = port;
        this.server = new Server();

        org.eclipse.jetty.server.ServerConnector connector =
            new org.eclipse.jetty.server.ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletContainer jerseyServlet = new ServletContainer((org.glassfish.jersey.server.ResourceConfig) application);
        context.addServlet(new ServletHolder(jerseyServlet), "/*");

        server.setHandler(context);
    }

    public void start() throws Exception { server.start(); }

    public void join() throws InterruptedException { server.join(); }

    public void stop() throws Exception { server.stop(); }

    public String getHost() { return host; }

    /** Actual bound port — useful for tests started on port 0. */
    public int getPort() {
        if (server.getConnectors().length > 0
            && server.getConnectors()[0] instanceof org.eclipse.jetty.server.ServerConnector c) {
            int p = c.getLocalPort();
            return p > 0 ? p : requestedPort;
        }
        return requestedPort;
    }
}
