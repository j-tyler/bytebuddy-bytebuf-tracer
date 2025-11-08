package com.example.bytebuf.tracker.agent;

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
    
    /**
     * Premain method for Java agent
     *
     * @param arguments Agent arguments in format: include=pkg1,pkg2;exclude=pkg3,pkg4;trackConstructors=class1,class2
     * @param inst Instrumentation instance
     */
    public static void premain(String arguments, Instrumentation inst) {
        AgentConfig config = AgentConfig.parse(arguments);

        System.out.println("[ByteBufFlowAgent] Starting with config: " + config);

        // Setup JMX MBean for monitoring
        setupJmxMonitoring();

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
            .transform(new ByteBufConstructionTransformer());

        agentBuilder.installOn(inst);

        System.out.println("[ByteBufFlowAgent] Instrumentation installed successfully");
    }
    
    /**
     * Transformer that applies advice to methods
     */
    static class ByteBufTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            return builder
                .method(
                    // Match methods that might handle ByteBufs (including static methods)
                    // Skip abstract methods (they have no bytecode to instrument)
                    // IMPORTANT: Only instrument methods with ByteBuf in their signature
                    // to prevent unnecessary class transformation and Mockito conflicts
                    isPublic()
                    .or(isProtected())
                    .and(not(isConstructor()))
                    .and(not(isAbstract()))
                    .and(hasByteBufInSignature())
                )
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
     */
    static class ByteBufConstructionTransformer implements AgentBuilder.Transformer {
        @Override
        public DynamicType.Builder<?> transform(
                DynamicType.Builder<?> builder,
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                java.security.ProtectionDomain protectionDomain) {

            // Determine which methods to instrument based on the class
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
                methodMatcher = (named("wrappedBuffer")
                    .or(named("copiedBuffer"))
                    .or(named("compositeBuffer")))
                    .and(isPublic())
                    .and(not(isAbstract()))
                    .and(returns(hasSuperType(named("io.netty.buffer.ByteBuf"))));
            } else {
                // For ByteBufAllocator implementations:
                // Only track methods with exactly 2 int parameters (terminal implementations)
                methodMatcher = (named("directBuffer")
                    .or(named("heapBuffer")))
                    .and(isPublic())
                    .and(not(isAbstract()))
                    .and(returns(hasSuperType(named("io.netty.buffer.ByteBuf"))))
                    .and(takesArguments(int.class, int.class)); // Only track 2-arg terminal versions
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
    private final Set<String> includePackages;
    private final Set<String> excludePackages;
    private final Set<String> constructorTrackingClasses;

    private AgentConfig(Set<String> includePackages, Set<String> excludePackages,
                        Set<String> constructorTrackingClasses) {
        this.includePackages = includePackages;
        this.excludePackages = excludePackages;
        this.constructorTrackingClasses = constructorTrackingClasses;
    }

    /**
     * Parse agent arguments
     * Format: include=com.example,com.myapp;exclude=com.example.legacy;trackConstructors=com.example.Message,com.example.Request
     */
    public static AgentConfig parse(String arguments) {
        Set<String> include = new HashSet<>();
        Set<String> exclude = new HashSet<>();
        Set<String> trackConstructors = new HashSet<>();

        if (arguments != null && !arguments.isEmpty()) {
            String[] parts = arguments.split(";");
            for (String part : parts) {
                String[] kv = part.split("=", 2); // Use limit=2 to handle class names with = in them
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();

                    if ("include".equals(key)) {
                        include.addAll(Arrays.asList(value.split(",")));
                    } else if ("exclude".equals(key)) {
                        exclude.addAll(Arrays.asList(value.split(",")));
                    } else if ("trackConstructors".equals(key)) {
                        // Parse class names, trimming whitespace
                        for (String className : value.split(",")) {
                            trackConstructors.add(className.trim());
                        }
                    }
                }
            }
        }

        // Default to common application packages if none specified
        if (include.isEmpty()) {
            include.add("com.");
            include.add("org.");
            include.add("net.");
        }

        return new AgentConfig(include, exclude, trackConstructors);
    }

    /**
     * Get type matcher based on configuration
     */
    public ElementMatcher.Junction<TypeDescription> getTypeMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = none();

        // Include packages
        for (String pkg : includePackages) {
            matcher = matcher.or(nameStartsWith(pkg));
        }

        // Exclude packages
        for (String pkg : excludePackages) {
            matcher = matcher.and(not(nameStartsWith(pkg)));
        }

        return matcher;
    }

    /**
     * Get matcher for classes that should have constructor tracking
     */
    public ElementMatcher.Junction<TypeDescription> getConstructorTrackingMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = none();

        for (String className : constructorTrackingClasses) {
            // Support both exact matches and pattern matches
            if (className.endsWith("*")) {
                // Wildcard pattern: com.example.* matches all classes in package
                String prefix = className.substring(0, className.length() - 1);
                matcher = matcher.or(nameStartsWith(prefix));
            } else {
                // Exact match
                matcher = matcher.or(named(className));
            }
        }

        return matcher;
    }

    /**
     * Get the set of classes configured for constructor tracking
     */
    public Set<String> getConstructorTrackingClasses() {
        return constructorTrackingClasses;
    }

    @Override
    public String toString() {
        return "AgentConfig{include=" + includePackages +
               ", exclude=" + excludePackages +
               ", trackConstructors=" + constructorTrackingClasses + "}";
    }
}
