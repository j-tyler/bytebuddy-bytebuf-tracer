#!/bin/bash
# Test script to verify retain(), release(), and retainedDuplicate() tracking

set -e

cd "$(dirname "$0")"

# Build and install if needed
if [ ! -f "bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar" ]; then
    echo "Building agent..."
    mvn clean install -DskipTests -q
fi

# Create test Java file
cat > /tmp/LifecycleTest.java << 'EOF'
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

public class LifecycleTest {
    public static void main(String[] args) {
        System.out.println("=== Testing Lifecycle Method Tracking ===\n");

        // Test 1: retain() which returns ByteBuf
        System.out.println("Test 1: retain() tracking");
        ByteBuf buf1 = Unpooled.buffer(256);
        System.out.println("buf1 created: refCnt=" + buf1.refCnt());
        buf1.retain();  // Increase refCnt to 2
        System.out.println("buf1 retained: refCnt=" + buf1.refCnt());
        buf1.release();  // Drop to 1
        System.out.println("After first release: refCnt=" + buf1.refCnt());
        buf1.release();  // Drop to 0 and deallocate
        System.out.println("After second release: DEALLOCATED");

        // Test 2: retainedDuplicate() which returns NEW ByteBuf
        System.out.println("Test 2: retainedDuplicate() tracking");
        ByteBuf buf2 = Unpooled.buffer(256);
        ByteBuf dup = buf2.retainedDuplicate();  // Should track return with _return suffix
        dup.release();
        buf2.release();

        // Test 3: release() which returns boolean (not ByteBuf)
        System.out.println("Test 3: release() tracking");
        ByteBuf buf3 = Unpooled.buffer(256);
        boolean released = buf3.release();  // Should track via lifecycle advice

        // Print results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        tracker.ensureGCProcessed();  // Process any pending GC

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        System.out.println("\n=== Flow Summary ===");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        // Verify expectations
        String tree = renderer.renderIndentedTree();

        boolean hasRetain = tree.contains("retain");
        boolean hasRetainedDuplicateReturn = tree.contains("retainedDuplicate_return");
        boolean hasRelease = tree.contains("release");

        System.out.println("\n=== Verification ===");
        System.out.println("retain tracked: " + hasRetain);
        System.out.println("retainedDuplicate_return tracked: " + hasRetainedDuplicateReturn);
        System.out.println("release tracked: " + hasRelease);

        if (hasRetain && hasRetainedDuplicateReturn && hasRelease) {
            System.out.println("\n✓ ALL TESTS PASSED");
            System.exit(0);
        } else {
            System.out.println("\n✗ TESTS FAILED - Check output above for details");
            System.exit(1);
        }
    }
}
EOF

# Compile test
echo "Compiling test..."
cd bytebuf-flow-example
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
CP="target/classes:$(cat /tmp/cp.txt)"

javac -cp "$CP" /tmp/LifecycleTest.java -d /tmp

# Run test with agent
echo "Running test with agent..."
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=LifecycleTest" \
     -cp "/tmp:$CP" \
     LifecycleTest

echo ""
echo "✓ Lifecycle tracking test completed successfully!"
