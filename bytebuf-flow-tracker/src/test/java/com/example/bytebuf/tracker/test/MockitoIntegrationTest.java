/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test verifying that Mockito works correctly with the ByteBuf flow tracker agent.
 *
 * This test ensures that org.mockito packages are excluded by default from instrumentation,
 * preventing "Mockito cannot mock this class" errors that occur when ByteBuddy tries to
 * instrument classes that Mockito is also trying to mock.
 */
public class MockitoIntegrationTest {

    /**
     * Interface representing a ByteBuf processor that can be mocked
     */
    public interface ByteBufProcessor {
        ByteBuf process(ByteBuf input);
        void consume(ByteBuf buffer);
        boolean validate(ByteBuf buffer);
    }

    /**
     * Class with ByteBuf methods that would be instrumented by the agent
     */
    public static class RealByteBufHandler {
        private final ByteBufProcessor processor;

        public RealByteBufHandler(ByteBufProcessor processor) {
            this.processor = processor;
        }

        public ByteBuf handleBuffer(ByteBuf buffer) {
            if (processor.validate(buffer)) {
                return processor.process(buffer);
            }
            return null;
        }

        public void releaseBuffer(ByteBuf buffer) {
            processor.consume(buffer);
            buffer.release();
        }
    }

    private ByteBufProcessor mockProcessor;

    @Before
    public void setup() {
        mockProcessor = Mockito.mock(ByteBufProcessor.class);
    }

    @Test
    public void testMockitoCanMockInterfaceWithByteBuf() {
        // Test that we can create a mock without errors
        assertNotNull("Should be able to create mock", mockProcessor);

        // Verify that we can stub methods
        ByteBuf testBuffer = Unpooled.buffer(10);
        ByteBuf returnBuffer = Unpooled.buffer(20);

        when(mockProcessor.validate(any(ByteBuf.class))).thenReturn(true);
        when(mockProcessor.process(any(ByteBuf.class))).thenReturn(returnBuffer);

        // Verify the mock works
        assertTrue("Mock should return stubbed value", mockProcessor.validate(testBuffer));
        assertEquals("Mock should return stubbed buffer", returnBuffer, mockProcessor.process(testBuffer));

        // Verify interactions
        verify(mockProcessor, times(1)).validate(testBuffer);
        verify(mockProcessor, times(1)).process(testBuffer);

        // Cleanup
        testBuffer.release();
        returnBuffer.release();
    }

    @Test
    public void testMockitoCanMockClassWithByteBufMethods() {
        // Create a concrete class and mock it
        ByteBufProcessor concreteProcessor = new ByteBufProcessor() {
            @Override
            public ByteBuf process(ByteBuf input) {
                return input;
            }

            @Override
            public void consume(ByteBuf buffer) {
                // No-op
            }

            @Override
            public boolean validate(ByteBuf buffer) {
                return buffer.readableBytes() > 0;
            }
        };

        // This should not throw "Mockito cannot mock this class" error
        ByteBufProcessor spy = Mockito.spy(concreteProcessor);

        ByteBuf testBuffer = Unpooled.buffer(10);
        testBuffer.writeBytes(new byte[]{1, 2, 3});

        // Use the spy
        assertTrue("Spy should call real method", spy.validate(testBuffer));

        // Verify interaction
        verify(spy, times(1)).validate(testBuffer);

        // Cleanup
        testBuffer.release();
    }

    @Test
    public void testRealClassesWithMockedDependencies() {
        // This tests the real-world scenario where:
        // - Some classes are mocked (and should not be instrumented)
        // - Other classes are real (and should be instrumented by the agent)

        ByteBuf inputBuffer = Unpooled.buffer(100);
        ByteBuf processedBuffer = Unpooled.buffer(200);

        inputBuffer.writeBytes(new byte[]{1, 2, 3, 4, 5});

        // Setup mock behavior
        when(mockProcessor.validate(any(ByteBuf.class))).thenReturn(true);
        when(mockProcessor.process(any(ByteBuf.class))).thenReturn(processedBuffer);

        // Create real handler that uses the mock
        RealByteBufHandler handler = new RealByteBufHandler(mockProcessor);

        // Execute real code with mocked dependency
        ByteBuf result = handler.handleBuffer(inputBuffer);

        // Verify behavior
        assertNotNull("Handler should return buffer", result);
        assertEquals("Handler should return processed buffer", processedBuffer, result);

        // Verify mock interactions
        verify(mockProcessor, times(1)).validate(inputBuffer);
        verify(mockProcessor, times(1)).process(inputBuffer);
        verify(mockProcessor, never()).consume(any(ByteBuf.class));

        // Cleanup
        inputBuffer.release();
        processedBuffer.release();
    }

    @Test
    public void testMockitoWithVoidMethodsOnByteBuf() {
        // Test void methods with ByteBuf parameters
        doNothing().when(mockProcessor).consume(any(ByteBuf.class));

        ByteBuf buffer = Unpooled.buffer(10);

        // This should not throw any errors
        mockProcessor.consume(buffer);

        // Verify the call
        verify(mockProcessor, times(1)).consume(buffer);

        // Cleanup
        buffer.release();
    }

    @Test
    public void testMockitoArgumentCapture() {
        // Test that we can use ArgumentCaptor with ByteBuf
        org.mockito.ArgumentCaptor<ByteBuf> captor =
            org.mockito.ArgumentCaptor.forClass(ByteBuf.class);

        ByteBuf testBuffer = Unpooled.buffer(10);
        testBuffer.writeInt(42);

        when(mockProcessor.validate(any(ByteBuf.class))).thenReturn(true);

        // Use the mock
        mockProcessor.validate(testBuffer);

        // Capture the argument
        verify(mockProcessor).validate(captor.capture());

        // Verify captured value
        ByteBuf captured = captor.getValue();
        assertEquals("Should capture the same buffer", testBuffer, captured);
        assertEquals("Should capture buffer with same content", 42, captured.getInt(0));

        // Cleanup
        testBuffer.release();
    }

    @Test
    public void testMockitoDoesNotConflictWithAgentInstrumentation() {
        // This is the critical test: verify that using Mockito in tests
        // does not cause instrumentation conflicts with the ByteBuf flow tracker agent

        // Create multiple mocks
        ByteBufProcessor processor1 = mock(ByteBufProcessor.class);
        ByteBufProcessor processor2 = mock(ByteBufProcessor.class);
        ByteBufProcessor processor3 = mock(ByteBufProcessor.class);

        // Setup different behaviors
        ByteBuf buf1 = Unpooled.buffer(10);
        ByteBuf buf2 = Unpooled.buffer(20);
        ByteBuf buf3 = Unpooled.buffer(30);

        when(processor1.process(any())).thenReturn(buf1);
        when(processor2.process(any())).thenReturn(buf2);
        when(processor3.process(any())).thenReturn(buf3);

        // Use all mocks
        assertEquals(buf1, processor1.process(Unpooled.buffer(1)));
        assertEquals(buf2, processor2.process(Unpooled.buffer(2)));
        assertEquals(buf3, processor3.process(Unpooled.buffer(3)));

        // Cleanup
        buf1.release();
        buf2.release();
        buf3.release();
    }
}
