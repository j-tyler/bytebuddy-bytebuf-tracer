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

        // Sort children by total count
        List<Map.Entry<ImprintNode.NodeKey, ImprintNode>> children =
            new ArrayList<>(node.getChildren().entrySet());
        children.sort((a, b) ->
            Long.compare(b.getValue().getTotalCount(), a.getValue().getTotalCount()));

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

        // RefCount and count
        sb.append(" [ref=").append(node.getRefCountForDisplay());
        sb.append(", count=").append(node.getTotalCount()).append("]");

        // Leak indicators (only for leaf nodes)
        if (node.isLeaf()) {
            long leak = node.getLeakCount();
            long clean = node.getCleanCount();
            long total = node.getTotalCount();

            if (leak > 0) {
                double leakRate = (leak * 100.0) / total;

                if (clean == 0) {
                    // Always leaks
                    if (isDirectBufferRoot(rootSignature)) {
                        sb.append(" 🚨 LEAK");
                    } else {
                        sb.append(" ⚠️ LEAK");
                    }
                } else if (leakRate >= 50) {
                    // Often leaks
                    sb.append(String.format(" ⚠️ OFTEN LEAKS (%.0f%%)", leakRate));
                } else {
                    // Sometimes leaks
                    sb.append(String.format(" ⚠️ SOMETIMES LEAKS (%.0f%%)", leakRate));
                }
            } else if (clean > 0) {
                // Clean - just show checkmark
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

        long totalClean = 0;
        long totalLeak = 0;
        int totalPaths = 0;
        int alwaysLeakPaths = 0;
        int sometimesLeakPaths = 0;
        int cleanPaths = 0;
        int criticalLeaks = 0;  // Direct buffer leaks
        int moderateLeaks = 0;   // Heap buffer leaks

        for (Map.Entry<String, ImprintNode> entry : trie.getRoots().entrySet()) {
            String rootSignature = entry.getKey();
            ImprintNode root = entry.getValue();
            PathStats stats = calculatePathStats(root, 0);
            totalClean += stats.cleanCount;
            totalLeak += stats.leakCount;
            totalPaths += stats.pathCount;
            alwaysLeakPaths += stats.alwaysLeakCount;
            sometimesLeakPaths += stats.sometimesLeakCount;
            cleanPaths += stats.cleanPathCount;

            // Count critical vs moderate leaks
            if (stats.leakCount > 0) {
                if (isDirectBufferRoot(rootSignature)) {
                    criticalLeaks += stats.alwaysLeakCount + stats.sometimesLeakCount;
                } else {
                    moderateLeaks += stats.alwaysLeakCount + stats.sometimesLeakCount;
                }
            }
        }

        long totalTraversals = totalClean + totalLeak;
        sb.append("Total Traversals: ").append(totalTraversals).append("\n");
        sb.append("Total Paths: ").append(totalPaths).append("\n");

        if (totalPaths > 0) {
            sb.append("Leak Paths: ").append(alwaysLeakPaths + sometimesLeakPaths).append("\n");

            // Show leak breakdown (always show when there are leaks)
            if (alwaysLeakPaths > 0 || sometimesLeakPaths > 0) {
                sb.append("Critical Leaks: ").append(criticalLeaks).append(" (direct buffers)\n");
                sb.append("Moderate Leaks: ").append(moderateLeaks).append(" (heap buffers)\n");
            }

            if (totalTraversals > 0) {
                double leakRate = (totalLeak * 100.0) / totalTraversals;
                sb.append(String.format("Leak Percentage: %.2f%%\n", leakRate));
            }
        }

        return sb.toString();
    }

    private static class PathStats {
        long cleanCount = 0;
        long leakCount = 0;
        int pathCount = 0;
        int alwaysLeakCount = 0;
        int sometimesLeakCount = 0;
        int cleanPathCount = 0;
    }

    private PathStats calculatePathStats(ImprintNode node, int depth) {
        PathStats stats = new PathStats();

        if (depth >= MAX_RECURSION_DEPTH) {
            stats.pathCount = 1;
            stats.cleanCount = node.getCleanCount();
            stats.leakCount = node.getLeakCount();
            return stats;
        }

        if (node.isLeaf()) {
            stats.pathCount = 1;
            stats.cleanCount = node.getCleanCount();
            stats.leakCount = node.getLeakCount();

            if (node.getLeakCount() > 0 && node.getCleanCount() == 0) {
                stats.alwaysLeakCount = 1;
            } else if (node.getLeakCount() > 0 && node.getCleanCount() > 0) {
                stats.sometimesLeakCount = 1;
            } else if (node.getCleanCount() > 0) {
                stats.cleanPathCount = 1;
            }
        } else {
            for (ImprintNode child : node.getChildren().values()) {
                PathStats childStats = calculatePathStats(child, depth + 1);
                stats.cleanCount += childStats.cleanCount;
                stats.leakCount += childStats.leakCount;
                stats.pathCount += childStats.pathCount;
                stats.alwaysLeakCount += childStats.alwaysLeakCount;
                stats.sometimesLeakCount += childStats.sometimesLeakCount;
                stats.cleanPathCount += childStats.cleanPathCount;
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
        long totalClean = 0;
        long totalLeak = 0;
        int totalPaths = 0;

        for (ImprintNode root : trie.getRoots().values()) {
            PathStats stats = calculatePathStats(root, 0);
            totalClean += stats.cleanCount;
            totalLeak += stats.leakCount;
            totalPaths += stats.pathCount;
        }

        long totalTraversals = totalClean + totalLeak;

        // Metadata
        sb.append("METADATA:\n");
        sb.append("total_roots=").append(trie.getRootCount()).append("\n");
        sb.append("total_traversals=").append(totalTraversals).append("\n");
        sb.append("total_paths=").append(totalPaths).append("\n");
        sb.append("clean_count=").append(totalClean).append("\n");
        sb.append("leak_count=").append(totalLeak).append("\n");
        if (totalTraversals > 0) {
            double leakRate = (totalLeak * 100.0) / totalTraversals;
            sb.append(String.format("leak_rate=%.2f%%\n", leakRate));
        }
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
                double leakRate = (path.leakCount * 100.0) / (path.cleanCount + path.leakCount);

                sb.append(leakType)
                  .append("|root=").append(path.root)
                  .append("|leak_rate=").append(String.format("%.1f%%", leakRate))
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
            long total = path.cleanCount + path.leakCount;
            boolean hasLeak = path.leakCount > 0;

            sb.append("flow|root=").append(path.root)
              .append("|total=").append(total)
              .append("|has_leak=").append(hasLeak)
              .append("|path=").append(path.path)
              .append("\n");
        }

        return sb.toString();
    }

    private static class PathInfo {
        String root;
        String path;
        long cleanCount;
        long leakCount;

        PathInfo(String root, String path, long cleanCount, long leakCount) {
            this.root = root;
            this.path = path;
            this.cleanCount = cleanCount;
            this.leakCount = leakCount;
        }
    }

    private void collectPathsForLLM(String root, ImprintNode node, List<String> currentPath,
                                    List<PathInfo> paths, int depth) {
        if (depth >= MAX_RECURSION_DEPTH) {
            String pathStr = String.join(" -> ", currentPath) + " -> [MAX_DEPTH_REACHED]";
            paths.add(new PathInfo(root, pathStr, node.getCleanCount(), node.getLeakCount()));
            return;
        }

        String nodeStr = node.getClassName() + "." + node.getMethodName() +
                        "[ref=" + node.getRefCountForDisplay() + "]";
        currentPath.add(nodeStr);

        if (node.isLeaf()) {
            String pathStr = String.join(" -> ", currentPath);
            paths.add(new PathInfo(root, pathStr, node.getCleanCount(), node.getLeakCount()));
        } else {
            for (ImprintNode child : node.getChildren().values()) {
                collectPathsForLLM(root, child, new ArrayList<>(currentPath), paths, depth + 1);
            }
        }
    }
}
