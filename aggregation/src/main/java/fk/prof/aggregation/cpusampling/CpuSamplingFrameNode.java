package fk.prof.aggregation.cpusampling;

import fk.prof.aggregation.SerializableAggregationEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

    public class CpuSamplingFrameNode implements SerializableAggregationEntity {
    private final int methodId;
    private final int lineNumber;
    private final List<CpuSamplingFrameNode> children = new ArrayList<>();

    private final AtomicInteger onStackSamples = new AtomicInteger(0);
    private final AtomicInteger onCpuSamples = new AtomicInteger(0);

    public CpuSamplingFrameNode(int methodId, int lineNumber) {
        this.methodId = methodId;
        this.lineNumber = lineNumber;
    }

    public CpuSamplingFrameNode getOrAddChild(int childMethodId, int childLineNumber) {
        synchronized (children) {
            CpuSamplingFrameNode result = null;
            Iterator<CpuSamplingFrameNode> i = children.iterator();
            // Since count of children is going to be small for a node (in scale of tens usually),
            // sticking with arraylist impl of children with O(N) traversal
            while (i.hasNext()) {
                CpuSamplingFrameNode child = i.next();
                if (child.methodId == childMethodId && child.lineNumber == childLineNumber) {
                    result = child;
                    break;
                }
            }

            if (result == null) {
                result = new CpuSamplingFrameNode(childMethodId, childLineNumber);
                children.add(result);
            }

            return result;
        }
    }

    public int incrementOnStackSamples () {
        return this.onStackSamples.incrementAndGet();
    }

    public int incrementOnCpuSamples () {
        return this.onCpuSamples.incrementAndGet();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof CpuSamplingFrameNode)) {
            return false;
        }

        CpuSamplingFrameNode other = (CpuSamplingFrameNode) o;
        return this.methodId == other.methodId
            && this.lineNumber == other.lineNumber
            && this.onStackSamples.get() == other.onStackSamples.get()
            && this.onCpuSamples.get() == other.onCpuSamples.get()
            && this.children.size() == other.children.size()
            && this.children.containsAll(other.children)
            && other.children.containsAll(this.children);
    }

    @Override
    public int hashCode() {
        final int PRIME = 31;
        int result = 1;
        result = result * PRIME + methodId;
        result = result * PRIME + lineNumber;
        return result;
    }
}
