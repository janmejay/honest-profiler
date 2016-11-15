package com.insightfullogic.honest_profiler.parser;

import com.google.protobuf.CodedInputStream;
import com.insightfullogic.honest_profiler.ports.sources.FileLogSource;
import com.insightfullogic.lambdabehave.JunitSuiteRunner;
import com.insightfullogic.lambdabehave.expectations.Expect;
import org.junit.runner.RunWith;
import recording.Recorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.*;

import static com.insightfullogic.lambdabehave.Suite.describe;

/**
 * @understands reading profile-file
 */
@RunWith(JunitSuiteRunner.class)
public class ProfileParsingTest {
    {
        describe("Encoded profile", it -> {

            it.should("be readable with checksum verification", expect -> {
                CodedInputStream is = CodedInputStream.newInstance(new FileInputStream("/tmp/profile.data"));
                expect.that(is.readUInt32()).is(1);

                int headerLen = is.readUInt32();

                int hdrLimit = is.pushLimit(headerLen);
                Recorder.RecordingHeader.Builder rhBuilder = Recorder.RecordingHeader.newBuilder();
                rhBuilder.mergeFrom(is);
                is.popLimit(hdrLimit);

                Recorder.RecordingHeader rh = rhBuilder.build();
                expect.that(rh.getRecorderVersion()).is(1);
                expect.that(rh.getControllerVersion()).is(2);
                expect.that(rh.getControllerId()).is(3);
                expect.that(rh.getWorkDescription()).is("Test cpu-sampling work");
                Recorder.WorkAssignment wa = rh.getWorkAssignment();
                expect.that(wa.getWorkId()).is(10l);
                expect.that(wa.getIssueTime()).is("2016-11-10T14:35:09.372");
                expect.that(wa.getDuration()).is(60);
                expect.that(wa.getDelay()).is(17);
                Recorder.Work w = wa.getWork();
                expect.that(w.getWType()).is(Recorder.WorkType.cpu_sample_work);
                Recorder.CpuSampleWork csw = w.getCpuSample();
                expect.that(csw.getFrequency()).is(49);
                expect.that(csw.getMaxFrames()).is(200);

                //// Hdr len and chksum
                int bytesBeforeHdrChksum = is.getTotalBytesRead();
                int headerChksum = is.readUInt32();
                int bytesOffsetAfterHdrChksum = is.getTotalBytesRead();
                ///////////////////////

                int wse1Len = is.readUInt32();
                int wse1Lim = is.pushLimit(wse1Len);
                Recorder.Wse.Builder wseBuilder = Recorder.Wse.newBuilder();
                wseBuilder.mergeFrom(is);
                Recorder.Wse e1 = wseBuilder.build();
                is.popLimit(wse1Lim);

                Map<Long, String> methodIdToName = new HashMap<>();
                testWseContents(expect, e1, methodIdToName, new int[]{15000, 15050}, new long[]{200l, 200l}, new List[]{Arrays.asList("Y", "C", "D", "C", "D"), Arrays.asList("Y", "C", "D", "E", "C", "D")}, 4);
                wseBuilder.clear();

                //// E1 len and chksum
                int byteCountE1 = is.getTotalBytesRead() - bytesOffsetAfterHdrChksum;
                int e1Chksum = is.readUInt32();
                int bytesOffsetAfterE1Chksum = is.getTotalBytesRead();
                ///////////////////////

                int wse2Len = is.readUInt32();
                int wse2Lim = is.pushLimit(wse2Len);
                wseBuilder.mergeFrom(is);
                is.popLimit(wse2Lim);

                Recorder.Wse e2 = wseBuilder.build();
                testWseContents(expect, e2, methodIdToName, new int[]{25002}, new long[]{201l}, new List[]{Arrays.asList("Y", "C", "D", "E", "F", "C")}, 1);

                Set<String> expectedFunctions = new HashSet<>();
                expectedFunctions.add("Y");
                expectedFunctions.add("C");
                expectedFunctions.add("D");
                expectedFunctions.add("E");
                expectedFunctions.add("F");
                expect.that(new HashSet<String>(methodIdToName.values())).is(expectedFunctions);

            });
        });
    }

    private void testWseContents(Expect expect, Recorder.Wse e, Map<Long, String> methodIdToName, final int[] startOffsets, final long[] threadIds, final List[] frames, final int methodInfoCount) {
        expect.that(e.getWType()).is(Recorder.WorkType.cpu_sample_work);
        Recorder.StackSampleWse cse = e.getCpuSampleEntry();
        expect.that(cse.getMethodInfoCount()).is(methodInfoCount);
        for (Recorder.MethodInfo methodInfo : cse.getMethodInfoList()) {
            long methodId = methodInfo.getMethodId();
            expect.that(methodIdToName.containsKey(methodId)).is(false);
            String methodName = methodInfo.getMethodName();
            methodIdToName.put(methodId, methodName);
            expect.that(methodInfo.getFileName()).is("foo/Bar.java");
            expect.that(methodInfo.getClassFqdn()).is("foo.Bar");
            expect.that(methodInfo.getSignature()).is("([I)I");
        }
        expect.that(cse.getStackSampleCount()).is(frames.length);

        for (int i = 0; i < frames.length; i++) {
            Recorder.StackSample ss1 = cse.getStackSample(i);
            expect.that(ss1.getStartOffsetMicros()).is(startOffsets[i]);
            expect.that(ss1.getThreadId()).is(threadIds[i]);
            expect.that(ss1.getFrameCount()).is(frames[i].size());
            List<String> callChain = new ArrayList<>();
            for (Recorder.Frame frame : ss1.getFrameList()) {
                long methodId = frame.getMethodId();
                expect.that(methodIdToName.containsKey(methodId)).is(true);
                callChain.add(methodIdToName.get(methodId));
                expect.that(frame.getBci()).is((int) (17 * methodId));
                expect.that(frame.getLineNo()).is((int) (2 * methodId));
            }
            expect.that(callChain).is(frames[i]);
        }

    }
}
