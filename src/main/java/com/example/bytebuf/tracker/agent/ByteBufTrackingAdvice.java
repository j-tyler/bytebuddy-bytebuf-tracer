package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import io.netty.buffer.ByteBuf;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bind.annotation.*;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice for tracking ByteBuf flow through methods.
 * Applied to all methods that might handle ByteBufs.
 */
public class ByteBufTrackingAdvice {
    
    /**
     * Method entry advice - tracks ByteBufs in parameters
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] arguments) {
        
        if (arguments == null || arguments.length == 0) {
            return;
        }
        
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
    }
    
    /**
     * Method exit advice - tracks ByteBufs in return values and final state
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] arguments,
            @Advice.Return Object returnValue,
            @Advice.Thrown Throwable thrown) {
        
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
