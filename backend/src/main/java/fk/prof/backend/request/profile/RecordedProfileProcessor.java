package fk.prof.backend.request.profile;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.aggregator.AggregationWindow;
import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.model.profile.RecordedProfileHeader;
import fk.prof.backend.model.profile.RecordedProfileIndexes;
import fk.prof.backend.request.CompositeByteBufInputStream;
import fk.prof.backend.request.profile.parser.RecordedProfileHeaderParser;
import fk.prof.backend.request.profile.parser.WseParser;
import fk.prof.backend.model.aggregation.AggregationWindowDiscoveryContext;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import recording.Recorder;

import java.time.Clock;
import java.time.LocalDateTime;

public class RecordedProfileProcessor {
  private static Logger logger = LoggerFactory.getLogger(RecordedProfileProcessor.class);

  private AggregationWindowDiscoveryContext aggregationWindowDiscoveryContext;
  private ISingleProcessingOfProfileGate singleProcessingOfProfileGate;

  private RecordedProfileHeader header = null;
  private AggregationWindow aggregationWindow = null;
  private long workId = 0;
  private LocalDateTime startedAt = null;
  private boolean errored = false;

  private RecordedProfileHeaderParser headerParser;
  private WseParser wseParser;
  private RecordedProfileIndexes indexes = new RecordedProfileIndexes();

  private MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(ConfigManager.METRIC_REGISTRY);
  private Counter ctrAggrWinMiss = metricRegistry.counter(MetricRegistry.name(RecordedProfileProcessor.class, "window", "miss"));
  private Meter mtrPayloadInvalid = metricRegistry.meter(MetricRegistry.name(RecordedProfileProcessor.class, "payload", "invalid"));
  private Meter mtrPayloadCorrupt = metricRegistry.meter(MetricRegistry.name(RecordedProfileProcessor.class, "payload", "corrupt"));

  public RecordedProfileProcessor(AggregationWindowDiscoveryContext aggregationWindowDiscoveryContext, ISingleProcessingOfProfileGate singleProcessingOfProfileGate,
                                  int maxAllowedBytesForRecordingHeader, int maxAllowedBytesForWse) {
    this.aggregationWindowDiscoveryContext = aggregationWindowDiscoveryContext;
    this.singleProcessingOfProfileGate = singleProcessingOfProfileGate;
    this.headerParser = new RecordedProfileHeaderParser(maxAllowedBytesForRecordingHeader);
    this.wseParser = new WseParser(maxAllowedBytesForWse);
  }

  /**
   * Returns true if header has been successfully parsed to retrieve aggregation window and no wse entry log is processed partially
   *
   * @return processed a valid recorded profile object or not
   */
  public boolean isProcessed() {
    return aggregationWindow != null && wseParser.isEndMarkerReceived();
  }

  /**
   * Reads buffer and updates internal state with parsed fields.
   * Aggregates the parsed entries in appropriate aggregation window
   * Returns the starting unread position in outputstream
   *
   * @param inputStream
   */
  public void process(CompositeByteBufInputStream inputStream) {
    if (startedAt == null) {
      startedAt = LocalDateTime.now(Clock.systemUTC());
    }

    try {
      if(isProcessed()) {
        throw new AggregationFailure("Cannot accept more data after receiving end marker");
      }

      if (aggregationWindow == null) {
        headerParser.parse(inputStream);

        if (headerParser.isParsed()) {
          header = headerParser.get();
          workId = header.getRecordingHeader().getWorkAssignment().getWorkId();

          singleProcessingOfProfileGate.accept(workId);
          aggregationWindow = aggregationWindowDiscoveryContext.getAssociatedAggregationWindow(workId);
          if (aggregationWindow == null) {
            ctrAggrWinMiss.inc();
            throw new AggregationFailure(String.format("workId=%d not found, cannot continue receiving associated profile",
                workId));
          }

          aggregationWindow.startProfile(workId, header.getRecordingHeader().getRecorderVersion(), startedAt);
          logger.info(String.format("Profile aggregation started for work_id=%d started_at=%s",
              workId, startedAt.toString()));
        }
      }

      if (aggregationWindow != null) {
        while (inputStream.available() > 0) {
          wseParser.parse(inputStream);
          if(wseParser.isEndMarkerReceived()) {
            return;
          } else if (wseParser.isParsed()) {
            Recorder.Wse wse = wseParser.get();
            processWse(wse);
            wseParser.reset();
          } else {
            break;
          }
        }
      }
    } catch (AggregationFailure ex) {
      errored = true;
      throw ex;
    } catch (Exception ex) {
      errored = true;
      throw new AggregationFailure(ex, true);
    }
  }

  /**
   * If parsing was successful, marks the profile as corrupt if errored, completed/retried if processed, incomplete otherwise
   *
   * @throws AggregationFailure
   */
  public void close() throws AggregationFailure {
    try {
      // Check for errored before checking for processed.
      // Profile can be corrupt(errored) even if processed returns true if more data is sent by client after server has received end marker
      if (errored) {
        mtrPayloadCorrupt.mark();
        if (aggregationWindow != null) {
          aggregationWindow.abandonProfileAsCorrupt(workId);
        }
      } else {
        if (isProcessed()) {
          aggregationWindow.completeProfile(workId);
        } else {
          mtrPayloadInvalid.mark();
          if (aggregationWindow != null) {
            aggregationWindow.abandonProfileAsIncomplete(workId);
          }
        }
      }
    } finally {
      singleProcessingOfProfileGate.finish(workId);
    }
  }

  @Override
  public String toString() {
    return "work_id=" + workId + ", window={" + aggregationWindow + "}, errored=" + errored + ", processed=" + isProcessed();
  }

  private void processWse(Recorder.Wse wse) throws AggregationFailure {
    indexes.update(wse.getIndexedData());
    aggregationWindow.updateWorkInfoWithWSE(workId, wse);
    aggregationWindow.aggregate(wse, indexes);
  }

}
