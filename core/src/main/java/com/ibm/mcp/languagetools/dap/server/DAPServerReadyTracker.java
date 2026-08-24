/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Angelo ZERR - initial API and implementation
 *******************************************************************************/
package com.ibm.mcp.languagetools.dap.server;

import com.ibm.mcp.languagetools.dap.configurations.ExtractorResult;
import com.ibm.mcp.languagetools.dap.configurations.NetworkAddressExtractor;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Tracker to notify that DAP server is ready to consume by DAP clients.
 * (DAP server is started and can be listened to a given port if DAP server uses Socket).
 */
public class DAPServerReadyTracker extends CompletableFuture<Void> {

    private static final Logger LOG = Logger.getLogger(DAPServerReadyTracker.class);

    private final ServerReadyConfig config;
    private final Process serverProcess;
    private String address;
    private Integer port;
    private boolean foundTrace;
    private final Consumer<String> traceConsumer;
    private volatile Consumer<String> stdoutConsumer;

    public DAPServerReadyTracker(ServerReadyConfig config,
                                 Process serverProcess,
                                 Consumer<String> traceConsumer) {
        this.config = config;
        this.serverProcess = serverProcess;
        this.traceConsumer = traceConsumer;
        this.port = config.getPort();
        this.address = config.getAddress();
    }

    public void setStdoutConsumer(Consumer<String> stdoutConsumer) {
        this.stdoutConsumer = stdoutConsumer;
    }

    public Integer getPort() {
        return port;
    }

    public String getAddress() {
        return address;
    }

    /**
     * Start tracking the server readiness.
     *
     * @param executorService the executor service for async tasks
     * @return this CompletableFuture
     */
    public CompletableFuture<Void> track(ExecutorService executorService) {
        if (waitForTimeout(executorService)) {
            // Wait for timeout strategy
            return this;
        }

        if (config.getDebugServerReadyPattern() != null) {
            // Wait for trace pattern strategy
            monitorStdoutForPattern(executorService);
            return this;
        }

        if (port != null) {
            // Wait for socket availability
            waitForSocket(executorService);
            return this;
        }

        // No waiting strategy, server is ready immediately
        onServerReady();
        return this;
    }

    /**
     * Monitor stdout/stderr for the debug server ready pattern.
     */
    private void monitorStdoutForPattern(ExecutorService executorService) {
        executorService.submit(() -> {
            try {
                InputStream stdout = serverProcess.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stdout));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!foundTrace) {
                        if (traceConsumer != null) {
                            traceConsumer.accept(line);
                        }
                        checkDebugServerReadyPattern(line);
                    } else {
                        Consumer<String> consumer = stdoutConsumer;
                        if (consumer != null) {
                            consumer.accept(line);
                        }
                    }
                }
            } catch (IOException e) {
                if (serverProcess.isAlive()) {
                    LOG.warnf("Error reading DAP server output: %s", e.getMessage());
                }
            }
        });
    }

    /**
     * Wait for socket to become available.
     */
    private void waitForSocket(ExecutorService executorService) {
        executorService.submit(() -> {
            String host = address != null ? address : "127.0.0.1";
            long deadline = System.currentTimeMillis() + 60_000;
            while (!this.isDone() && System.currentTimeMillis() < deadline) {
                if (isSocketAvailable(host, port)) {
                    onServerReady();
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!this.isDone()) {
                String msg = String.format("Timeout waiting for DAP server socket on %s:%d", host, port);
                LOG.warnf(msg);
                this.completeExceptionally(new java.util.concurrent.TimeoutException(msg));
            }
        });
    }

    private static boolean isSocketAvailable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void onServerReady() {
        if (!this.isDone()) {
            LOG.infof("DAP server ready (address=%s, port=%s)", address, port);
            super.complete(null);
        }
    }

    private boolean waitForTimeout(ExecutorService executorService) {
        Integer connectTimeout = config.getConnectTimeout();
        if (connectTimeout != null) {
            executorService.submit(() -> {
                try {
                    Thread.sleep(connectTimeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onServerReady();
            });
            return true;
        }
        return false;
    }

    private boolean checkDebugServerReadyPattern(String text) {
        if (!foundTrace) {
            NetworkAddressExtractor extractor = config.getDebugServerReadyPattern();
            if (extractor != null) {
                ExtractorResult result = extractor.extract(text);
                if (result.matches()) {
                    // ex: text="DAP server listening at: 127.0.0.1:61537"
                    if (port == null && result.port() != null) {
                        // ex: extractedPort="61537"
                        port = Integer.valueOf(result.port());
                    }
                    if (address == null) {
                        address = result.address();
                    }
                    foundTrace = true;
                    onServerReady();
                    return true;
                }
            }
        }
        return false;
    }
}
