package com.example.bytebuf.tracker.agent;

/**
 * JMX MBean interface for ByteBuf flow monitoring
 */
public interface ByteBufFlowMBeanInterface {
    // View operations
    String getTreeView();
    String getFlatView();
    String getCsvView();
    String getJsonView();
    String getSummary();

    // Statistics
    int getRootCount();
    int getActiveFlowCount();

    // Export operations
    void exportToFile(String filepath, String format);

    // Control operations
    void reset();
}
