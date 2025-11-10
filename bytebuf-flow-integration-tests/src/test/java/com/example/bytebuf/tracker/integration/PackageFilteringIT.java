/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.integration.utils.AppLauncher;
import com.example.bytebuf.tracker.integration.utils.OutputVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for package filtering (include/exclude config).
 * Verifies that only specified packages are instrumented.
 */
public class PackageFilteringIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testIncludePackage() throws Exception {
        // Run with include config
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Methods from included package should be tracked
        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testExcludePackage() throws Exception {
        // Run with exclude config that excludes the test app package
        // This should result in no methods being tracked
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.*;exclude=com.example.bytebuf.tracker.integration.testapp.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Since we excluded the testapp package, testapp methods should NOT be tracked
        // However, allocator methods (Unpooled, UnpooledByteBufAllocator) will still be roots
        // because they're in io.netty.buffer package which is not excluded

        // Application methods might appear due to _return suffix tracking
        // but the main flow should be minimal (no full application flow)
        // Check that we don't have a complete application flow by verifying cleanup isn't called
        assertThat(verifier.hasMethodInFlow("cleanup"))
            .withFailMessage("cleanup should NOT be tracked when package is excluded")
            .isFalse();

        // Allocator methods may still create roots (they're not in excluded package)
        // So we just verify application methods are not tracked
    }

    @Test
    public void testMultipleIncludes() throws Exception {
        // Run with multiple include packages
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*,com.example.demo.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start and track methods
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testIncludeParentPackage() throws Exception {
        // Run with parent package inclusion
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should track methods from com.example packages
        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testIncludeSingleClassOnly() throws Exception {
        // Run with single class inclusion (no .* suffix)
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should still track methods from BasicFlowApp
        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testExcludeSpecificClass() throws Exception {
        // Run with specific class exclusion (no .* suffix means class, not package)
        // OptionallyExcludedHelper should be excluded, but ExclusionTestApp methods should be tracked
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.OptionallyExcludedHelper");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Included methods should be tracked
        assertThat(verifier.hasMethodInFlow("includedProcess"))
            .withFailMessage("includedProcess should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("anotherIncludedProcess"))
            .withFailMessage("anotherIncludedProcess should be tracked")
            .isTrue();

        // Excluded class methods should NOT be tracked in flow tree
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("processInExcludedClass"))
            .withFailMessage("processInExcludedClass should NOT be tracked when class is excluded")
            .isFalse();
    }

    @Test
    public void testExcludePackageWithWildcard() throws Exception {
        // Run with package exclusion using .* notation
        // All classes in the excluded package should be excluded
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.excluded.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Included methods should be tracked
        assertThat(verifier.hasMethodInFlow("includedProcess"))
            .withFailMessage("includedProcess should be tracked")
            .isTrue();

        // Excluded package methods should NOT be tracked in flow tree
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("processInExcludedPackage"))
            .withFailMessage("processInExcludedPackage should NOT be tracked when package is excluded")
            .isFalse();

        assertThat(flowTree.contains("additionalProcessing"))
            .withFailMessage("additionalProcessing should NOT be tracked when package is excluded")
            .isFalse();
    }

    @Test
    public void testMixedClassAndPackageExclusions() throws Exception {
        // Run with both class and package exclusions (no .* = class, with .* = package)
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.OptionallyExcludedHelper,com.example.bytebuf.tracker.integration.testapp.excluded.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Included methods should be tracked
        assertThat(verifier.hasMethodInFlow("includedProcess"))
            .withFailMessage("includedProcess should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("anotherIncludedProcess"))
            .withFailMessage("anotherIncludedProcess should be tracked")
            .isTrue();

        // Excluded class and package methods should NOT be tracked in flow tree
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("processInExcludedClass"))
            .withFailMessage("processInExcludedClass should NOT be tracked when class is excluded")
            .isFalse();

        assertThat(flowTree.contains("processInExcludedPackage"))
            .withFailMessage("processInExcludedPackage should NOT be tracked when package is excluded")
            .isFalse();
    }

    @Test
    public void testExcludeSubpackages() throws Exception {
        // Run with package exclusion that should also exclude subpackages
        // Excluding "com.example.bytebuf.tracker.integration.testapp.excluded.*" should
        // also exclude "com.example.bytebuf.tracker.integration.testapp.excluded.subpkg" if it existed
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.excluded.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Excluded package and all subpackages should NOT be tracked in flow tree
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("ExcludedPackageHelper"))
            .withFailMessage("Classes in excluded package should NOT be tracked")
            .isFalse();

        // Even classes in the excluded package namespace should be excluded
        assertThat(flowTree.contains("AnotherExcludedClass"))
            .withFailMessage("All classes in excluded package should NOT be tracked")
            .isFalse();
    }

    @Test
    public void testExcludeInnerClass() throws Exception {
        // Run with inner class exclusion using $ separator (no .* suffix means class, not package)
        // InnerHelper (inner class) should be excluded, but outer class methods should be tracked
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.InnerClassTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.InnerClassTestApp$InnerHelper");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Outer class methods should be tracked
        assertThat(verifier.hasMethodInFlow("outerProcess"))
            .withFailMessage("outerProcess should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("anotherOuterProcess"))
            .withFailMessage("anotherOuterProcess should be tracked")
            .isTrue();

        // Inner class methods should NOT be tracked in flow tree
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("processInInnerClass"))
            .withFailMessage("processInInnerClass should NOT be tracked when inner class is excluded")
            .isFalse();
    }

    @Test
    public void testIncludeSpecificClass() throws Exception {
        // Run with specific class inclusion (no .* suffix means class, not package)
        // Only ExclusionTestApp should be instrumented, not OptionallyExcludedHelper
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // ExclusionTestApp methods should be tracked
        assertThat(verifier.hasMethodInFlow("includedProcess"))
            .withFailMessage("includedProcess should be tracked when its class is included")
            .isTrue();

        // OptionallyExcludedHelper should NOT be tracked (not in include list)
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("processInExcludedClass"))
            .withFailMessage("processInExcludedClass should NOT be tracked when its class is not included")
            .isFalse();
    }

    @Test
    public void testIncludeMultipleClasses() throws Exception {
        // Run with multiple specific class inclusions (no .* suffix)
        // Both ExclusionTestApp and OptionallyExcludedHelper should be instrumented
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp,com.example.bytebuf.tracker.integration.testapp.OptionallyExcludedHelper");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Both classes' methods should be tracked
        assertThat(verifier.hasMethodInFlow("includedProcess"))
            .withFailMessage("includedProcess should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processInExcludedClass"))
            .withFailMessage("processInExcludedClass should be tracked when its class is explicitly included")
            .isTrue();
    }

    @Test
    public void testMixedPackageAndClassIncludes() throws Exception {
        // Run with mixed package and class includes
        // Include the testapp package with .* suffix, and a specific class without .*
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*,com.example.bytebuf.tracker.integration.testapp.OptionallyExcludedHelper");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // All methods should be tracked (package .* includes everything anyway)
        assertThat(verifier.hasMethodInFlow("includedProcess"))
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processInExcludedClass"))
            .isTrue();
    }

    @Test
    public void testIncludeInnerClass() throws Exception {
        // Run with inner class inclusion using $ separator (no .* suffix means class, not package)
        // Only the inner class should be instrumented, not outer class methods
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.InnerClassTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.InnerClassTestApp$InnerHelper");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Inner class methods should be tracked
        assertThat(verifier.hasMethodInFlow("processInInnerClass"))
            .withFailMessage("processInInnerClass should be tracked when inner class is explicitly included")
            .isTrue();

        // Outer class methods should NOT be tracked in flow tree (not in include list)
        // Note: We check the flow tree specifically (not console output) to avoid false positives
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("outerProcess"))
            .withFailMessage("outerProcess should NOT be tracked when outer class is not included")
            .isFalse();

        assertThat(flowTree.contains("anotherOuterProcess"))
            .withFailMessage("anotherOuterProcess should NOT be tracked when outer class is not included")
            .isFalse();
    }

    @Test
    public void testExcludeTakesPrecedenceOverInclude() throws Exception {
        // Run with conflicting include and exclude (exclude should win)
        // Include com.example.*, but exclude specific class
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.ExclusionTestApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // ExclusionTestApp methods should NOT be tracked (excluded despite being in include pattern)
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("includedProcess"))
            .withFailMessage("includedProcess should NOT be tracked when its class is excluded (exclude takes precedence)")
            .isFalse();

        assertThat(flowTree.contains("anotherIncludedProcess"))
            .withFailMessage("anotherIncludedProcess should NOT be tracked when its class is excluded")
            .isFalse();
    }

    @Test
    public void testConstructorTrackingRespectsExclusions() throws Exception {
        // Run with trackConstructors but exclude the class
        // This tests that exclusions take precedence over trackConstructors
        // IMPORTANT: This verifies the fix for the bug where constructor tracking
        // ignored exclusion patterns
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;exclude=com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp$Message;trackConstructors=com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp$Message");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Outer class methods should still be tracked (ConstructorTrackingApp is included)
        assertThat(verifier.hasMethodInFlow("allocate"))
            .withFailMessage("allocate should be tracked (outer class is included)")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("prepare"))
            .withFailMessage("prepare should be tracked (outer class is included)")
            .isTrue();

        // Message constructor should NOT be tracked (excluded despite being in trackConstructors)
        // Why check flow tree: If the constructor were instrumented, the ByteBuf passed through
        // Message.<init> would appear as a node in the flow tree. Absence from the tree proves
        // the constructor was not instrumented, confirming exclusion took precedence.
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("Message.<init>"))
            .withFailMessage("Message constructor should NOT be tracked when class is excluded (exclude takes precedence over trackConstructors)")
            .isFalse();

        // Message.getBuffer() should also NOT be tracked (class is excluded)
        assertThat(flowTree.contains("getBuffer"))
            .withFailMessage("getBuffer should NOT be tracked when class is excluded")
            .isFalse();
    }

    @Test
    public void testConstructorTrackingRespectsPackageExclusions() throws Exception {
        // Run with trackConstructors pattern but exclude the package
        // This tests that package-level exclusions apply to constructor tracking
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp",
            "include=com.example.*;exclude=com.example.bytebuf.tracker.integration.testapp.*;trackConstructors=com.example.bytebuf.tracker.integration.testapp.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Application methods should NOT be tracked (package is excluded)
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree.contains("allocate"))
            .withFailMessage("allocate should NOT be tracked when package is excluded")
            .isFalse();

        assertThat(flowTree.contains("prepare"))
            .withFailMessage("prepare should NOT be tracked when package is excluded")
            .isFalse();

        // Constructors should also NOT be tracked (package is excluded despite trackConstructors)
        assertThat(flowTree.contains("Message.<init>"))
            .withFailMessage("Message constructor should NOT be tracked when package is excluded (exclude takes precedence)")
            .isFalse();
    }
}
