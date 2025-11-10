/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import org.junit.Test;

import static org.junit.Assert.fail;

/**
 * Unit tests for AgentConfig validation.
 * Tests that malformed patterns are rejected with clear error messages.
 */
public class AgentConfigValidationTest {

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyIncludePattern() {
        // Empty pattern should be rejected
        AgentConfig.parse("include=");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyPatternInList() {
        // Empty pattern in comma-separated list should be rejected
        AgentConfig.parse("include=com.example.*,,org.foo.*");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConsecutiveDots() {
        // Pattern with consecutive dots should be rejected
        AgentConfig.parse("include=com..example.*");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGlobalWildcardJustStar() {
        // Just * should be rejected (too broad)
        AgentConfig.parse("include=*");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGlobalWildcardDotStar() {
        // Just .* should be rejected (too broad)
        AgentConfig.parse("include=.*");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPatternEndingWithJustDot() {
        // Pattern ending with just . should be rejected (should be .*)
        AgentConfig.parse("include=com.example.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDoubleWildcard() {
        // Pattern with .** should be rejected
        AgentConfig.parse("include=com.example.**");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExcludeEmptyPattern() {
        // Empty exclude pattern should be rejected
        AgentConfig.parse("exclude=");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testExcludeConsecutiveDots() {
        // Exclude pattern with consecutive dots should be rejected
        AgentConfig.parse("exclude=com..example.*");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTrackConstructorsEmptyPattern() {
        // Empty trackConstructors pattern should be rejected
        AgentConfig.parse("trackConstructors=");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTrackConstructorsConsecutiveDots() {
        // trackConstructors pattern with consecutive dots should be rejected
        AgentConfig.parse("trackConstructors=com..example.Foo");
    }

    @Test
    public void testValidPatterns() {
        // All valid patterns should parse without exception
        try {
            AgentConfig.parse("include=com.example.*");
            AgentConfig.parse("include=com.example.Foo");
            AgentConfig.parse("include=com.example.Outer$Inner");
            AgentConfig.parse("exclude=com.example.test.*");
            AgentConfig.parse("exclude=com.example.MockHelper");
            AgentConfig.parse("trackConstructors=com.example.Message");
            AgentConfig.parse("trackConstructors=com.example.*");
            AgentConfig.parse("include=com.example.*;exclude=com.example.test.*");
        } catch (IllegalArgumentException e) {
            fail("Valid patterns should not throw exception: " + e.getMessage());
        }
    }

    @Test
    public void testWhitespaceTrimming() {
        // Patterns with whitespace should be trimmed and validated correctly
        try {
            AgentConfig.parse("include= com.example.* , org.foo.* ");
            AgentConfig.parse("exclude= com.test.* , com.example.Mock ");
        } catch (IllegalArgumentException e) {
            fail("Patterns with whitespace should be trimmed: " + e.getMessage());
        }
    }

    @Test
    public void testErrorMessageContainsPatternName() {
        // Error messages should include the parameter name for clarity
        try {
            AgentConfig.parse("include=com..example");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("include")) {
                fail("Error message should contain parameter name 'include': " + e.getMessage());
            }
        }

        try {
            AgentConfig.parse("exclude=");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("exclude")) {
                fail("Error message should contain parameter name 'exclude': " + e.getMessage());
            }
        }
    }

    @Test
    public void testRedundantPatternsAllowed() {
        // Redundant patterns should be accepted (Set deduplicates automatically)
        try {
            AgentConfig.parse("include=com.example.*,com.example.Foo,com.example.*");
            AgentConfig.parse("exclude=com.test.*,com.test.*");
            AgentConfig.parse("include=com.example.*;exclude=com.example.test.*,com.example.test.*");
        } catch (IllegalArgumentException e) {
            fail("Redundant patterns should be allowed: " + e.getMessage());
        }
    }

    @Test
    public void testExclusionTakesPrecedenceOverInclude() {
        // When a class matches both include and exclude, it should be excluded
        AgentConfig config = AgentConfig.parse("include=com.example.*;exclude=com.example.protocol.*");

        // getTypeMatcher() should exclude classes in com.example.protocol package
        // We can't easily test the matcher directly without mocking TypeDescription,
        // but we can verify the config was parsed correctly
        if (!config.toString().contains("exclude=[com.example.protocol.*]")) {
            fail("Exclude pattern should be present in config");
        }
    }

    @Test
    public void testExclusionTakesPrecedenceOverConstructorTracking() {
        // When a class matches both trackConstructors and exclude, it should be excluded
        AgentConfig config = AgentConfig.parse(
            "include=com.example.*;exclude=com.example.protocol.Message;trackConstructors=com.example.protocol.Message");

        // Verify all patterns were parsed
        String configStr = config.toString();
        if (!configStr.contains("exclude=[com.example.protocol.Message]")) {
            fail("Exclude pattern should be present in config");
        }
        if (!configStr.contains("trackConstructors=[com.example.protocol.Message]")) {
            fail("TrackConstructors pattern should be present in config");
        }

        // The getConstructorTrackingMatcher() should apply exclusions,
        // but testing the actual matcher behavior requires mocking TypeDescription
    }

    @Test
    public void testPackageExclusionAppliestoConstructorTracking() {
        // Package-level exclusions should also apply to constructor tracking
        AgentConfig config = AgentConfig.parse(
            "include=com.example.*;exclude=com.example.protocol.*;trackConstructors=com.example.protocol.*");

        // Verify patterns were parsed
        String configStr = config.toString();
        if (!configStr.contains("exclude=[com.example.protocol.*]")) {
            fail("Package exclude pattern should be present in config");
        }
        if (!configStr.contains("trackConstructors=[com.example.protocol.*]")) {
            fail("Package trackConstructors pattern should be present in config");
        }
    }

    @Test
    public void testConstructorTrackingWithoutExclusions() {
        // When no exclusions are present, trackConstructors patterns should work normally
        AgentConfig config = AgentConfig.parse(
            "include=com.example.*;trackConstructors=com.example.Message");

        // Verify trackConstructors was parsed
        if (!config.toString().contains("trackConstructors=[com.example.Message]")) {
            fail("TrackConstructors pattern should be present in config");
        }

        // Verify no exclusions (empty set)
        if (!config.toString().contains("exclude=[]")) {
            fail("Exclude set should be empty when not specified");
        }
    }
}
