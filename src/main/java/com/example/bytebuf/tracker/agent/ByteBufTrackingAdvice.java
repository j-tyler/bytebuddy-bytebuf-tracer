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

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import io.netty.buffer.ByteBuf;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice for tracking ByteBuf flow through methods.
 * Applied to all methods that might handle ByteBufs.
 */
public class ByteBufTrackingAdvice {

    // Re-entrance guard to prevent infinite recursion when tracking code
    // triggers other instrumented methods
    private static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Method entry advice - tracks ByteBufs in parameters
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] arguments) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        if (arguments == null || arguments.length == 0) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            for (Object arg : arguments) {
                if (arg instanceof ByteBuf) {
                    ByteBuf buf = (ByteBuf) arg;
                    tracker.recordMethodCall(
                        buf,
                        clazz.getSimpleName(),
                        methodName,
                        buf.refCnt()
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
    
    /**
     * Method exit advice - tracks ByteBufs in return values and final state
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] arguments,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Check if any ByteBufs in parameters have changed refCount
            if (arguments != null) {
                for (Object arg : arguments) {
                    if (arg instanceof ByteBuf) {
                        ByteBuf buf = (ByteBuf) arg;
                        // Record the exit state
                        tracker.recordMethodCall(
                            buf,
                            clazz.getSimpleName(),
                            methodName + "_exit",
                            buf.refCnt()
                        );
                    }
                }
            }

            // Track ByteBuf return values
            if (returnValue instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) returnValue;
                tracker.recordMethodCall(
                    buf,
                    clazz.getSimpleName(),
                    methodName + "_return",
                    buf.refCnt()
                );
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
}

/**
 * Alternative interceptor style for more complex scenarios
 */
public class ByteBufInterceptor {
    
    @RuntimeType
    public static Object intercept(
            @Origin Method method,
            @Origin Class<?> clazz,
            @AllArguments Object[] args,
            @SuperCall Callable<?> zuper) throws Exception {
        
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        
        // Track entry
        for (Object arg : args) {
            if (arg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) arg;
                tracker.recordMethodCall(
                    buf,
                    clazz.getSimpleName(),
                    method.getName(),
                    buf.refCnt()
                );
            }
        }
        
        // Execute original method
        Object result = zuper.call();
        
        // Track exit
        for (Object arg : args) {
            if (arg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) arg;
                tracker.recordMethodCall(
                    buf,
                    clazz.getSimpleName(),
                    method.getName() + "_after",
                    buf.refCnt()
                );
            }
        }
        
        // Track return
        if (result instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) result;
            tracker.recordMethodCall(
                buf,
                clazz.getSimpleName(),
                method.getName() + "_return",
                buf.refCnt()
            );
        }
        
        return result;
    }
}
