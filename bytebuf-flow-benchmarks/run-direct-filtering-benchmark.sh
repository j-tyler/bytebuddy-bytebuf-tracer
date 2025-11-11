#!/bin/bash
# Helper script to run DirectMemoryFilteringBenchmark with different tracking modes
#
# This demonstrates the performance benefits of trackDirectOnly flag
# with a realistic 80% heap / 20% direct workload.

set -e

AGENT_JAR="../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar"
BENCHMARK_JAR="target/benchmarks.jar"
BENCHMARK_CLASS="DirectMemoryFilteringBenchmark"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Direct Memory Filtering Benchmark${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "This benchmark demonstrates the performance benefits of"
echo "tracking only direct memory allocations (critical leaks)"
echo "while skipping heap allocations (80% of workload)."
echo ""

# Check if JAR files exist
if [ ! -f "$AGENT_JAR" ]; then
    echo -e "${RED}ERROR: Agent JAR not found at $AGENT_JAR${NC}"
    echo "Please build the agent first: mvn clean package"
    exit 1
fi

if [ ! -f "$BENCHMARK_JAR" ]; then
    echo -e "${RED}ERROR: Benchmark JAR not found at $BENCHMARK_JAR${NC}"
    echo "Please build the benchmarks first: mvn clean package"
    exit 1
fi

# Function to run a benchmark scenario
run_scenario() {
    local mode=$1
    local description=$2
    local agent_args=$3

    echo -e "${YELLOW}========================================${NC}"
    echo -e "${YELLOW}$mode: $description${NC}"
    echo -e "${YELLOW}========================================${NC}"

    if [ -z "$agent_args" ]; then
        # No agent
        echo "Running WITHOUT agent (baseline)"
        java -jar "$BENCHMARK_JAR" "$BENCHMARK_CLASS" -prof gc
    else
        # With agent
        echo "Running WITH agent: $agent_args"
        java "-javaagent:$AGENT_JAR=$agent_args" \
            -jar "$BENCHMARK_JAR" "$BENCHMARK_CLASS" -prof gc
    fi

    echo ""
    echo -e "${GREEN}$mode complete!${NC}"
    echo ""
}

# Parse command line arguments
MODE="${1:-all}"

case "$MODE" in
    baseline)
        run_scenario "BASELINE" \
            "No agent - best performance" \
            ""
        ;;

    all|track-all)
        run_scenario "TRACK ALL" \
            "Default behavior - tracks both heap and direct" \
            "include=com.example.bytebuf.benchmarks"
        ;;

    direct|track-direct)
        run_scenario "TRACK DIRECT ONLY" \
            "Zero overhead for heap allocations (80% of workload)" \
            "include=com.example.bytebuf.benchmarks;trackDirectOnly=true"
        ;;

    compare)
        echo -e "${BLUE}Running COMPARISON of all modes...${NC}"
        echo ""

        run_scenario "1/3 BASELINE" \
            "No agent - best performance" \
            ""

        run_scenario "2/3 TRACK DIRECT ONLY" \
            "Zero overhead for heap allocations (80% of workload)" \
            "include=com.example.bytebuf.benchmarks;trackDirectOnly=true"

        run_scenario "3/3 TRACK ALL" \
            "Default behavior - tracks both heap and direct" \
            "include=com.example.bytebuf.benchmarks"

        echo -e "${GREEN}========================================${NC}"
        echo -e "${GREEN}All scenarios complete!${NC}"
        echo -e "${GREEN}========================================${NC}"
        echo ""
        echo "Expected performance ranking (best to worst):"
        echo "  1. BASELINE (no tracking)"
        echo "  2. TRACK DIRECT ONLY (heap not instrumented)"
        echo "  3. TRACK ALL (tracks everything)"
        ;;

    quick)
        echo -e "${BLUE}Running QUICK comparison (baseline vs trackDirectOnly)...${NC}"
        echo ""

        run_scenario "1/2 BASELINE" \
            "No agent - best performance" \
            ""

        run_scenario "2/2 TRACK DIRECT ONLY" \
            "Zero overhead for heap allocations (80% of workload)" \
            "include=com.example.bytebuf.benchmarks;trackDirectOnly=true"

        echo -e "${GREEN}Quick comparison complete!${NC}"
        ;;

    *)
        echo "Usage: $0 [mode]"
        echo ""
        echo "Modes:"
        echo "  baseline       - Run without agent (best performance)"
        echo "  track-all      - Track both heap and direct (default behavior)"
        echo "  track-direct   - Track only direct (ZERO overhead for heap)"
        echo "  compare        - Run all 3 scenarios (takes ~15 minutes)"
        echo "  quick          - Quick comparison (baseline vs trackDirectOnly)"
        echo ""
        echo "Examples:"
        echo "  $0 baseline              # Run baseline only"
        echo "  $0 track-direct          # Run with trackDirectOnly=true"
        echo "  $0 compare               # Run all scenarios for comparison"
        echo "  $0 quick                 # Quick baseline vs trackDirectOnly"
        exit 1
        ;;
esac

echo -e "${GREEN}Done!${NC}"
