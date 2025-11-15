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
     * Load custom handlers from system property BEFORE instrumentation.
     * Handlers must be registered before we analyze methods to decide which advice to apply.
     *
     * System property format: -Dobject.tracker.handlers=com.example.Handler1,com.example.Handler2
     */
    private static void loadCustomHandlers() {
        String handlers = System.getProperty("object.tracker.handlers");
        if (handlers != null && !handlers.isEmpty()) {
            String[] handlerClasses = handlers.split(",");
            for (String className : handlerClasses) {
                className = className.trim();
                if (className.isEmpty()) {
                    continue;
                }
                try {
                    Class<?> clazz = Class.forName(className);
                    com.example.bytebuf.api.tracker.ObjectTrackerHandler handler =
                        (com.example.bytebuf.api.tracker.ObjectTrackerHandler) clazz.getDeclaredConstructor().newInstance();
                    com.example.bytebuf.tracker.ObjectTrackerRegistry.registerCustomHandler(handler);
                } catch (Exception e) {
                    System.err.println("[ByteBufFlowAgent] Failed to load custom handler: " + className);
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Premain method for Java agent
     *
     * @param arguments Agent arguments in format: include=pkg1,pkg2;exclude=pkg3,pkg4;trackConstructors=class1,class2
     * @param inst Instrumentation instance
     */
    public static void premain(String arguments, Instrumentation inst) {
        // CRITICAL: Load custom handlers BEFORE parsing config and instrumenting classes
        // The agent needs to know about custom types at instrumentation time
        loadCustomHandlers();

        AgentConfig config = AgentConfig.parse(arguments);

        System.out.println("[ByteBufFlowAgent] Starting with config: " + config);

        // Configure direct-only tracking mode
        if (config.isTrackDirectOnly()) {
            System.out.println("[ByteBufFlowAgent] Direct-only tracking mode enabled:");
            System.out.println("  - heapBuffer methods: NOT instrumented (zero overhead)");
            System.out.println("  - directBuffer/ioBuffer: Instrumented and tracked");
            System.out.println("  - Ambiguous methods (wrappedBuffer, compositeBuffer): Filtered at runtime via isDirect()");
            // Enable filtering for ambiguous methods when trackDirectOnly is set
            ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;
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
            // IMPORTANT: Exclude ByteBuf implementation classes - they're handled by ByteBufLifecycleTransformer
            .type(config.getTypeMatcher()
                .and(not(isInterface()))
                .and(not(isAbstract()))
                .and(not(hasSuperType(named("io.netty.buffer.ByteBuf")))))  // Prevent double instrumentation
            .transform(new ByteBufTransformer());

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
     * Enhanced transformer that applies DIFFERENT advice based on method analysis.
     * Analyzes each method to determine:
     * - How many ByteBuf parameters it has (0, 1, 2, 3+)
     * - Whether it has custom tracked object parameters
     * Then applies the optimal advice:
     * - ByteBuf-only methods: Use optimized zero-allocation advice
     * - Custom object methods: Use generic advice (acceptable slower path)
     */
    static class ByteBufTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            // Iterate through each method and apply appropriate advice
            for (MethodDescription method : typeDescription.getDeclaredMethods()) {
                // Skip constructors, abstract methods, private methods
                if (method.isConstructor() || method.isAbstract() ||
                    (!method.isPublic() && !method.isProtected())) {
                    continue;
                }

                // Analyze method to determine which advice to apply
                MethodTypeInfo typeInfo = analyzeMethod(method);

                if (!typeInfo.needsTracking()) {
                    continue;  // No tracked objects in signature
                }

                // Apply appropriate advice based on analysis
                Class<?> adviceClass = selectAdvice(typeInfo);

                builder = builder
                    .method(is(method))
                    .intercept(Advice.to(adviceClass));
            }

            return builder;
        }
    }

    /**
     * Method type information for deciding which advice to apply.
     */
    static class MethodTypeInfo {
        final int byteBufParamCount;
        final java.util.List<Integer> byteBufPositions;  // Positions of ByteBuf parameters
        final boolean hasCustomObjects;
        final boolean hasByteBufReturn;

        MethodTypeInfo(int byteBufParamCount, java.util.List<Integer> byteBufPositions,
                       boolean hasCustomObjects, boolean hasByteBufReturn) {
            this.byteBufParamCount = byteBufParamCount;
            this.byteBufPositions = byteBufPositions;
            this.hasCustomObjects = hasCustomObjects;
            this.hasByteBufReturn = hasByteBufReturn;
        }

        boolean needsTracking() {
            return byteBufParamCount > 0 || hasCustomObjects || hasByteBufReturn;
        }

        /**
         * Check if all ByteBuf parameters are in the first 4 positions (indices 0-3).
         * This determines whether we can use optimized advice that uses @Argument(0-3)
         * instead of @AllArguments.
         */
        boolean allByteBufsInFirstFourPositions() {
            for (int pos : byteBufPositions) {
                if (pos >= 4) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "MethodTypeInfo{byteBuf=" + byteBufParamCount +
                   ", positions=" + byteBufPositions +
                   ", custom=" + hasCustomObjects +
                   ", return=" + hasByteBufReturn + "}";
        }
    }

    /**
     * Analyze a method to determine what types of tracked objects it uses.
     */
    private static MethodTypeInfo analyzeMethod(MethodDescription method) {
        int byteBufCount = 0;
        java.util.List<Integer> byteBufPositions = new java.util.ArrayList<>();
        boolean hasCustom = false;

        // Get custom handlers
        java.util.List<com.example.bytebuf.api.tracker.ObjectTrackerHandler> customHandlers =
            com.example.bytebuf.tracker.ObjectTrackerRegistry.getCustomHandlers();

        // Analyze parameters
        for (ParameterDescription param : method.getParameters()) {
            TypeDescription paramType = param.getType().asErasure();

            // Check if ByteBuf
            if (isByteBufType(paramType)) {
                byteBufCount++;
                byteBufPositions.add(param.getIndex());
            } else {
                // Check if custom tracked type
                for (com.example.bytebuf.api.tracker.ObjectTrackerHandler handler : customHandlers) {
                    if (isTrackedType(paramType, handler.getObjectType())) {
                        hasCustom = true;
                        break;
                    }
                }
            }
        }

        // Check return type
        TypeDescription returnType = method.getReturnType().asErasure();
        boolean hasByteBufReturn = isByteBufType(returnType);

        if (!hasByteBufReturn && !returnType.represents(void.class)) {
            // Check if return is custom tracked type
            for (com.example.bytebuf.api.tracker.ObjectTrackerHandler handler : customHandlers) {
                if (isTrackedType(returnType, handler.getObjectType())) {
                    hasCustom = true;
                    break;
                }
            }
        }

        return new MethodTypeInfo(byteBufCount, byteBufPositions, hasCustom, hasByteBufReturn);
    }

    /**
     * Check if a type is ByteBuf or subclass.
     */
    private static boolean isByteBufType(TypeDescription type) {
        try {
            return type.represents(io.netty.buffer.ByteBuf.class) ||
                   type.isAssignableTo(io.netty.buffer.ByteBuf.class);
        } catch (Exception e) {
            // Class loading issues - assume not ByteBuf
            return false;
        }
    }

    /**
     * Check if a type matches a tracked type name (string-based to avoid class loading).
     * Supports interfaces and superclasses.
     */
    private static boolean isTrackedType(TypeDescription type, String typeName) {
        // Direct match
        if (type.getTypeName().equals(typeName)) {
            return true;
        }

        // Check interfaces
        for (TypeDescription.Generic iface : type.getInterfaces()) {
            if (iface.asErasure().getTypeName().equals(typeName)) {
                return true;
            }
        }

        // Check superclass recursively
        TypeDescription.Generic superClass = type.getSuperClass();
        if (superClass != null && !superClass.represents(Object.class)) {
            return isTrackedType(superClass.asErasure(), typeName);
        }

        return false;
    }

    /**
     * Select the appropriate advice class based on method analysis.
     *
     * Performance optimization: Use specialized advice only when all ByteBuf parameters
     * are in the first 4 positions (indices 0-3). This allows using @Argument(0-3)
     * instead of @AllArguments, saving 88 bytes per method call.
     *
     * Correctness guarantee: Methods with custom tracked objects always use generic
     * advice to ensure both ByteBuf AND custom objects are tracked through method calls.
     */
    private static Class<?> selectAdvice(MethodTypeInfo typeInfo) {
        // If has custom objects, always use generic advice
        // This ensures we track ByteBuf AND custom tracked objects correctly (user requirement)
        if (typeInfo.hasCustomObjects) {
            return GenericTrackingAdvice.class;
        }

        // ByteBuf-only methods - check positions before using optimized advice
        if (typeInfo.byteBufParamCount == 0 && typeInfo.hasByteBufReturn) {
            return ByteBufZeroParamAdvice.class;
        } else if (typeInfo.byteBufParamCount == 1) {
            if (typeInfo.allByteBufsInFirstFourPositions()) {
                return ByteBufOneParamAdvice.class;  // Fast path: 0 bytes
            } else {
                return ByteBufGeneralAdvice.class;   // Safe fallback: 88 bytes
            }
        } else if (typeInfo.byteBufParamCount == 2) {
            if (typeInfo.allByteBufsInFirstFourPositions()) {
                return ByteBufTwoParamAdvice.class;  // Fast path: 0 bytes
            } else {
                return ByteBufGeneralAdvice.class;   // Safe fallback: 88 bytes
            }
        } else if (typeInfo.byteBufParamCount >= 3) {
            return ByteBufGeneralAdvice.class;
        }

        // Fallback to general advice (shouldn't happen if needsTracking() was checked)
        return ByteBufGeneralAdvice.class;
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
     * Instruments release(), retain(), retainedDuplicate(), and retainedSlice()
     * methods to track reference count changes and derived buffers.
     * Only records release() when it drops refCnt to 0, avoiding noise from
     * intermediate release calls.
     *
     * This transformer applies to Netty ByteBuf implementation classes, while
     * ByteBufTransformer applies to application code. They don't conflict because
     * they transform different type hierarchies.
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
                    // Match lifecycle methods: release, retain, retainedDuplicate, retainedSlice
                    (named("release")
                        .or(named("retain"))
                        .or(named("retainedDuplicate"))
                        .or(named("retainedSlice")))
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

    private final Set<String> includePatterns;  // Contains both package and class inclusions
    private final Set<String> excludePatterns;  // Contains both package and class exclusions
    private final Set<String> constructorTrackingClasses;
    private final boolean trackDirectOnly;      // Skip instrumentation of heap buffers (zero overhead)

    private AgentConfig(Set<String> includePatterns, Set<String> excludePatterns,
                        Set<String> constructorTrackingClasses, boolean trackDirectOnly) {
        this.includePatterns = includePatterns;
        this.excludePatterns = excludePatterns;
        this.constructorTrackingClasses = constructorTrackingClasses;
        this.trackDirectOnly = trackDirectOnly;
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

        return new AgentConfig(include, exclude, trackConstructors, trackDirectOnly);
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

    @Override
    public String toString() {
        return "AgentConfig{include=" + includePatterns +
               ", exclude=" + excludePatterns +
               ", trackConstructors=" + constructorTrackingClasses +
               ", trackDirectOnly=" + trackDirectOnly + "}";
    }
}
