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

package com.example.bytebuf.tracker.trie;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pure Trie data structure for ByteBuf flow tracking.
 * First method that touches a ByteBuf becomes the root.
 * No allocation tracking - simplified and efficient.
 */
public class FlowTrie {
    // Map from "ClassName.methodName" to root nodes
    private final Map<String, TrieNode> roots = new ConcurrentHashMap<>();
    
    /**
     * Get or create a root node for a method
     * Using explicit get/putIfAbsent to avoid re-entrance issues with computeIfAbsent
     */
    public TrieNode getOrCreateRoot(String className, String methodName) {
        String key = className + "." + methodName;
        TrieNode node = roots.get(key);
        if (node == null) {
            node = new TrieNode(className, methodName, 1);
            TrieNode existing = roots.putIfAbsent(key, node);
            if (existing != null) {
                node = existing;
            }
        }
        return node;
    }
    
    /**
     * Get all roots for analysis/viewing
     */
    public Map<String, TrieNode> getRoots() {
        return Collections.unmodifiableMap(roots);
    }
    
    /**
     * Clear all data (useful for testing or resetting)
     */
    public void clear() {
        roots.clear();
    }
    
    /**
     * Get total number of root nodes
     */
    public int getRootCount() {
        return roots.size();
    }
    
    /**
     * Single node in the Trie representing a method invocation
     */
    public static class TrieNode {
        private final String className;
        private final String methodName;
        private final int refCount;
        private final Map<NodeKey, TrieNode> children = new ConcurrentHashMap<>();
        private final LongAdder traversalCount = new LongAdder();
        
        public TrieNode(String className, String methodName, int refCount) {
            this.className = className;
            this.methodName = methodName;
            this.refCount = refCount;
        }
        
        /**
         * Traverse to a child node, creating it if necessary.
         * Note: This does NOT increment traversalCount on the parent node.
         * traversalCount represents how many times THIS specific method was called,
         * not how many times we passed through it to reach children.
         * Using explicit get/putIfAbsent to avoid re-entrance issues with computeIfAbsent
         */
        public TrieNode traverse(String className, String methodName, int refCount) {
            NodeKey key = new NodeKey(className, methodName, refCount);
            TrieNode child = children.get(key);
            if (child == null) {
                child = new TrieNode(className, methodName, refCount);
                TrieNode existing = children.putIfAbsent(key, child);
                if (existing != null) {
                    child = existing;
                }
            }
            // Increment traversal count on the CHILD node, not parent
            child.recordTraversal();
            return child;
        }
        
        /**
         * Just record that we passed through this node (for leaf nodes)
         */
        public void recordTraversal() {
            traversalCount.increment();
        }
        
        // Getters for read-only access
        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public int getRefCount() { return refCount; }
        public long getTraversalCount() { return traversalCount.sum(); }
        public Map<NodeKey, TrieNode> getChildren() { 
            return Collections.unmodifiableMap(children); 
        }
        
        public boolean isLeaf() {
            return children.isEmpty();
        }
    }
    
    /**
     * Key for child nodes - includes refCount to track variations
     * Using refCount in the key means same method with different refCounts 
     * creates different branches (making anomalies visible)
     */
    public static class NodeKey {
        public final String className;
        public final String methodName;
        public final int refCount;
        private final int hashCode;
        
        public NodeKey(String className, String methodName, int refCount) {
            this.className = className;
            this.methodName = methodName;
            this.refCount = refCount;
            this.hashCode = Objects.hash(className, methodName, refCount);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NodeKey)) return false;
            NodeKey that = (NodeKey) o;
            return refCount == that.refCount &&
                   className.equals(that.className) &&
                   methodName.equals(that.methodName);
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
        
        @Override
        public String toString() {
            return String.format("%s.%s[%d]", className, methodName, refCount);
        }
    }
}
