/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Simple assertEquals helper to avoid JUnit dependency
 */
class Assert {
    static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + ", but got: " + actual);
        }
    }
}

/**
 * Test application that verifies Mockito works correctly with the ByteBuf tracking agent.
 *
 * This app demonstrates that:
 * 1. Mockito can create mocks of interfaces/classes with ByteBuf methods
 * 2. The agent doesn't interfere with Mockito's bytecode manipulation
 * 3. Both the agent and Mockito can coexist without "class redefinition failed" errors
 */
public class MockitoTestApp {

    /**
     * Interface that will be mocked by Mockito
     */
    public interface ByteBufProcessor {
        ByteBuf process(ByteBuf input);
        void consume(ByteBuf buffer);
        boolean validate(ByteBuf buffer);
    }

    /**
     * Real class that will be instrumented by the agent
     */
    public static class RealByteBufHandler {
        private final ByteBufProcessor processor;

        public RealByteBufHandler(ByteBufProcessor processor) {
            this.processor = processor;
        }

        public ByteBuf handleBuffer(ByteBuf buffer) {
            System.out.println("RealByteBufHandler.handleBuffer called");
            if (processor.validate(buffer)) {
                return processor.process(buffer);
            }
            return null;
        }

        public void releaseBuffer(ByteBuf buffer) {
            System.out.println("RealByteBufHandler.releaseBuffer called");
            processor.consume(buffer);
            if (buffer.refCnt() > 0) {
                buffer.release();
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Mockito Integration Test App ===");
        System.out.println("Testing that Mockito and ByteBuf tracking agent can coexist\n");

        try {
            // Test 1: Create a mock (this would fail if org.mockito is not excluded)
            System.out.println("Test 1: Creating Mockito mock of interface with ByteBuf methods...");
            ByteBufProcessor mockProcessor = Mockito.mock(ByteBufProcessor.class);
            System.out.println("✓ Successfully created mock");

            // Test 2: Configure mock behavior
            System.out.println("\nTest 2: Configuring mock behavior...");
            ByteBuf returnBuffer = Unpooled.buffer(100);
            when(mockProcessor.validate(any(ByteBuf.class))).thenReturn(true);
            when(mockProcessor.process(any(ByteBuf.class))).thenReturn(returnBuffer);
            doNothing().when(mockProcessor).consume(any(ByteBuf.class));
            System.out.println("✓ Successfully configured mock");

            // Test 3: Use mock with real instrumented class
            System.out.println("\nTest 3: Using mock with real instrumented class...");
            RealByteBufHandler handler = new RealByteBufHandler(mockProcessor);
            ByteBuf inputBuffer = Unpooled.buffer(50);
            inputBuffer.writeBytes(new byte[]{1, 2, 3, 4, 5});

            ByteBuf result = handler.handleBuffer(inputBuffer);
            System.out.println("✓ Successfully called instrumented method with mock dependency");

            // Test 4: Verify mock interactions
            System.out.println("\nTest 4: Verifying mock interactions...");
            verify(mockProcessor, times(1)).validate(inputBuffer);
            verify(mockProcessor, times(1)).process(inputBuffer);
            verify(mockProcessor, never()).consume(any(ByteBuf.class));
            System.out.println("✓ Successfully verified mock interactions");

            // Test 5: Create multiple mocks (stress test)
            System.out.println("\nTest 5: Creating multiple mocks (stress test)...");
            ByteBufProcessor mock1 = mock(ByteBufProcessor.class);
            ByteBufProcessor mock2 = mock(ByteBufProcessor.class);
            ByteBufProcessor mock3 = mock(ByteBufProcessor.class);
            System.out.println("✓ Successfully created multiple mocks");

            // Test 6: Verify ArgumentCaptor works
            System.out.println("\nTest 6: Testing ArgumentCaptor with ByteBuf...");
            org.mockito.ArgumentCaptor<ByteBuf> captor =
                org.mockito.ArgumentCaptor.forClass(ByteBuf.class);

            // Use mock to capture arguments
            mockProcessor.validate(inputBuffer);
            verify(mockProcessor, times(2)).validate(captor.capture());

            // Verify captured values
            Assert.assertEquals(inputBuffer, captor.getValue());
            System.out.println("✓ Successfully captured ByteBuf argument");

            System.out.println("\n=== Note on Spies ===");
            System.out.println("Mockito.spy() cannot be used on classes instrumented by the agent.");
            System.out.println("This is expected: both the agent and Mockito try to transform the same class.");
            System.out.println("Solution: Use mock() instead of spy() (recommended), or exclude specific");
            System.out.println("classes from agent instrumentation if spying is absolutely necessary.");

            // Cleanup
            System.out.println("\nCleaning up resources...");
            inputBuffer.release();
            returnBuffer.release();
            System.out.println("✓ Cleanup complete");

            System.out.println("\n=== ALL TESTS PASSED ===");
            System.out.println("Mockito and ByteBuf tracking agent work together without conflicts!");

        } catch (Exception e) {
            System.err.println("\n✗ TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
