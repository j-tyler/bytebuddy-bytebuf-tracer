/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.view;

import com.example.bytebuf.tracker.trie.FlowTrie;
import com.example.bytebuf.tracker.trie.FlowTrie.TrieNode;
import com.example.bytebuf.tracker.trie.FlowTrie.NodeKey;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders the Trie data structure in various formats for analysis.
 * Pure presentation logic - no business logic or analysis.
 */
public class TrieRenderer {

    /**
     * Maximum recursion depth to prevent StackOverflowError from cyclic graphs.
     * This limits how deep we traverse the tree structure during rendering.
     * 100 levels is plenty for debugging while preventing stack overflow.
     */
    private static final int MAX_RECURSION_DEPTH = 100;

    private final FlowTrie trie;

    public TrieRenderer(FlowTrie trie) {
        this.trie = trie;
    }
    
    /**
     * Render as indented tree format (human-readable)
     */
    public String renderIndentedTree() {
        StringBuilder sb = new StringBuilder();
        
        // Sort roots by traversal count for better visibility
        List<Map.Entry<String, TrieNode>> sortedRoots = trie.getRoots().entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().getTraversalCount(), 
                                          a.getValue().getTraversalCount()))
            .collect(Collectors.toList());
        
        for (Map.Entry<String, TrieNode> entry : sortedRoots) {
            sb.append("ROOT: ").append(entry.getKey());
            sb.append(" [count=").append(entry.getValue().getTraversalCount()).append("]\n");
            renderNode(sb, entry.getValue(), "", true, true, 0);
        }
        
        return sb.toString();
    }
    
    private void renderNode(StringBuilder sb, TrieNode node, String prefix, boolean isLast, boolean isRoot, int depth) {
        // Prevent stack overflow from cyclic graphs or very deep trees
        if (depth >= MAX_RECURSION_DEPTH) {
            if (!isRoot) {
                sb.append(prefix);
                sb.append(isLast ? "└── " : "├── ");
                sb.append("[MAX DEPTH REACHED - Truncated at ").append(depth).append(" levels]\n");
            }
            return;
        }

        if (!isRoot) {
            sb.append(prefix);
            sb.append(isLast ? "└── " : "├── ");
            sb.append(formatNode(node));
            sb.append("\n");
        }

        String childPrefix = isRoot ? "" : prefix + (isLast ? "    " : "│   ");

        List<Map.Entry<NodeKey, TrieNode>> children = new ArrayList<>(node.getChildren().entrySet());
        // Sort children by traversal count
        children.sort((a, b) -> Long.compare(b.getValue().getTraversalCount(),
                                            a.getValue().getTraversalCount()));

        for (int i = 0; i < children.size(); i++) {
            Map.Entry<NodeKey, TrieNode> child = children.get(i);
            boolean isLastChild = (i == children.size() - 1);
            renderNode(sb, child.getValue(), childPrefix, isLastChild, false, depth + 1);
        }
    }
    
    private String formatNode(TrieNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.getClassName()).append(".").append(node.getMethodName());
        sb.append(" [ref=").append(node.getRefCount());
        sb.append(", count=").append(node.getTraversalCount()).append("]");
        
        // Add indicators for potential issues
        if (node.isLeaf() && node.getRefCount() != 0) {
            sb.append(" ⚠️ LEAK");
        }
        
        return sb.toString();
    }
    
    /**
     * Render summary statistics
     */
    public String renderSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ByteBuf Flow Summary ===\n");
        sb.append("Total Root Methods: ").append(trie.getRootCount()).append("\n");
        
        long totalTraversals = 0;
        int totalPaths = 0;
        int leakPaths = 0;
        
        for (TrieNode root : trie.getRoots().values()) {
            PathStats stats = calculatePathStats(root, 0);
            totalTraversals += root.getTraversalCount();
            totalPaths += stats.pathCount;
            leakPaths += stats.leakCount;
        }
        
        sb.append("Total Traversals: ").append(totalTraversals).append("\n");
        sb.append("Total Paths: ").append(totalPaths).append("\n");
        sb.append("Leak Paths: ").append(leakPaths).append("\n");
        
        if (totalPaths > 0) {
            double leakPercentage = (leakPaths * 100.0) / totalPaths;
            sb.append(String.format("Leak Percentage: %.2f%%\n", leakPercentage));
        }
        
        return sb.toString();
    }
    
    private static class PathStats {
        int pathCount = 0;
        int leakCount = 0;
    }

    private PathStats calculatePathStats(TrieNode node, int depth) {
        PathStats stats = new PathStats();

        // Prevent stack overflow from cyclic graphs or very deep trees
        if (depth >= MAX_RECURSION_DEPTH) {
            // Treat max-depth nodes as leaf nodes for stats purposes
            stats.pathCount = 1;
            if (node.getRefCount() != 0) {
                stats.leakCount = 1;
            }
            return stats;
        }

        if (node.isLeaf()) {
            stats.pathCount = 1;
            if (node.getRefCount() != 0) {
                stats.leakCount = 1;
            }
        } else {
            for (TrieNode child : node.getChildren().values()) {
                PathStats childStats = calculatePathStats(child, depth + 1);
                stats.pathCount += childStats.pathCount;
                stats.leakCount += childStats.leakCount;
            }
        }

        return stats;
    }

    /**
     * Render in LLM-optimized format for maximum clarity and token efficiency.
     * Format is structured with metadata, leaks section, and flows section.
     */
    public String renderForLLM() {
        StringBuilder sb = new StringBuilder();

        // Calculate statistics
        long totalTraversals = 0;
        int totalPaths = 0;
        int leakPaths = 0;

        for (TrieNode root : trie.getRoots().values()) {
            PathStats stats = calculatePathStats(root, 0);
            totalTraversals += root.getTraversalCount();
            totalPaths += stats.pathCount;
            leakPaths += stats.leakCount;
        }

        // Metadata section
        sb.append("METADATA:\n");
        sb.append("total_roots=").append(trie.getRootCount()).append("\n");
        sb.append("total_traversals=").append(totalTraversals).append("\n");
        sb.append("total_paths=").append(totalPaths).append("\n");
        sb.append("leak_paths=").append(leakPaths).append("\n");
        if (totalPaths > 0) {
            double leakPercentage = (leakPaths * 100.0) / totalPaths;
            sb.append(String.format("leak_percentage=%.2f%%\n", leakPercentage));
        } else {
            sb.append("leak_percentage=0.00%\n");
        }
        sb.append("\n");

        // Collect all paths
        List<PathInfo> allPaths = new ArrayList<>();
        for (Map.Entry<String, TrieNode> entry : trie.getRoots().entrySet()) {
            collectPathsForLLM(entry.getKey(), entry.getValue(), new ArrayList<>(), allPaths, 0);
        }

        // Leaks section
        sb.append("LEAKS:\n");
        boolean hasLeaks = false;
        for (PathInfo path : allPaths) {
            if (path.isLeak) {
                sb.append("leak|root=").append(path.root)
                  .append("|final_ref=").append(path.finalRef)
                  .append("|path=").append(path.path)
                  .append("\n");
                hasLeaks = true;
            }
        }
        if (!hasLeaks) {
            sb.append("(none)\n");
        }
        sb.append("\n");

        // Flows section (all paths including non-leaks)
        sb.append("FLOWS:\n");
        for (PathInfo path : allPaths) {
            sb.append("flow|root=").append(path.root)
              .append("|final_ref=").append(path.finalRef)
              .append("|is_leak=").append(path.isLeak)
              .append("|path=").append(path.path)
              .append("\n");
        }

        return sb.toString();
    }

    /**
     * Helper class to hold path information for LLM format
     */
    private static class PathInfo {
        String root;
        String path;
        int finalRef;
        boolean isLeak;

        PathInfo(String root, String path, int finalRef, boolean isLeak) {
            this.root = root;
            this.path = path;
            this.finalRef = finalRef;
            this.isLeak = isLeak;
        }
    }

    /**
     * Collect all root-to-leaf paths for LLM format
     */
    private void collectPathsForLLM(String root, TrieNode node, List<String> currentPath,
                                     List<PathInfo> paths, int depth) {
        // Prevent stack overflow
        if (depth >= MAX_RECURSION_DEPTH) {
            String pathStr = String.join(" -> ", currentPath) + " -> [MAX_DEPTH_REACHED]";
            paths.add(new PathInfo(root, pathStr, node.getRefCount(), node.getRefCount() != 0));
            return;
        }

        // Add current node to path
        String nodeStr = node.getClassName() + "." + node.getMethodName() + "[ref=" + node.getRefCount() + "]";
        currentPath.add(nodeStr);

        if (node.isLeaf()) {
            // Leaf node - create path info
            String pathStr = String.join(" -> ", currentPath);
            boolean isLeak = node.getRefCount() != 0;
            paths.add(new PathInfo(root, pathStr, node.getRefCount(), isLeak));
        } else {
            // Continue traversing
            for (TrieNode child : node.getChildren().values()) {
                collectPathsForLLM(root, child, new ArrayList<>(currentPath), paths, depth + 1);
            }
        }
    }
}
