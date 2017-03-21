package fk.prof.aggregation.model;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;
import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.aggregation.serialize.Serializer;
import fk.prof.storage.AsyncStorage;
import fk.prof.storage.StreamTransformer;
import fk.prof.storage.buffer.StorageBackedOutputStream;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Created by gaurav.ashok on 09/03/17.
 */
public class AggregationWindowStorage {

    private final static Logger logger = LoggerFactory.getLogger(AggregationWindowStorage.class);

    private final String baseDir;
    private final AsyncStorage storage;
    private final GenericObjectPool<ByteBuffer> bufferPool;

    public AggregationWindowStorage(String baseDir, AsyncStorage storage, GenericObjectPool<ByteBuffer> bufferPool) {
        this.baseDir = baseDir;
        this.storage = storage;
        this.bufferPool = bufferPool;
    }

    public void store(FinalizedAggregationWindow aggregationWindow) throws IOException {

        // right now only cpu_sample date is available. In future data related to other pivots like thread, contentions will be collected and serialized here.

        // cpu_sample
        store(aggregationWindow, AggregatedProfileModel.WorkType.cpu_sample_work);

        // summary file
        storeSummary(aggregationWindow);
    }

    private void store(FinalizedAggregationWindow aggregationWindow, AggregatedProfileModel.WorkType workType) throws IOException {
        AggregatedProfileNamingStrategy filename = getFilename(aggregationWindow, workType);
        AggregationWindowSerializer serializer = new AggregationWindowSerializer(aggregationWindow, workType);
        writeToStream(serializer, filename);
    }

    private void storeSummary(FinalizedAggregationWindow aggregationWindow) throws IOException {
        AggregatedProfileNamingStrategy filename = getSummaryFilename(aggregationWindow);
        AggregationWindowSummarySerializer serializer = new AggregationWindowSummarySerializer(aggregationWindow);
        writeToStream(serializer, filename);
    }

    private void writeToStream(Serializer serializer, AggregatedProfileNamingStrategy filename) throws IOException {
        if(logger.isDebugEnabled()) {
            logger.debug("Attempting serialization and write of file: " + filename);
        }

        OutputStream out = new StorageBackedOutputStream(bufferPool, storage, filename);
        GZIPOutputStream gout;

        try {
            gout = StreamTransformer.zip(out);
        }
        catch (IOException e) {
            logger.error("Could not zip outstream for file: " + filename, e);
            try {
                out.close();
            }
            catch (IOException ee) {
                logger.error("Failed to close outstream for file: " + filename, ee);
            }
            throw e;
        }

        try {
            serializer.serialize(out);
            if(logger.isDebugEnabled()) {
                logger.debug("Serialization and subsequent write successfully scheduled for file: " + filename);
            }
        }
        catch (IOException e) {
            logger.error("Serialization to outstream failed for file: " + filename, e);
            throw e;
        }
        finally {
            try {
                gout.close();
            }
            catch (IOException e) {
                logger.error("Failed to close zipped outstream for file: " + filename);
                throw e;
            }
        }
    }

    private AggregatedProfileNamingStrategy getFilename(FinalizedAggregationWindow aw, AggregatedProfileModel.WorkType workType) {
        ZonedDateTime start = aw.start.atOffset(ZoneOffset.UTC).toZonedDateTime();
        return new AggregatedProfileNamingStrategy(baseDir, AggregationWindowSerializer.VERSION, aw.appId, aw.clusterId, aw.procId, start, aw.durationInSecs, workType);
    }

    private AggregatedProfileNamingStrategy getSummaryFilename(FinalizedAggregationWindow aw) {
        ZonedDateTime start = aw.start.atOffset(ZoneOffset.UTC).toZonedDateTime();
        return new AggregatedProfileNamingStrategy(baseDir, AggregationWindowSummarySerializer.VERSION, aw.appId, aw.clusterId, aw.procId, start, aw.durationInSecs);
    }
}
