package fk.prof.backend.model.assignment;

import com.google.common.base.Preconditions;
import fk.prof.aggregation.model.FinalizedAggregationWindow;
import fk.prof.backend.aggregator.AggregationWindow;
import fk.prof.backend.model.aggregation.AggregationWindowLookupStore;
import fk.prof.backend.proto.BackendDTO;
import fk.prof.backend.util.BitOperationUtil;
import fk.prof.backend.util.proto.RecorderProtoUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import recording.Recorder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AggregationWindowPlanner {
  private static int workIdCounter = 1;
  private static final int MILLIS_IN_SEC = 1000;
  private static final Logger logger = LoggerFactory.getLogger(AggregationWindowPlanner.class);
  //Should be a different value if backend were to die and come back up, otherwise duplicate work ids will be generated by the backend since workId counter starts from 0 on every process start
  public static final int BACKEND_IDENTIFIER;

  static {
    Random random = new Random();
    //Random does not guarantee unique values every single time but should be an acceptable(and minimal) risk since we have 32 bits to play with here
    BACKEND_IDENTIFIER = random.nextInt();
  }

  private final Vertx vertx;
  private final WorkAssignmentScheduleFactory workAssignmentScheduleFactory;
  private final SimultaneousWorkAssignmentCounter simultaneousWorkAssignmentCounter;
  private final ProcessGroupContextForScheduling processGroupContextForScheduling;
  private final AggregationWindowLookupStore aggregationWindowLookupStore;
  private final Function<Recorder.ProcessGroup, Future<BackendDTO.WorkProfile>> workForBackendRequestor;

  private final Recorder.ProcessGroup processGroup;
  private final int aggregationWindowDurationInMins;
  private final int workProfileRefreshBufferInSecs;
  private final Future<Long> aggregationWindowScheduleTimer;

  private AggregationWindow currentAggregationWindow = null;
  private BackendDTO.WorkProfile latestWorkProfile = null;
  private int currentlyOccupiedWorkAssignmentSlots = 0;

  private int currentAggregationWindowIndex = 0;
  private int relevantAggregationWindowIndexForWorkProfile = 0;

  public AggregationWindowPlanner(Vertx vertx,
                                  int aggregationWindowDurationInMins,
                                  int workProfileRefreshBufferInSecs,
                                  WorkAssignmentScheduleFactory workAssignmentScheduleFactory,
                                  SimultaneousWorkAssignmentCounter simultaneousWorkAssignmentCounter,
                                  ProcessGroupContextForScheduling processGroupContextForScheduling,
                                  AggregationWindowLookupStore aggregationWindowLookupStore,
                                  Function<Recorder.ProcessGroup, Future<BackendDTO.WorkProfile>> workForBackendRequestor) {
    this.vertx = Preconditions.checkNotNull(vertx);
    this.processGroupContextForScheduling = Preconditions.checkNotNull(processGroupContextForScheduling);
    this.processGroup = processGroupContextForScheduling.getProcessGroup();
    this.workForBackendRequestor = Preconditions.checkNotNull(workForBackendRequestor);
    this.aggregationWindowLookupStore = Preconditions.checkNotNull(aggregationWindowLookupStore);
    this.workAssignmentScheduleFactory = Preconditions.checkNotNull(workAssignmentScheduleFactory);
    this.simultaneousWorkAssignmentCounter = Preconditions.checkNotNull(simultaneousWorkAssignmentCounter);
    this.aggregationWindowDurationInMins = aggregationWindowDurationInMins;
    this.workProfileRefreshBufferInSecs = workProfileRefreshBufferInSecs;

    this.aggregationWindowScheduleTimer = Future.future();
    getWorkForNextAggregationWindow(currentAggregationWindowIndex + 1).setHandler(ar -> {
      aggregationWindowSwitcher();

      // From vertx docs:
      // Keep in mind that the timer will fire on a periodic basis.
      // If your periodic treatment takes a long amount of time to proceed, your timer events could run continuously or even worse : stack up.
      // NOTE: The above is a fringe scenario since aggregation window duration is going to be in excess of 20 minutes
      // Still, there is a way to detect if this build-up happens. If aggregation window switch event happens before work profile is fetched, we publish a metric
      // If /leader/work API latency is within bounds but this metric is high, this implies a build-up of aggregation window events
      long periodicTimerId = vertx.setPeriodic(aggregationWindowDurationInMins * 60 * MILLIS_IN_SEC,
          timerId -> aggregationWindowSwitcher());
      this.aggregationWindowScheduleTimer.complete(periodicTimerId);
    });
  }

  /**
   * This expires current aggregation window and cancels scheduling of upcoming aggregation windows
   * To be called when leader de-associates relevant process group from the backend
   */
  public void close() {
    aggregationWindowScheduleTimer.setHandler(ar -> {
      if(ar.succeeded()) {
        vertx.cancelTimer(ar.result());
      }
    });
    expireCurrentAggregationWindow();
  }

  /**
   * This method will be called before start of every aggregation window
   * There should be sufficient buffer to allow completion of this method before the next aggregation window starts
   * Not adding any guarantees here, but a lead of few minutes for this method's execution should ensure that the request to get work should complete in time for next aggregation window
   */
  private Future<Void> getWorkForNextAggregationWindow(int aggregationWindowIndex) {
    latestWorkProfile = null;
    Future<Void> result = Future.future();
    this.workForBackendRequestor.apply(processGroup).setHandler(ar -> {
      relevantAggregationWindowIndexForWorkProfile = aggregationWindowIndex;
      if(ar.failed()) {
        //Cannot fetch work from leader, so chill out and let this aggregation window go by
        //TODO: Metric to indicate failure to fetch work for this process group from leader
        logger.error("Error fetching work from leader for process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup) + ", error=" + ar.cause().getMessage());
        result.fail(ar.cause());
      } else {
        latestWorkProfile = ar.result();
        if(logger.isDebugEnabled()) {
          logger.debug("Fetched work successfully from leader for process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
        }
        result.complete();
      }
    });

    return result;
  }

  private void aggregationWindowSwitcher() {
    expireCurrentAggregationWindow();
    currentAggregationWindowIndex++;
    if (currentAggregationWindowIndex == relevantAggregationWindowIndexForWorkProfile && latestWorkProfile != null) {
      try {
        int targetRecordersCount = processGroupContextForScheduling.getRecorderTargetCountToMeetCoverage(latestWorkProfile.getCoveragePct());
        Recorder.WorkAssignment.Builder[] workAssignmentBuilders = new Recorder.WorkAssignment.Builder[targetRecordersCount];
        long workIds[] = new long[targetRecordersCount];
        for (int i = 0; i < workIds.length; i++) {
          Recorder.WorkAssignment.Builder workAssignmentBuilder = Recorder.WorkAssignment.newBuilder()
              .setWorkId(BitOperationUtil.constructLongFromInts(BACKEND_IDENTIFIER, workIdCounter++))
              .addAllWork(latestWorkProfile.getWorkList().stream()
                  .map(RecorderProtoUtil::translateWorkFromBackendDTO)
                  .collect(Collectors.toList()))
              .setDescription(latestWorkProfile.getDescription())
              .setDuration(latestWorkProfile.getDuration());

          workAssignmentBuilders[i] = workAssignmentBuilder;
          workIds[i] = workAssignmentBuilder.getWorkId();
        }
        setupAggregationWindow(workAssignmentBuilders, workIds);
      } catch (Exception ex) {
        reset();
        //TODO: log this as metric somewhere, fatal failure wrt to aggregation window
        logger.error("Skipping work assignments and setup of aggregation window because of unexpected error while processing for process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
      }
    } else {
      //TODO: log this as metric somewhere, fatal failure wrt to aggregation window
      logger.error("Skipping work assignments and setup of aggregation window because work profile was not fetched in time for process_group=" + RecorderProtoUtil.processGroupCompactRepr(processGroup));
    }

    vertx.setTimer(((aggregationWindowDurationInMins * 60) - workProfileRefreshBufferInSecs) * MILLIS_IN_SEC,
        timerId -> getWorkForNextAggregationWindow(currentAggregationWindowIndex + 1));
  }

  private void setupAggregationWindow(Recorder.WorkAssignment.Builder[] workAssignmentBuilders, long[] workIds) {
    LocalDateTime windowStart = LocalDateTime.now(Clock.systemUTC());
    int maxConcurrentSlotsForScheduling = simultaneousWorkAssignmentCounter.acquireSlots(processGroup, latestWorkProfile);
    try {
      WorkAssignmentSchedule workAssignmentSchedule = workAssignmentScheduleFactory.getNewWorkAssignmentSchedule(workAssignmentBuilders,
          latestWorkProfile.getCoveragePct(),
          maxConcurrentSlotsForScheduling,
          latestWorkProfile.getDuration());
      currentlyOccupiedWorkAssignmentSlots = workAssignmentSchedule.getMaxConcurrentlyScheduledEntries();
      currentAggregationWindow = new AggregationWindow(
          processGroup.getAppId(),
          processGroup.getCluster(),
          processGroup.getProcName(),
          windowStart,
          workIds);
      processGroupContextForScheduling.updateWorkAssignmentSchedule(workAssignmentSchedule);
    } catch (Exception ex) {
      //Any exception thrown is first detected here so that acquired slots can be released and then propagated further up the chain so that caller for setting up aggregation window can react to it
      simultaneousWorkAssignmentCounter.releaseSlots(maxConcurrentSlotsForScheduling);
      throw ex;
    }
    simultaneousWorkAssignmentCounter.releaseSlots(maxConcurrentSlotsForScheduling - currentlyOccupiedWorkAssignmentSlots);
    aggregationWindowLookupStore.associateAggregationWindow(workIds, currentAggregationWindow);
  }

  private void expireCurrentAggregationWindow() {
    if(currentAggregationWindow != null) {
      simultaneousWorkAssignmentCounter.releaseSlots(currentlyOccupiedWorkAssignmentSlots);
      FinalizedAggregationWindow finalizedAggregationWindow = currentAggregationWindow.expireWindow(aggregationWindowLookupStore);
      //TODO: Serialization and persistence of aggregated profile should hookup here

      reset(); //this should be the last statement in this method
    }
  }

  private void reset() {
    currentAggregationWindow = null;
    currentlyOccupiedWorkAssignmentSlots = 0;
    processGroupContextForScheduling.updateWorkAssignmentSchedule(null);
  }
}
