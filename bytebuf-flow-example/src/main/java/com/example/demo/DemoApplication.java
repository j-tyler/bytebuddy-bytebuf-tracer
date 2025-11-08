/*
 * Copyright 2025 Justin Marsh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.demo;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Demo application showing ByteBuf Flow Tracker in action.
 *
 * This demonstrates how an external project would use the tracker.
 *
 * Run with:
 *   mvn exec:java
 *
 * Or manually:
 *   java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-*-agent.jar=include=com.example.demo \
 *        -cp target/bytebuf-flow-example-*.jar \
 *        com.example.demo.DemoApplication
 */
public class DemoApplication {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ByteBuf Flow Tracker Demo ===\n");

        DemoApplication app = new DemoApplication();

        // Simulate various ByteBuf usage patterns
        System.out.println("Running ByteBuf operations...\n");

        // Normal flow - properly released
        for (int i = 0; i < 5; i++) {
            app.handleNormalRequest();
        }

        // Flow with error handling - also properly released
        for (int i = 0; i < 3; i++) {
            app.handleRequestWithError();
        }

        // Intentional leak for demonstration
        System.out.println("Creating intentional leak for demonstration...\n");
        app.createLeak();

        // Give the agent a moment to finish tracking
        Thread.sleep(100);

        // Print the flow analysis
        System.out.println("\n=== Flow Analysis ===\n");
        printFlowAnalysis();
    }

    /**
     * Normal request handling - ByteBuf is properly released
     */
    public void handleNormalRequest() {
        ByteBuf request = Unpooled.buffer(256);
        request.writeBytes("Normal request data".getBytes());

        try {
            MessageProcessor processor = new MessageProcessor();
            processor.process(request);
        } finally {
            request.release();
        }
    }

    /**
     * Request with error - ByteBuf is still properly released in finally block
     */
    public void handleRequestWithError() {
        ByteBuf request = Unpooled.buffer(256);
        request.writeBytes("Error request data".getBytes());

        try {
            MessageProcessor processor = new MessageProcessor();
            processor.processWithPotentialError(request);
        } catch (Exception e) {
            ErrorHandler errorHandler = new ErrorHandler();
            errorHandler.handleError(request, e);
        } finally {
            request.release();
        }
    }

    /**
     * Intentional leak - ByteBuf is never released
     * The tracker will detect this as a leak
     */
    public void createLeak() {
        ByteBuf leakyBuffer = Unpooled.buffer(256);
        leakyBuffer.writeBytes("This will leak".getBytes());

        LeakyService leakyService = new LeakyService();
        leakyService.forgetsToRelease(leakyBuffer);

        // Oops! We forgot to release the buffer
        // The tracker will show this as a leaf node with non-zero refCount
    }

    /**
     * Print flow analysis using the tracker
     */
    private static void printFlowAnalysis() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        // Summary statistics
        System.out.println(renderer.renderSummary());
        System.out.println();

        // Tree view showing all flows
        System.out.println("=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());
        System.out.println();

        // Flat paths highlighting leaks
        System.out.println("=== Flat Paths (Leaks Highlighted) ===");
        System.out.println(renderer.renderForLLM());
        System.out.println();

        System.out.println("Look for leaf nodes with non-zero refCount - those are leaks!");
        System.out.println("In this example, LeakyService.forgetsToRelease should show as a leak.");
    }
}
