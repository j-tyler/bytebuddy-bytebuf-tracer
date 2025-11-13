/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.metrics.MetricPushScheduler;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Java Agent for ByteBuf flow tracking.
 * Instruments methods to track ByteBuf movement through the application.
 */
public class ByteBufFlowAgent {

    private static MetricPushScheduler metricScheduler;

    /**
     * Premain method for Java agent
     *
     * @param arguments Agent arguments in format: include=pkg1,pkg2;exclude=pkg3,pkg4;trackConstructors=class1,class2
     * @param inst Instrumentation instance
     */
    public static void premain(String arguments, Instrumentation inst) {
        AgentConfig config = AgentConfig.parse(arguments);

        System.out.println("[ByteBufFlowAgent] Starting with config: " + config);

        // Configure optimization mode
        if (config.hasCustomHandler()) {
            System.out.println("[ByteBufFlowAgent] Custom handler detected - using general advice (safe mode)");
        } else {
            System.out.println("[ByteBufFlowAgent] Optimization enabled:");
            System.out.println("  - 0-param methods returning ByteBuf: Optimized (ZeroParamByteBufReturnAdvice)");
            System.out.println("  - 1-param methods (ByteBuf): Optimized (SingleByteBufParamAdvice)");
            System.out.println("  - 2-param methods (ByteBuf, X): Optimized (TwoParamByteBufAt0Advice)");
            System.out.println("  - 2-param methods (X, ByteBuf): Optimized (TwoParamByteBufAt1Advice)");
            System.out.println("  - 2-param methods (ByteBuf, ByteBuf): Optimized (TwoParamBothByteBufAdvice)");
            System.out.println("  - 3+ param methods: General advice (fallback)");
            System.out.println("  - Expected savings: ~100-220 bytes per optimized operation");
        }

        // Configure direct-only tracking mode
        if (config.isTrackDirectOnly()) {
            System.out.println("[ByteBufFlowAgent] Direct-only tracking mode enabled:");
            System.out.println("  - heapBuffer methods: NOT instrumented (zero overhead)");
            System.out.println("  - directBuffer/ioBuffer: Instrumented and tracked");
            System.out.println("  - Ambiguous methods (wrappedBuffer, compositeBuffer): Filtered at runtime via isDirect()");
            // Enable filtering for ambiguous methods when trackDirectOnly is set
            ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;
        }

        // Configure structural-only tracking mode
        if (config.isTrackOnlyStructuralMethods()) {
            System.out.println("[ByteBufFlowAgent] Structural-only tracking mode enabled:");
            System.out.println("  - TRACKING: slice, duplicate, copy, readSlice, retain, release (structural changes)");
            System.out.println("  - SKIPPING: read*/write*/get*/set* primitives (data manipulation)");
            System.out.println("  - SKIPPING: index queries, mark/reset, housekeeping methods");
            System.out.println("  - Expected overhead reduction: 60-80% while maintaining full leak detection");
        }

        // Setup JMX MBean for monitoring
        setupJmxMonitoring();

        // Start metric push scheduler (if handlers registered)
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        metricScheduler = new MetricPushScheduler(tracker);
        metricScheduler.start();

        // Setup shutdown hook for final report
        setupShutdownHook();

        // Build the agent with chained transformers
        AgentBuilder.Identified.Extendable agentBuilder = new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener.StreamWriting(System.out).withTransformationsOnly())
            .ignore(
                nameStartsWith("net.bytebuddy.")
                .or(nameStartsWith("java."))
                .or(nameStartsWith("sun."))
                .or(nameStartsWith("com.sun."))
                .or(nameStartsWith("jdk."))
                .or(nameStartsWith("org.mockito.")) // Avoid conflicts with Mockito mocking framework
                // Don't instrument the tracker implementation, but DO instrument test apps
                .or(nameStartsWith("com.example.bytebuf.tracker.agent."))
                .or(nameStartsWith("com.example.bytebuf.tracker.ByteBufFlowTracker"))
                .or(nameStartsWith("com.example.bytebuf.tracker.ObjectTracker"))
                .or(nameStartsWith("com.example.bytebuf.tracker.Trie"))
                .or(isSynthetic()) // Don't instrument compiler-generated classes
            )
            // Transform regular methods (non-constructors)
            // Filter out interfaces and abstract classes (can't instrument abstract methods)
            .type(config.getTypeMatcher()
                .and(not(isInterface()))
                .and(not(isAbstract())))
            .transform(new ByteBufTransformer(config));

        // Add constructor tracking for specified classes
        if (!config.getConstructorTrackingClasses().isEmpty()) {
            System.out.println("[ByteBufFlowAgent] Constructor tracking enabled for: " +
                config.getConstructorTrackingClasses());
            agentBuilder = agentBuilder
                .type(config.getConstructorTrackingMatcher()
                    .and(not(isInterface()))
                    .and(not(isAbstract())))
                .transform(new ConstructorTrackingTransformer());
        }

        // Add ByteBuf lifecycle method tracking (release, retain)
        // This tracks release() only when it drops refCnt to 0
        // Use string-based matching to avoid loading ByteBuf class during premain
        System.out.println("[ByteBufFlowAgent] ByteBuf lifecycle tracking enabled (release/retain)");
        agentBuilder = agentBuilder
            .type(hasSuperType(named("io.netty.buffer.ByteBuf"))
                .and(not(isInterface()))
                .and(not(isAbstract())))
            .transform(new ByteBufLifecycleTransformer());

        // Add ByteBuf construction tracking
        // This makes allocation sites (Unpooled.buffer, allocator.directBuffer, etc.)
        // the root nodes in the flow trie for better leak diagnosis
        System.out.println("[ByteBufFlowAgent] ByteBuf construction tracking enabled");
        agentBuilder = agentBuilder
            .type(named("io.netty.buffer.Unpooled")
                .or(hasSuperType(named("io.netty.buffer.ByteBufAllocator"))
                    .and(not(isInterface()))
                    .and(not(isAbstract()))))
            .transform(new ByteBufConstructionTransformer(config.isTrackDirectOnly()));

        agentBuilder.installOn(inst);

        System.out.println("[ByteBufFlowAgent] Instrumentation installed successfully");
    }
    
    /**
     * Transformer that applies advice to methods with selective optimization.
     *
     * <p><b>Optimization Strategy:</b>
     * <ul>
     *   <li>0-param returning ByteBuf: {@link ZeroParamByteBufReturnAdvice}</li>
     *   <li>1-param (ByteBuf): {@link SingleByteBufParamAdvice}</li>
     *   <li>2-param (ByteBuf, X): {@link TwoParamByteBufAt0Advice}</li>
     *   <li>2-param (X, ByteBuf): {@link TwoParamByteBufAt1Advice}</li>
     *   <li>2-param (ByteBuf, ByteBuf): {@link TwoParamBothByteBufAdvice}</li>
     *   <li>3+ params or custom handler: {@link ByteBufTrackingAdvice}</li>
     * </ul>
     *
     * <p>This reduces memory allocation by eliminating Object[] array creation
     * for 0-param, 1-param and 2-param methods, saving ~100-220 bytes per operation.
     */
    static class ByteBufTransformer implements AgentBuilder.Transformer {
        private final boolean useOptimizedAdvice;
        private final boolean trackOnlyStructuralMethods;

        public ByteBufTransformer(AgentConfig config) {
            // Only use optimized advice if no custom handler is configured
            // Custom handlers need the general advice to support arbitrary object types
            this.useOptimizedAdvice = !config.hasCustomHandler();
            this.trackOnlyStructuralMethods = config.isTrackOnlyStructuralMethods();
        }

        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            // Common method matcher: public/protected, non-constructor, non-abstract, has ByteBuf
            ElementMatcher.Junction<MethodDescription> baseMatcher =
                (isPublic().or(isProtected()))
                .and(not(isConstructor()))
                .and(not(isAbstract()))
                .and(hasByteBufInSignature());

            // Apply structural-only filtering to exclude high-frequency, low-value methods
            if (trackOnlyStructuralMethods) {
                baseMatcher = baseMatcher.and(isStructuralMethod());
            }

            if (!useOptimizedAdvice) {
                // Custom handler detected - use general advice for all methods
                return builder
                    .method(baseMatcher)
                    .intercept(Advice.to(ByteBufTrackingAdvice.class));
            }

            // Optimization enabled - route to specialized advice classes

            // 0. Zero-parameter methods returning ByteBuf: ByteBuf method()
            ElementMatcher.Junction<MethodDescription> zeroParamByteBufReturn =
                baseMatcher
                .and(takesArguments(0))
                .and(returns(isSubTypeOf(io.netty.buffer.ByteBuf.class)));

            // 1. Single-parameter methods: method(ByteBuf)
            ElementMatcher.Junction<MethodDescription> singleByteBufParam =
                baseMatcher
                .and(takesArguments(1))
                .and(takesArgument(0, isSubTypeOf(io.netty.buffer.ByteBuf.class)));

            // 2. Two-parameter methods: method(ByteBuf, X) where X is NOT ByteBuf
            ElementMatcher.Junction<MethodDescription> twoParamByteBufAt0 =
                baseMatcher
                .and(takesArguments(2))
                .and(takesArgument(0, isSubTypeOf(io.netty.buffer.ByteBuf.class)))
                .and(not(takesArgument(1, isSubTypeOf(io.netty.buffer.ByteBuf.class))));

            // 3. Two-parameter methods: method(X, ByteBuf) where X is NOT ByteBuf
            ElementMatcher.Junction<MethodDescription> twoParamByteBufAt1 =
                baseMatcher
                .and(takesArguments(2))
                .and(not(takesArgument(0, isSubTypeOf(io.netty.buffer.ByteBuf.class))))
                .and(takesArgument(1, isSubTypeOf(io.netty.buffer.ByteBuf.class)));

            // 4. Two-parameter methods: method(ByteBuf, ByteBuf)
            ElementMatcher.Junction<MethodDescription> twoParamBothByteBuf =
                baseMatcher
                .and(takesArguments(2))
                .and(takesArgument(0, isSubTypeOf(io.netty.buffer.ByteBuf.class)))
                .and(takesArgument(1, isSubTypeOf(io.netty.buffer.ByteBuf.class)));

            // Combine all optimized matchers
            ElementMatcher.Junction<MethodDescription> optimizedMethods =
                zeroParamByteBufReturn
                .or(singleByteBufParam)
                .or(twoParamByteBufAt0)
                .or(twoParamByteBufAt1)
                .or(twoParamBothByteBuf);

            // Apply specialized advice in order
            builder = builder
                .method(zeroParamByteBufReturn)
                .intercept(Advice.to(ZeroParamByteBufReturnAdvice.class))
                .method(singleByteBufParam)
                .intercept(Advice.to(SingleByteBufParamAdvice.class))
                .method(twoParamByteBufAt0)
                .intercept(Advice.to(TwoParamByteBufAt0Advice.class))
                .method(twoParamByteBufAt1)
                .intercept(Advice.to(TwoParamByteBufAt1Advice.class))
                .method(twoParamBothByteBuf)
                .intercept(Advice.to(TwoParamBothByteBufAdvice.class));

            // Apply general advice to remaining methods (3+ params, etc.)
            return builder
                .method(baseMatcher.and(not(optimizedMethods)))
                .intercept(Advice.to(ByteBufTrackingAdvice.class));
        }
    }

    /**
     * Creates a matcher that checks if a method has ByteBuf in its signature.
     * This includes:
     * - Methods that return ByteBuf (or any subclass)
     * - Methods that take ByteBuf as a parameter (at any position)
     *
     * @return ElementMatcher that matches methods with ByteBuf in signature
     */
    private static ElementMatcher.Junction<MethodDescription> hasByteBufInSignature() {
        // Match methods that return ByteBuf or any subclass
        ElementMatcher.Junction<MethodDescription> matcher =
            returns(isSubTypeOf(io.netty.buffer.ByteBuf.class));

        // Match methods that have ByteBuf as a parameter at any position
        // We create a custom matcher that checks all parameters
        matcher = matcher.or(new ElementMatcher<MethodDescription>() {
            @Override
            public boolean matches(MethodDescription target) {
                // Check if any parameter is assignable to ByteBuf
                for (ParameterDescription param : target.getParameters()) {
                    TypeDescription paramType = param.getType().asErasure();
                    try {
                        // Check if the parameter type is ByteBuf or a subclass
                        if (paramType.represents(io.netty.buffer.ByteBuf.class) ||
                            paramType.isAssignableTo(io.netty.buffer.ByteBuf.class)) {
                            return true;
                        }
                    } catch (Exception e) {
                        // If we can't load the class, skip it
                        // This can happen with classloader issues
                    }
                }
                return false;
            }
        });

        return matcher;
    }

    /**
     * Creates a matcher that identifies "structural" ByteBuf methods - those that:
     * - Create new ByteBuf instances (slice, duplicate, copy, etc.)
     * - Change ByteBuf structure (order, asReadOnly, etc.)
     * - Are critical for lifecycle tracking (retain, release)
     *
     * <p>EXCLUDES high-frequency, low-value methods:
     * - Data manipulation: readXXX/writeXXX/getXXX/setXXX primitives
     * - Index queries: writerIndex, readerIndex, capacity
     * - Mark/reset operations: markReaderIndex, resetWriterIndex, etc.
     * - Housekeeping: clear, discardReadBytes, ensureWritable, etc.
     *
     * <p>This dramatically reduces overhead (60-80%) while maintaining full
     * leak detection capability, since leaks are caused by structural issues
     * (not releasing, creating slices without tracking), not by data operations.
     *
     * @return ElementMatcher that matches only structural methods
     */
    private static ElementMatcher.Junction<MethodDescription> isStructuralMethod() {
        // EXCLUDE data manipulation methods (read/write/get/set primitives)
        // These are the highest-frequency methods with no leak detection value
        // Use nameStartsWith for simplicity - covers readByte, writeByte, getByte, setByte, etc.
        ElementMatcher.Junction<MethodDescription> matcher = not(
            nameStartsWith("read").or(nameStartsWith("write"))
                .or(nameStartsWith("get")).or(nameStartsWith("set"))
        );

        // KEEP methods that start with excluded prefixes but are actually structural
        // readSlice() creates a new ByteBuf, so we need to re-include it
        matcher = matcher.or(named("readSlice").or(named("readRetainedSlice")));

        // EXCLUDE index manipulation methods
        matcher = matcher.and(not(
            named("writerIndex").or(named("readerIndex"))
                .or(named("setIndex")).or(named("capacity"))
                .or(named("maxCapacity")).or(named("readableBytes"))
                .or(named("writableBytes")).or(named("maxWritableBytes"))
                .or(named("isReadable")).or(named("isWritable"))
                .or(named("maxFastWritableBytes"))
        ));

        // EXCLUDE mark/reset index operations
        matcher = matcher.and(not(
            named("markReaderIndex").or(named("markWriterIndex"))
                .or(named("resetReaderIndex")).or(named("resetWriterIndex"))
        ));

        // EXCLUDE housekeeping operations (no new ByteBuf, no lifecycle impact)
        matcher = matcher.and(not(
            named("clear").or(named("discardReadBytes"))
                .or(named("discardSomeReadBytes"))
                .or(named("ensureWritable"))
                .or(named("isContiguous")).or(named("nioBufferCount"))
                .or(named("internalNioBuffer")).or(named("nioBuffer"))
                .or(named("nioBuffers")).or(named("hasArray"))
                .or(named("array")).or(named("arrayOffset"))
                .or(named("hasMemoryAddress")).or(named("memoryAddress"))
                .or(named("toString")).or(named("hashCode"))
                .or(named("equals")).or(named("compareTo"))
                .or(named("indexOf")).or(named("bytesBefore"))
                .or(named("forEachByte")).or(named("forEachByteDesc"))
        ));

        // KEEP structural methods by exclusion (if not excluded above, it's structural)
        // This includes:
        // - slice(), duplicate(), copy(), readSlice()
        // - retainedSlice(), retainedDuplicate()
        // - asReadOnly(), order()
        // - retain(), release() (lifecycle critical)
        // - touch(), retain(int), release(int)
        // - Any method that returns a NEW ByteBuf instance

        return matcher;
    }

    /**
     * Transformer that applies advice to constructors for specified classes.
     * Uses ByteBufConstructorAdvice instead of ByteBufTrackingAdvice because:
     * - Constructors cannot use onThrowable (would wrap code before super() call)
     * - JVM bytecode verifier requires super()/this() to be called first
     * - Exception handlers can only exist AFTER super/this initialization
     */
    static class ConstructorTrackingTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            return builder
                .constructor(
                    // Match public and protected constructors
                    isPublic().or(isProtected())
                )
                .intercept(Advice.to(ByteBufConstructorAdvice.class));
        }
    }

    /**
     * Transformer that applies advice to ByteBuf lifecycle methods.
     * Instruments release() and retain() methods to track reference count changes.
     * Only records release() when it drops refCnt to 0, avoiding noise from
     * intermediate release calls.
     */
    static class ByteBufLifecycleTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            return builder
                .method(
                    // Match release() and retain() methods
                    (named("release").or(named("retain")))
                    .and(isPublic())
                    .and(not(isAbstract()))
                )
                .intercept(Advice.to(ByteBufLifecycleAdvice.class));
        }
    }

    /**
     * Transformer that applies advice to ByteBuf construction/factory methods.
     * Instruments Unpooled static methods and ByteBufAllocator methods to
     * make allocation sites the root nodes in flow trees.
     *
     * This enables deterministic roots and better leak diagnosis by showing
     * the exact allocation method (buffer, directBuffer, wrappedBuffer, etc.)
     *
     * IMPORTANT: Only instruments terminal methods to avoid confusing output
     * from telescoping method calls. For example, directBuffer() delegates to
     * directBuffer(int) which delegates to directBuffer(int, int). We only
     * track the terminal directBuffer(int, int) to avoid nested duplicates.
     *
     * Performance: When trackDirectOnly is enabled, heapBuffer methods are not
     * instrumented at all, resulting in zero runtime overhead for heap allocations.
     */
    static class ByteBufConstructionTransformer implements AgentBuilder.Transformer {
        private final boolean trackDirectOnly;

        /**
         * @param trackDirectOnly If true, skip instrumentation of heapBuffer (zero overhead)
         */
        public ByteBufConstructionTransformer(boolean trackDirectOnly) {
            this.trackDirectOnly = trackDirectOnly;
        }

        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            // Determine which methods to instrument based on the class and config
            ElementMatcher.Junction<MethodDescription> methodMatcher;

            // For ByteBufAllocator implementations (AbstractByteBufAllocator and subclasses):
            // Only track terminal methods with 2 int parameters to avoid telescoping
            // directBuffer() -> directBuffer(int) -> directBuffer(int, int) [TERMINAL]
            // heapBuffer() -> heapBuffer(int) -> heapBuffer(int, int) [TERMINAL]
            // buffer() -> delegates to directBuffer/heapBuffer [skip entirely]
            // ioBuffer() -> delegates to directBuffer/heapBuffer [skip entirely]
            if (typeDescription.asErasure().getName().equals("io.netty.buffer.Unpooled")) {
                // For Unpooled class:
                // - Skip buffer() and directBuffer() entirely (they delegate to allocator)
                // - Track wrappedBuffer, copiedBuffer, compositeBuffer (all terminal)
                // Note: When trackDirectOnly is enabled, these are still instrumented
                // because they may wrap direct buffers; filtering happens at runtime
                methodMatcher = (named("wrappedBuffer")
                    .or(named("copiedBuffer"))
                    .or(named("compositeBuffer")))
                    .and(isPublic())
                    .and(not(isAbstract()))
                    .and(returns(hasSuperType(named("io.netty.buffer.ByteBuf"))));
            } else {
                // For ByteBufAllocator implementations:
                // Only track methods with exactly 2 int parameters (terminal implementations)
                if (trackDirectOnly) {
                    // OPTIMIZATION: Skip heapBuffer entirely (zero instrumentation overhead)
                    methodMatcher = named("directBuffer")
                        .and(isPublic())
                        .and(not(isAbstract()))
                        .and(returns(hasSuperType(named("io.netty.buffer.ByteBuf"))))
                        .and(takesArguments(int.class, int.class));
                } else {
                    // Track both direct and heap buffers (default behavior)
                    methodMatcher = (named("directBuffer")
                        .or(named("heapBuffer")))
                        .and(isPublic())
                        .and(not(isAbstract()))
                        .and(returns(hasSuperType(named("io.netty.buffer.ByteBuf"))))
                        .and(takesArguments(int.class, int.class)); // Only track 2-arg terminal versions
                }
            }

            return builder
                .method(methodMatcher)
                .intercept(Advice.to(ByteBufConstructionAdvice.class));
        }
    }

    /**
     * Setup JMX MBean for runtime monitoring
     */
    private static void setupJmxMonitoring() {
        try {
            javax.management.MBeanServer mbs =
                java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName name =
                new javax.management.ObjectName("com.example:type=ByteBufFlowTracker");
            mbs.registerMBean(new ByteBufFlow(), name);
            System.out.println("[ByteBufFlowAgent] JMX MBean registered");
        } catch (Exception e) {
            System.err.println("[ByteBufFlowAgent] Failed to register JMX MBean: " + e);
        }
    }
    
    /**
     * Setup shutdown hook to output final report
     */
    private static void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Mark remaining flows as leaks before generating report
            ByteBufFlowTracker.getInstance().onShutdown();

            // Shutdown metric scheduler
            if (metricScheduler != null) {
                metricScheduler.shutdown();
            }

            System.out.println("\n=== ByteBuf Flow Final Report ===");
            ByteBufFlowReporter reporter = new ByteBufFlowReporter();
            System.out.println(reporter.generateReport());
        }));
    }
}

/**
 * Configuration for the agent
 */
class AgentConfig {
    /** Suffix for package wildcard notation (e.g., "com.example.*") */
    private static final String PACKAGE_WILDCARD_SUFFIX = ".*";

    /** Parameter names for agent configuration */
    private static final String PARAM_INCLUDE = "include";
    private static final String PARAM_EXCLUDE = "exclude";
    private static final String PARAM_TRACK_CONSTRUCTORS = "trackConstructors";
    private static final String PARAM_TRACK_DIRECT_ONLY = "trackDirectOnly";
    private static final String PARAM_TRACK_ONLY_STRUCTURAL = "trackOnlyStructuralMethods";

    private final Set<String> includePatterns;  // Contains both package and class inclusions
    private final Set<String> excludePatterns;  // Contains both package and class exclusions
    private final Set<String> constructorTrackingClasses;
    private final boolean trackDirectOnly;      // Skip instrumentation of heap buffers (zero overhead)
    private final boolean trackOnlyStructuralMethods;  // Skip data manipulation methods (huge overhead reduction)
    private final boolean hasCustomHandler;     // Whether a custom ObjectTrackerHandler is configured

    private AgentConfig(Set<String> includePatterns, Set<String> excludePatterns,
                        Set<String> constructorTrackingClasses, boolean trackDirectOnly,
                        boolean trackOnlyStructuralMethods, boolean hasCustomHandler) {
        this.includePatterns = includePatterns;
        this.excludePatterns = excludePatterns;
        this.constructorTrackingClasses = constructorTrackingClasses;
        this.trackDirectOnly = trackDirectOnly;
        this.trackOnlyStructuralMethods = trackOnlyStructuralMethods;
        this.hasCustomHandler = hasCustomHandler;
    }

    /**
     * Validate a pattern string for include/exclude/trackConstructors.
     * @param pattern The pattern to validate
     * @param paramName The parameter name (for error messages)
     * @throws IllegalArgumentException if the pattern is invalid
     */
    private static void validatePattern(String pattern, String paramName) {
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException(
                paramName + ": Empty pattern not allowed");
        }

        if (pattern.contains("..")) {
            throw new IllegalArgumentException(
                paramName + ": Invalid pattern with consecutive dots: " + pattern);
        }

        if (pattern.equals(".*") || pattern.equals("*")) {
            throw new IllegalArgumentException(
                paramName + ": Global wildcard not allowed (would match all classes): " + pattern);
        }

        if (pattern.endsWith(".") && !pattern.endsWith(".*")) {
            throw new IllegalArgumentException(
                paramName + ": Package patterns must end with .* not just . : " + pattern);
        }

        // Check for double wildcard
        if (pattern.contains(".**")) {
            throw new IllegalArgumentException(
                paramName + ": Invalid pattern with .** (use .* instead): " + pattern);
        }
    }

    /**
     * Parse agent arguments.
     * Format: include=package.*,SpecificClass;exclude=package.*,SpecificClass;trackConstructors=SpecificClass,package.*
     *
     * Examples:
     * - include=com.example.* (package with .* suffix)
     * - include=com.example.MyClass (specific class without .* suffix)
     * - include=com.example.Outer$Inner (inner class with $ separator)
     * - exclude=com.example.test.* (package exclusion)
     * - exclude=com.example.MockHelper (class exclusion)
     */
    public static AgentConfig parse(String arguments) {
        Set<String> include = new HashSet<>();
        Set<String> exclude = new HashSet<>();
        Set<String> trackConstructors = new HashSet<>();
        boolean trackDirectOnly = false;
        boolean trackOnlyStructuralMethods = false;

        if (arguments != null && !arguments.isEmpty()) {
            String[] parts = arguments.split(";");
            for (String part : parts) {
                String[] kv = part.split("=", 2); // Use limit=2 to handle class names with = in them
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();

                    if (PARAM_INCLUDE.equals(key)) {
                        // Parse patterns, trimming whitespace and validating
                        for (String pattern : value.split(",")) {
                            String trimmed = pattern.trim();
                            validatePattern(trimmed, PARAM_INCLUDE);
                            include.add(trimmed);
                        }
                    } else if (PARAM_EXCLUDE.equals(key)) {
                        // Parse patterns, trimming whitespace and validating
                        for (String pattern : value.split(",")) {
                            String trimmed = pattern.trim();
                            validatePattern(trimmed, PARAM_EXCLUDE);
                            exclude.add(trimmed);
                        }
                    } else if (PARAM_TRACK_CONSTRUCTORS.equals(key)) {
                        // Parse class names, trimming whitespace and validating
                        for (String className : value.split(",")) {
                            String trimmed = className.trim();
                            validatePattern(trimmed, PARAM_TRACK_CONSTRUCTORS);
                            trackConstructors.add(trimmed);
                        }
                    } else if (PARAM_TRACK_DIRECT_ONLY.equals(key)) {
                        // Parse boolean flag (true/false)
                        trackDirectOnly = Boolean.parseBoolean(value);
                    } else if (PARAM_TRACK_ONLY_STRUCTURAL.equals(key)) {
                        // Parse boolean flag (true/false)
                        trackOnlyStructuralMethods = Boolean.parseBoolean(value);
                    }
                }
            }
        }

        // Default to common application packages if none specified
        if (include.isEmpty()) {
            include.add("com.*");
            include.add("org.*");
            include.add("net.*");
        }

        // Detect if a custom ObjectTrackerHandler is configured
        // Custom handlers prevent using optimized advice (must use general advice for all types)
        String customHandler = System.getProperty("object.tracker.handler");
        boolean hasCustomHandler = (customHandler != null && !customHandler.isEmpty());

        return new AgentConfig(include, exclude, trackConstructors, trackDirectOnly,
                               trackOnlyStructuralMethods, hasCustomHandler);
    }

    /**
     * Applies exclusion patterns to a matcher.
     * Exclusions support both package-level and class-level patterns:
     * - Package: "com.example.protocol.*" excludes all classes in package/subpackages
     * - Class: "com.example.protocol.SpecificClass" excludes only that specific class
     * - Inner class: "com.example.Outer$Inner" excludes specific inner class
     *
     * @param matcher The matcher to apply exclusions to
     * @return The matcher with exclusions applied
     */
    private ElementMatcher.Junction<TypeDescription> applyExclusions(
            ElementMatcher.Junction<TypeDescription> matcher) {
        for (String exclude : excludePatterns) {
            if (exclude.endsWith(PACKAGE_WILDCARD_SUFFIX)) {
                // Package exclusion - prefix match
                String prefix = exclude.substring(0, exclude.length() - PACKAGE_WILDCARD_SUFFIX.length());
                matcher = matcher.and(not(nameStartsWith(prefix)));
            } else {
                // Class exclusion - exact match (supports inner classes with $)
                matcher = matcher.and(not(named(exclude)));
            }
        }
        return matcher;
    }

    /**
     * Get type matcher based on configuration.
     * Supports both package-level and class-level includes/exclusions:
     * - Package: "com.example.protocol.*" matches all classes in package/subpackages (requires .* suffix)
     * - Class: "com.example.protocol.SpecificClass" matches only that specific class (no .* suffix)
     * - Inner class: "com.example.Outer$Inner" matches specific inner class ($ separator)
     *
     * The .* suffix distinguishes packages from classes, making intent explicit.
     *
     * Precedence: Exclusions take precedence over inclusions. If a class matches both include
     * and exclude patterns, it will be excluded.
     */
    public ElementMatcher.Junction<TypeDescription> getTypeMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = none();

        // Include packages and classes
        for (String include : includePatterns) {
            if (include.endsWith(PACKAGE_WILDCARD_SUFFIX)) {
                // Package inclusion - prefix match
                String prefix = include.substring(0, include.length() - PACKAGE_WILDCARD_SUFFIX.length());
                matcher = matcher.or(nameStartsWith(prefix));
            } else {
                // Class inclusion - exact match (supports inner classes with $)
                matcher = matcher.or(named(include));
            }
        }

        return applyExclusions(matcher);
    }

    /**
     * Get matcher for classes that should have constructor tracking.
     * Applies both trackConstructors patterns and exclude patterns.
     *
     * IMPORTANT: Exclusions take precedence - if a class is both in trackConstructors
     * and in exclude patterns, it will NOT be instrumented. This ensures consistency
     * with regular method instrumentation and prevents conflicts with Mockito.
     */
    public ElementMatcher.Junction<TypeDescription> getConstructorTrackingMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = none();

        for (String className : constructorTrackingClasses) {
            if (className.endsWith(PACKAGE_WILDCARD_SUFFIX)) {
                String prefix = className.substring(0, className.length() - PACKAGE_WILDCARD_SUFFIX.length());
                matcher = matcher.or(nameStartsWith(prefix));
            } else {
                matcher = matcher.or(named(className));
            }
        }

        return applyExclusions(matcher);
    }

    /**
     * Get the set of classes configured for constructor tracking
     */
    public Set<String> getConstructorTrackingClasses() {
        return constructorTrackingClasses;
    }

    /**
     * Check if track-direct-only mode is enabled (skip instrumentation of heap buffers)
     */
    public boolean isTrackDirectOnly() {
        return trackDirectOnly;
    }

    public boolean hasCustomHandler() {
        return hasCustomHandler;
    }

    /**
     * Check if structural-only tracking mode is enabled (skip data manipulation methods)
     */
    public boolean isTrackOnlyStructuralMethods() {
        return trackOnlyStructuralMethods;
    }

    @Override
    public String toString() {
        return "AgentConfig{include=" + includePatterns +
               ", exclude=" + excludePatterns +
               ", trackConstructors=" + constructorTrackingClasses +
               ", trackDirectOnly=" + trackDirectOnly +
               ", trackOnlyStructuralMethods=" + trackOnlyStructuralMethods +
               ", hasCustomHandler=" + hasCustomHandler + "}";
    }
}
