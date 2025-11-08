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
     * @param arguments Agent arguments in format: include=pkg1,pkg2;exclude=pkg3,pkg4
     * @param inst Instrumentation instance
     */
    public static void premain(String arguments, Instrumentation inst) {
        AgentConfig config = AgentConfig.parse(arguments);
        
        System.out.println("[ByteBufFlowAgent] Starting with config: " + config);
        
        // Setup JMX MBean for monitoring
        setupJmxMonitoring();
        
        // Setup shutdown hook for final report
        setupShutdownHook();
        
        // Build the agent
        new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener.StreamWriting(System.out).withTransformationsOnly())
            .ignore(
                nameStartsWith("net.bytebuddy.")
                .or(nameStartsWith("java."))
                .or(nameStartsWith("sun."))
                .or(nameStartsWith("com.sun."))
                .or(nameStartsWith("jdk."))
                .or(nameStartsWith("com.example.bytebuf.tracker.")) // Don't instrument ourselves
                .or(isSynthetic()) // Don't instrument compiler-generated classes
            )
            // Filter out interfaces and abstract classes (can't instrument abstract methods)
            .type(config.getTypeMatcher()
                .and(not(isInterface()))
                .and(not(isAbstract())))
            .transform(new ByteBufTransformer())
            .installOn(inst);
        
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
                JavaModule module) {

            return builder
                .method(
                    // Match methods that might handle ByteBufs
                    // Skip abstract methods (they have no bytecode to instrument)
                    // IMPORTANT: Only instrument methods with ByteBuf in their signature
                    // to prevent unnecessary class transformation and Mockito conflicts
                    isPublic()
                    .or(isProtected())
                    .and(not(isConstructor()))
                    .and(not(isStatic()))
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
     * Setup JMX MBean for runtime monitoring
     */
    private static void setupJmxMonitoring() {
        try {
            javax.management.MBeanServer mbs = 
                java.lang.management.ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName name = 
                new javax.management.ObjectName("com.example:type=ByteBufFlowTracker");
            mbs.registerMBean(new ByteBufFlowMBean(), name);
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
    
    private AgentConfig(Set<String> includePackages, Set<String> excludePackages) {
        this.includePackages = includePackages;
        this.excludePackages = excludePackages;
    }
    
    /**
     * Parse agent arguments
     * Format: include=com.example,com.myapp;exclude=com.example.legacy
     */
    public static AgentConfig parse(String arguments) {
        Set<String> include = new HashSet<>();
        Set<String> exclude = new HashSet<>();
        
        if (arguments != null && !arguments.isEmpty()) {
            String[] parts = arguments.split(";");
            for (String part : parts) {
                String[] kv = part.split("=");
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String value = kv[1].trim();
                    
                    if ("include".equals(key)) {
                        include.addAll(Arrays.asList(value.split(",")));
                    } else if ("exclude".equals(key)) {
                        exclude.addAll(Arrays.asList(value.split(",")));
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
        
        return new AgentConfig(include, exclude);
    }
    
    /**
     * Get type matcher based on configuration
     */
    public ElementMatcher<TypeDescription> getTypeMatcher() {
        ElementMatcher<TypeDescription> matcher = none();
        
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
    
    @Override
    public String toString() {
        return "AgentConfig{include=" + includePackages + ", exclude=" + excludePackages + "}";
    }
}
