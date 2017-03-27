package fk.prof.backend.model.assignment;

import com.google.common.base.Preconditions;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import recording.Recorder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * vars prefixed with d are duration in seconds
 * vars prefixed with n are duration in nanos
 * vars prefixed with c are counters
 */
public class WorkAssignmentSchedule {
  private final static Logger logger = LoggerFactory.getLogger(WorkAssignmentSchedule.class);
  private final static long NANOS_IN_SEC = (long)Math.pow(10, 9);

  private final long nRef;
  private final int dMinDelay;
  private final int dMaxDelay;
  private final int cMaxParallel;

  private final PriorityQueue<ScheduleEntry> entries = new PriorityQueue<>();
  private Map<RecorderIdentifier, ScheduleEntry> assignedSchedule = new HashMap<>();
  private final ReentrantLock entriesLock = new ReentrantLock();

  public WorkAssignmentSchedule(WorkAssignmentScheduleBootstrapConfig bootstrapConfig,
                                Recorder.WorkAssignment.Builder[] workAssignmentBuilders,
                                int dProfileLen) {
    this.nRef = System.nanoTime();

    // breathing space at the start, this will usually be a lower value than window tolerance
    int dWinStartPad = bootstrapConfig.getSchedulingBufferInSecs() * 2;
    //actual time span for which schedule is calculated
    int dEffectiveWinLen = bootstrapConfig.getWindowDurationInSecs() - bootstrapConfig.getWindowEndToleranceInSecs() - dWinStartPad;
    int dEffectiveProfileLen = dProfileLen + bootstrapConfig.getSchedulingBufferInSecs();
    int cMaxSerial = dEffectiveWinLen / dEffectiveProfileLen;
    int cRequired = workAssignmentBuilders.length;

    this.dMinDelay = bootstrapConfig.getMinAcceptableDelayForWorkAssignmentInSecs();
    this.dMaxDelay = bootstrapConfig.getMaxAcceptableDelayForWorkAssignmentInSecs();
    this.cMaxParallel = (int)Math.ceil((double)cRequired / cMaxSerial);

    for(int i = 0; i < cRequired; i++) {
      long nEntryStartPad = (dWinStartPad + ((i % cMaxSerial) * dEffectiveProfileLen)) * NANOS_IN_SEC;
      this.entries.add(new ScheduleEntry(workAssignmentBuilders[i], nEntryStartPad));
    }
  }

  /**
   * Returns the maximum number of concurrently scheduled entries in this schedule
   * @return
   */
  public int getMaxOverlap() {
    return cMaxParallel;
  }

  /**
   * Supplies with a work assignment
   * Returns null if:
   * > no work assignments are pending
   * > no work assignment ready to be handed out
   * > recorder already assigned work (tied to aggregation window)
   * > timeout while acquiring lock over queue
   * > interrupted while waiting to acquire lock over queue
   * > exception occurred while processing queue entries
   * @return WorkAssignment or null
   */
  public Recorder.WorkAssignment getNextWorkAssignment(RecorderIdentifier recorderIdentifier) {
    try {
      boolean acquired = entriesLock.tryLock(100, TimeUnit.MILLISECONDS);
      if(acquired) {
        try {
          ScheduleEntry scheduleEntry = this.assignedSchedule.get(recorderIdentifier);
          if(scheduleEntry != null) {
            ScheduleEntry.ScheduleEntryValue value = scheduleEntry.getValue((System.nanoTime() - nRef), dMinDelay, dMaxDelay);
            return value.workAssignment;
          }

          while((scheduleEntry = this.entries.peek()) != null) {
            ScheduleEntry.ScheduleEntryValue value = scheduleEntry.getValue((System.nanoTime() - nRef), dMinDelay, dMaxDelay);
            if(value.tooEarly) {
              return null; //Since this is a priority queue, no point checking subsequent entries if current entry indicates its too early
            } else {
              this.entries.poll(); //dequeue the entry. no point in keeping the entry around whether fetch was done on right time or it was a scheduling miss
              if(value.workAssignment != null) {
                this.assignedSchedule.put(recorderIdentifier, scheduleEntry);
                return value.workAssignment;
              }
            }
          }
        } catch (Exception ex) {
          //TODO: increment some metric somewhere
          logger.error("Unexpected error when getting fetching work assignment from schedule", ex);
        } finally {
          entriesLock.unlock();
        }
      } else {
        //TODO: increment some metric somewhere
        logger.warn("Timeout while acquiring lock for fetching work assignment from schedule");
      }
    } catch (InterruptedException ex) {
      //TODO: increment some metric somewhere
      logger.warn("Interrupted while acquiring lock for fetching work assignment from schedule");
    }
    return null;
  }

  public static class ScheduleEntry implements Comparable<ScheduleEntry> {
    private final static Logger logger = LoggerFactory.getLogger(ScheduleEntry.class);

    private final Recorder.WorkAssignment.Builder workAssignmentBuilder;
    private final long nStartPad;

    public ScheduleEntry(Recorder.WorkAssignment.Builder workAssignmentBuilder, long nStartPad) {
      this.workAssignmentBuilder = Preconditions.checkNotNull(workAssignmentBuilder);
      this.nStartPad = nStartPad;
    }

    /**
     * @param nElapsed
     * @param dMinDelay
     * @param dMaxDelay
     * @return value of schedule entry with appropriate flags set to indicate if its too early or too late to receive work assignment
     */
    ScheduleEntryValue getValue(long nElapsed, int dMinDelay, int dMaxDelay) {
      int dRemainingDelay = (int)((nStartPad - nElapsed) / NANOS_IN_SEC);
      if (dRemainingDelay < dMinDelay) {
        logger.error(String.format("Scheduling miss for work_id=%d, remaining delay=%d", workAssignmentBuilder.getWorkId(), dRemainingDelay));
        return new ScheduleEntryValue(null, false, true);
      }
      if (dRemainingDelay > dMaxDelay) {
        logger.debug(String.format("Too early to hand over work assignment for work_id=%d, remaining delay=%d", workAssignmentBuilder.getWorkId(), dRemainingDelay));
        return new ScheduleEntryValue(null, true, false);
      }
      return new ScheduleEntryValue(
          workAssignmentBuilder
              .clone()
              .setDelay(dRemainingDelay)
              .setIssueTime(LocalDateTime.now(Clock.systemUTC()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
              .build(),
          false, false);
    }

    @Override
    public int compareTo(ScheduleEntry other) {
      long diff = this.nStartPad - other.nStartPad;
      return diff > 0 ? 1 : (diff < 0 ? -1 : 0);
    }

    static class ScheduleEntryValue {
      private final Recorder.WorkAssignment workAssignment;
      private final boolean tooEarly;
      private final boolean tooLate;

      ScheduleEntryValue(Recorder.WorkAssignment workAssignment, boolean tooEarly, boolean tooLate) {
        if(tooEarly && tooLate) {
          throw new IllegalArgumentException("Fetch of scheduling entry cannot be too early and too late simultaneously. Make up your mind!");
        } else if (tooEarly || tooLate) {
          if(workAssignment != null) {
            throw new IllegalArgumentException("Work assignment should be set as null if the fetch is too early or too late");
          }
        } else {
          if(workAssignment == null) {
            throw new IllegalArgumentException("Valid work assignment should be returned if the fetch is done at correct time");
          }
        }

        this.workAssignment = workAssignment;
        this.tooEarly = tooEarly;
        this.tooLate = tooLate;
      }
    }

  }

}