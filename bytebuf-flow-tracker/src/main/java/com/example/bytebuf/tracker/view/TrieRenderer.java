/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.view;

import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import com.example.bytebuf.tracker.trie.ImprintNode;

import java.util.*;

/**
 * Renders the Trie data structure in various formats for analysis.
 */
public class TrieRenderer {

    /**
     * Maximum recursion depth to prevent StackOverflowError from cyclic graphs.
     */
    private static final int MAX_RECURSION_DEPTH = 100;

    /**
     * Direct buffer allocation methods that allocate off-heap memory (never GC'd).
     */
    private static final String DIRECT_BUFFER_METHOD = ".directBuffer";
    private static final String IO_BUFFER_METHOD = ".ioBuffer";

    private final BoundedImprintTrie trie;

    public TrieRenderer(BoundedImprintTrie trie) {
        this.trie = trie;
    }

    /**
     * Render as indented tree format (human-readable)
     */
    public String renderIndentedTree() {
        StringBuilder sb = new StringBuilder();

        // Sort roots by traversal count (how many objects went through each root)
        List<Map.Entry<String, ImprintNode>> sortedRoots =
            new ArrayList<>(trie.getRoots().entrySet());
        sortedRoots.sort((a, b) ->
            Long.compare(b.getValue().getTraversalCount(), a.getValue().getTraversalCount()));

        for (Map.Entry<String, ImprintNode> entry : sortedRoots) {
            String rootSignature = entry.getKey();
            ImprintNode root = entry.getValue();

            sb.append("ROOT: ").append(rootSignature);
            // For roots, show traversal count (how many objects went through), not outcome count
            sb.append(" [count=").append(root.getTraversalCount()).append("]\n");

            renderNode(sb, root, "", true, true, 0, rootSignature);
        }

        return sb.toString();
    }

    private void renderNode(StringBuilder sb, ImprintNode node, String prefix,
                            boolean isLast, boolean isRoot, int depth, String rootSignature) {
        // Prevent stack overflow
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
            sb.append(formatNode(node, rootSignature));
            sb.append("\n");
        }

        String childPrefix = isRoot ? "" : prefix + (isLast ? "    " : "│   ");

        // Sort children by traversal count
        List<Map.Entry<ImprintNode.NodeKey, ImprintNode>> children =
            new ArrayList<>(node.getChildren().entrySet());
        children.sort((a, b) ->
            Long.compare(b.getValue().getTraversalCount(), a.getValue().getTraversalCount()));

        for (int i = 0; i < children.size(); i++) {
            Map.Entry<ImprintNode.NodeKey, ImprintNode> child = children.get(i);
            boolean isLastChild = (i == children.size() - 1);
            renderNode(sb, child.getValue(), childPrefix, isLastChild, false, depth + 1, rootSignature);
        }
    }

    private String formatNode(ImprintNode node, String rootSignature) {
        StringBuilder sb = new StringBuilder();

        // Method signature
        sb.append(node.getClassName()).append(".").append(node.getMethodName());

        // RefCount and traversal count
        sb.append(" [ref=").append(node.getRefCountForDisplay());
        sb.append(", count=").append(node.getTraversalCount()).append("]");

        // Leak indicators (only for leaf nodes)
        if (node.isLeaf()) {
            long leak = node.getLeakCount();

            if (leak > 0) {
                // Show leak indicator with count
                if (isDirectBufferRoot(rootSignature)) {
                    sb.append(" 🚨 LEAK (").append(leak).append(")");
                } else {
                    sb.append(" ⚠️ LEAK (").append(leak).append(")");
                }
            }
        }

        return sb.toString();
    }

    private boolean isDirectBufferRoot(String rootSignature) {
        if (rootSignature == null) {
            return false;
        }
        return rootSignature.endsWith(DIRECT_BUFFER_METHOD) ||
               rootSignature.endsWith(IO_BUFFER_METHOD) ||
               rootSignature.contains(DIRECT_BUFFER_METHOD + "(") ||
               rootSignature.contains(IO_BUFFER_METHOD + "(");
    }

    /**
     * Render summary statistics
     */
    public String renderSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ByteBuf Flow Summary ===\n");
        sb.append("Total Root Methods: ").append(trie.getRootCount()).append("\n");

        long totalLeaks = 0;
        long totalTraversals = 0;
        int totalPaths = 0;
        int leakPaths = 0;
        int criticalLeakPaths = 0;  // Direct buffer leaks
        int moderateLeakPaths = 0;   // Heap buffer leaks

        for (Map.Entry<String, ImprintNode> entry : trie.getRoots().entrySet()) {
            String rootSignature = entry.getKey();
            ImprintNode root = entry.getValue();
            PathStats stats = calculatePathStats(root, 0);
            totalLeaks += stats.leakCount;
            totalTraversals += stats.traversalCount;
            totalPaths += stats.pathCount;
            leakPaths += stats.leakPathCount;

            // Count critical vs moderate leaks
            if (stats.leakCount > 0) {
                if (isDirectBufferRoot(rootSignature)) {
                    criticalLeakPaths += stats.leakPathCount;
                } else {
                    moderateLeakPaths += stats.leakPathCount;
                }
            }
        }

        sb.append("Total Traversals: ").append(totalTraversals).append("\n");
        sb.append("Total Paths: ").append(totalPaths).append("\n");
        sb.append("Total Leaks: ").append(totalLeaks).append("\n");

        if (totalPaths > 0) {
            sb.append("Leak Paths: ").append(leakPaths).append("\n");

            // Show leak breakdown (always show when there are leaks)
            if (leakPaths > 0) {
                sb.append("Critical Leak Paths: ").append(criticalLeakPaths).append(" (direct buffers)\n");
                sb.append("Moderate Leak Paths: ").append(moderateLeakPaths).append(" (heap buffers)\n");
            }
        }

        return sb.toString();
    }

    private static class PathStats {
        long leakCount = 0;
        long traversalCount = 0;
        int pathCount = 0;
        int leakPathCount = 0;
    }

    private PathStats calculatePathStats(ImprintNode node, int depth) {
        PathStats stats = new PathStats();

        if (depth >= MAX_RECURSION_DEPTH) {
            stats.pathCount = 1;
            stats.leakCount = node.getLeakCount();
            stats.traversalCount = node.getTraversalCount();
            if (node.getLeakCount() > 0) {
                stats.leakPathCount = 1;
            }
            return stats;
        }

        if (node.isLeaf()) {
            stats.pathCount = 1;
            stats.leakCount = node.getLeakCount();
            stats.traversalCount = node.getTraversalCount();

            if (node.getLeakCount() > 0) {
                stats.leakPathCount = 1;
            }
        } else {
            for (ImprintNode child : node.getChildren().values()) {
                PathStats childStats = calculatePathStats(child, depth + 1);
                stats.leakCount += childStats.leakCount;
                stats.traversalCount += childStats.traversalCount;
                stats.pathCount += childStats.pathCount;
                stats.leakPathCount += childStats.leakPathCount;
            }
        }

        return stats;
    }

    /**
     * Render in LLM-optimized format
     */
    public String renderForLLM() {
        StringBuilder sb = new StringBuilder();

        // Calculate statistics
        long totalLeaks = 0;
        int totalPaths = 0;
        int leakPaths = 0;

        for (ImprintNode root : trie.getRoots().values()) {
            PathStats stats = calculatePathStats(root, 0);
            totalLeaks += stats.leakCount;
            totalPaths += stats.pathCount;
            leakPaths += stats.leakPathCount;
        }

        // Metadata
        sb.append("METADATA:\n");
        sb.append("total_roots=").append(trie.getRootCount()).append("\n");
        sb.append("total_paths=").append(totalPaths).append("\n");
        sb.append("leak_paths=").append(leakPaths).append("\n");
        sb.append("leak_count=").append(totalLeaks).append("\n");
        sb.append("\n");

        // Collect all paths
        List<PathInfo> allPaths = new ArrayList<>();
        for (Map.Entry<String, ImprintNode> entry : trie.getRoots().entrySet()) {
            collectPathsForLLM(entry.getKey(), entry.getValue(), new ArrayList<>(), allPaths, 0);
        }

        // Leaks section
        sb.append("LEAKS:\n");
        boolean hasLeaks = false;
        for (PathInfo path : allPaths) {
            if (path.leakCount > 0) {
                String leakType = isDirectBufferRoot(path.root) ? "CRITICAL_LEAK" : "leak";

                sb.append(leakType)
                  .append("|root=").append(path.root)
                  .append("|leak_count=").append(path.leakCount)
                  .append("|path=").append(path.path)
                  .append("\n");
                hasLeaks = true;
            }
        }
        if (!hasLeaks) {
            sb.append("(none)\n");
        }
        sb.append("\n");

        // Flows section
        sb.append("FLOWS:\n");
        for (PathInfo path : allPaths) {
            boolean hasLeak = path.leakCount > 0;

            sb.append("flow|root=").append(path.root)
              .append("|has_leak=").append(hasLeak)
              .append("|leak_count=").append(path.leakCount)
              .append("|path=").append(path.path)
              .append("\n");
        }

        return sb.toString();
    }

    private static class PathInfo {
        String root;
        String path;
        long leakCount;

        PathInfo(String root, String path, long leakCount) {
            this.root = root;
            this.path = path;
            this.leakCount = leakCount;
        }
    }

    private void collectPathsForLLM(String root, ImprintNode node, List<String> currentPath,
                                    List<PathInfo> paths, int depth) {
        if (depth >= MAX_RECURSION_DEPTH) {
            String pathStr = String.join(" -> ", currentPath) + " -> [MAX_DEPTH_REACHED]";
            paths.add(new PathInfo(root, pathStr, node.getLeakCount()));
            return;
        }

        String nodeStr = node.getClassName() + "." + node.getMethodName() +
                        "[ref=" + node.getRefCountForDisplay() + "]";
        currentPath.add(nodeStr);

        if (node.isLeaf()) {
            String pathStr = String.join(" -> ", currentPath);
            paths.add(new PathInfo(root, pathStr, node.getLeakCount()));
        } else {
            for (ImprintNode child : node.getChildren().values()) {
                collectPathsForLLM(root, child, new ArrayList<>(currentPath), paths, depth + 1);
            }
        }
    }
}
