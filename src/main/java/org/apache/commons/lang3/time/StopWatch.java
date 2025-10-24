/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.lang3.time;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableRunnable;
import org.apache.commons.lang3.function.FailableSupplier;

/**
 * {@link StopWatch} provides a convenient API for timings.
 *
 * <p>
 * To start the watch, call {@link #start()} or {@link StopWatch#createStarted()}. At this point you can:
 * </p>
 * <ul>
 * <li>{@link #split()} the watch to get the time whilst the watch continues in the background. {@link #unsplit()} will remove the effect of the split. At this
 * point, these three options are available again.</li>
 * <li>{@link #suspend()} the watch to pause it. {@link #resume()} allows the watch to continue. Any time between the suspend and resume will not be counted in
 * the total. At this point, these three options are available again.</li>
 * <li>{@link #stop()} the watch to complete the timing session.</li>
 * </ul>
 *
 * <p>
 * It is intended that the output methods {@link #toString()} and {@link #getTime()} should only be called after stop, split or suspend, however a suitable
 * result will be returned at other points.
 * </p>
 *
 * <p>
 * NOTE: As from v2.1, the methods protect against inappropriate calls. Thus you cannot now call stop before start, resume before suspend or unsplit before
 * split.
 * </p>
 *
 * <ol>
 * <li>{@link #split()}, {@link #suspend()}, or {@link #stop()} cannot be invoked twice</li>
 * <li>{@link #unsplit()} may only be called if the watch has been {@link #split()}</li>
 * <li>{@link #resume()} may only be called if the watch has been {@link #suspend()}</li>
 * <li>{@link #start()} cannot be called twice without calling {@link #reset()}</li>
 * </ol>
 *
 * <p>
 * This class is not thread-safe
 * </p>
 *
 * @see DurationUtils#of(FailableRunnable)
 * @see DurationUtils#of(FailableConsumer)
 * @since 2.0
 */
public class StopWatch {

    /**
     * Enumeration type which indicates the split status of a StopWatch.
     */
    private enum SplitState {
        SPLIT, UNSPLIT
    }

    /**
     * Enumeration type which indicates the status of a StopWatch.
     */
    private enum State {

        RUNNING {
            @Override
            boolean isStarted() {
                return true;
            }

            @Override
            boolean isStopped() {
                return false;
            }

            @Override
            boolean isSuspended() {
                return false;
            }
        },

        STOPPED {
            @Override
            boolean isStarted() {
                return false;
            }

            @Override
            boolean isStopped() {
                return true;
            }

            @Override
            boolean isSuspended() {
                return false;
            }
        },

        SUSPENDED {
            @Override
            boolean isStarted() {
                return true;
            }

            @Override
            boolean isStopped() {
                return false;
            }

            @Override
            boolean isSuspended() {
                return true;
            }
        },

        UNSTARTED {
            @Override
            boolean isStarted() {
                return false;
            }

            @Override
            boolean isStopped() {
                return true;
            }

            @Override
            boolean isSuspended() {
                return false;
            }
        };

        /**
         * Tests whether this StopWatch is started. A suspended StopWatch is also started.
         *
         * @return boolean If this StopWatch is started.
         */
        abstract boolean isStarted();

        /**
         * Tests whether this StopWatch is stopped. A StopWatch which is not yet started and explicitly stopped is considered stopped.
         *
         * @return boolean If this StopWatch is stopped.
         */
        abstract boolean isStopped();

        /**
         * Tests whether this StopWatch is suspended.
         *
         * @return boolean If this StopWatch is suspended.
         */
        abstract boolean isSuspended();
    }

    private static final long NANO_2_MILLIS = 1_000_000L;

    /**
     * Creates a StopWatch.
     *
     * @return StopWatch a StopWatch.
     * @since 3.10
     */
    public static StopWatch create() {
        return new StopWatch();
    }

    /**
     * Creates and starts a StopWatch.
     *
     * @return StopWatch a started StopWatch.
     * @since 3.5
     */
    public static StopWatch createStarted() {
        final StopWatch sw = new StopWatch();
        sw.start();
        return sw;
    }

    /**
     * A message for string presentation.
     *
     * @since 3.10
     */
    private final String message;

    /**
     * The current running state of this StopWatch.
     */
    private State runningState = State.UNSTARTED;

    /**
     * Whether this StopWatch has a split time recorded.
     */
    private SplitState splitState = SplitState.UNSPLIT;

    /**
     * The start time in nanoseconds.
     *
     * This field can be removed once we move off of Java 8.
     */
    private long startTimeNanos;

    /**
     * The start Instant.
     * <p>
     * nanoTime is only for elapsed time so we need to also store the currentTimeMillis to maintain the old getStartTime API.
     * </p>
     * <p>
     * On Java 8, Instant has millisecond precision, later versions use nanoseconds.
     * </p>
     */
    private Instant startInstant;

    /**
     * The end Instant.
     * <p>
     * nanoTime is only for elapsed time so we need to also store the currentTimeMillis to maintain the old getStartTime API.
     * </p>
     * <p>
     * On Java 8, Instant has millisecond precision, later versions use nanoseconds.
     * </p>
     */
    private Instant stopInstant;

    /**
     * The stop time in nanoseconds.
     *
     * This field can be removed once we move off of Java 8.
     */
    private long stopTimeNanos;

    /**
     * The list of splits
     */
    private List<Split> splits;

    /**
     * Constructs a new instance.
     */
    public StopWatch() {
        this(null);
    }

    /**
     * Constructs a new instance.
     *
     * @param message A message for string presentation.
     * @since 3.10
     */
    public StopWatch(final String message) {
        this.message = message;
    }

    /**
     * Formats the split time with {@link DurationFormatUtils#formatDurationHMS}.
     *
     * @return the split time formatted by {@link DurationFormatUtils#formatDurationHMS}.
     * @since 3.10
     */
    public String formatSplitTime() {
        return DurationFormatUtils.formatDurationHMS(getSplitDuration().toMillis());
    }

    /**
     * Formats the time formatted with {@link DurationFormatUtils#formatDurationHMS}.
     *
     * @return the time formatted by {@link DurationFormatUtils#formatDurationHMS}.
     * @since 3.10
     */
    public String formatTime() {
        return DurationFormatUtils.formatDurationHMS(getTime());
    }

    /**
     * Delegates to {@link Supplier#get()} while recording the duration of the call.
     *
     * @param <T>      the type of results supplied by this supplier.
     * @param supplier The supplier to {@link Supplier#get()}.
     * @return a result from the given Supplier.
     * @since 3.18.0
     */
    public <T> T get(final Supplier<T> supplier) {
        startResume();
        try {
            return supplier.get();
        } finally {
            suspend();
        }
    }

    /**
     * Gets the Duration on this StopWatch.
     *
     * <p>
     * This is either the Duration between the start and the moment this method is called, or the Duration between start and stop.
     * </p>
     *
     * @return the Duration.
     * @since 3.16.0
     */
    public Duration getDuration() {
        return Duration.ofNanos(getNanoTime());
    }

    /**
     * Gets the message for string presentation.
     *
     * @return the message for string presentation.
     * @since 3.10
     */
    public String getMessage() {
        return message;
    }

    /**
     * Gets the <em>elapsed</em> time in nanoseconds.
     *
     * <p>
     * This is either the time between the start and the moment this method is called, or the amount of time between start and stop.
     * </p>
     *
     * @return the <em>elapsed</em> time in nanoseconds.
     * @see System#nanoTime()
     * @since 3.0
     */
    public long getNanoTime() {
        switch (runningState) {
        case STOPPED:
        case SUSPENDED:
            return stopTimeNanos - startTimeNanos;
        case UNSTARTED:
            return 0;
        case RUNNING:
            return System.nanoTime() - startTimeNanos;
        default:
            break;
        }
        throw new IllegalStateException("Illegal running state has occurred.");
    }

    /**
     * Gets the split Duration on this StopWatch.
     *
     * <p>
     * This is the Duration between start and latest split.
     * </p>
     *
     * @return the split Duration
     * @throws IllegalStateException if this StopWatch has not yet been split.
     * @since 3.16.0
     */
    public Duration getSplitDuration() {
        return Duration.ofNanos(getSplitNanoTime());
    }

    /**
     * Gets the split time in nanoseconds.
     *
     * <p>
     * This is the time between start and latest split.
     * </p>
     *
     * @return the split time in nanoseconds
     * @throws IllegalStateException if this StopWatch has not yet been split.
     * @since 3.0
     */
    public long getSplitNanoTime() {
        if (splitState != SplitState.SPLIT) {
            throw new IllegalStateException("Stopwatch must be split to get the split time.");
        }
        return stopTimeNanos - startTimeNanos;
    }

    /**
     * Gets the split time on this StopWatch.
     *
     * <p>
     * This is the time between start and latest split.
     * </p>
     *
     * @return the split time in milliseconds
     * @throws IllegalStateException if this StopWatch has not yet been split.
     * @since 2.1
     * @deprecated Use {@link #getSplitDuration()}.
     */
    @Deprecated
    public long getSplitTime() {
        return nanosToMillis(getSplitNanoTime());
    }

    /**
     * Gets the Instant this StopWatch was started, between the current time and midnight, January 1, 1970 UTC.
     *
     * @return the Instant this StopWatch was started, between the current time and midnight, January 1, 1970 UTC.
     * @throws IllegalStateException if this StopWatch has not been started
     * @since 3.16.0
     */
    public Instant getStartInstant() {
        if (runningState == State.UNSTARTED) {
            throw new IllegalStateException("Stopwatch has not been started");
        }
        return startInstant;
    }

    /**
     * Gets the time this StopWatch was started in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     *
     * @return the time this StopWatch was started in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     * @throws IllegalStateException if this StopWatch has not been started
     * @since 2.4
     * @deprecated Use {@link #getStartInstant()}.
     */
    @Deprecated
    public long getStartTime() {
        return getStartInstant().toEpochMilli();
    }

    /**
     * Gets the Instant this StopWatch was stopped, between the current time and midnight, January 1, 1970 UTC.
     *
     * @return the Instant this StopWatch was stopped in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     * @throws IllegalStateException if this StopWatch has not been started
     * @since 3.16.0
     */
    public Instant getStopInstant() {
        if (runningState == State.UNSTARTED) {
            throw new IllegalStateException("Stopwatch has not been started");
        }
        return stopInstant;
    }

    /**
     * Gets the time this StopWatch was stopped in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     *
     * @return the time this StopWatch was stopped in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     * @throws IllegalStateException if this StopWatch has not been started
     * @since 3.12.0
     * @deprecated Use {@link #getStopInstant()}.
     */
    @Deprecated
    public long getStopTime() {
        // stopTimeNanos stores System.nanoTime() for elapsed time
        return getStopInstant().toEpochMilli();
    }

    /**
     * Delegates to {@link FailableSupplier#get()} while recording the duration of the call.
     *
     * @param <T>      the type of results supplied by this supplier.
     * @param <E>      The kind of thrown exception or error.
     * @param supplier The supplier to {@link Supplier#get()}.
     * @return a result from the given Supplier.
     * @throws Throwable if the supplier fails.
     * @since 3.18.0
     */
    public <T, E extends Throwable> T getT(final FailableSupplier<T, E> supplier) throws Throwable {
        startResume();
        try {
            return supplier.get();
        } finally {
            suspend();
        }
    }

    /**
     * Gets the time on this StopWatch.
     *
     * <p>
     * This is either the time between the start and the moment this method is called, or the amount of time between start and stop.
     * </p>
     *
     * @return the time in milliseconds
     * @see #getDuration()
     */
    public long getTime() {
        return nanosToMillis(getNanoTime());
    }

    /**
     * Gets the time in the specified TimeUnit.
     *
     * <p>
     * This is either the time between the start and the moment this method is called, or the amount of time between start and stop. The resulting time will be
     * expressed in the desired TimeUnit with any remainder rounded down. For example, if the specified unit is {@code TimeUnit.HOURS} and this StopWatch time
     * is 59 minutes, then the result returned will be {@code 0}.
     * </p>
     *
     * @param timeUnit the unit of time, not null
     * @return the time in the specified TimeUnit, rounded down
     * @since 3.5
     */
    public long getTime(final TimeUnit timeUnit) {
        return timeUnit.convert(getNanoTime(), TimeUnit.NANOSECONDS);
    }

    /**
     * Tests whether this StopWatch is started. A suspended StopWatch is also started watch.
     *
     * @return boolean If this StopWatch is started.
     * @since 3.2
     */
    public boolean isStarted() {
        return runningState.isStarted();
    }

    /**
     * Tests whether StopWatch is stopped. this StopWatch which's not yet started and explicitly stopped StopWatch is considered as stopped.
     *
     * @return boolean If this StopWatch is stopped.
     * @since 3.2
     */
    public boolean isStopped() {
        return runningState.isStopped();
    }

    /**
     * Tests whether this StopWatch is suspended.
     *
     * @return boolean If this StopWatch is suspended.
     * @since 3.2
     */
    public boolean isSuspended() {
        return runningState.isSuspended();
    }

    /**
     * Converts nanoseconds to milliseconds.
     *
     * @param nanos nanoseconds to convert.
     * @return milliseconds conversion result.
     */
    private long nanosToMillis(final long nanos) {
        return nanos / NANO_2_MILLIS;
    }

    /**
     * Resets this StopWatch. Stops it if need be.
     *
     * <p>
     * This method clears the internal values to allow the object to be reused.
     * </p>
     */
    public void reset() {
        runningState = State.UNSTARTED;
        splitState = SplitState.UNSPLIT;
    }

    /**
     * Resumes this StopWatch after a suspend.
     *
     * <p>
     * This method resumes the watch after it was suspended. The watch will not include time between the suspend and resume calls in the total time.
     * </p>
     *
     * @throws IllegalStateException if this StopWatch has not been suspended.
     */
    public void resume() {
        if (runningState != State.SUSPENDED) {
            throw new IllegalStateException("Stopwatch must be suspended to resume.");
        }
        startTimeNanos += System.nanoTime() - stopTimeNanos;
        runningState = State.RUNNING;
    }

    /**
     * Delegates to {@link Runnable#run()} while recording the duration of the call.
     *
     * @param runnable The runnable to {@link Runnable#run()}.
     * @since 3.18.0
     */
    public void run(final Runnable runnable) {
        startResume();
        try {
            runnable.run();
        } finally {
            suspend();
        }
    }

    /**
     * Delegates to {@link FailableRunnable#run()} while recording the duration of the call.
     *
     * @param <E>      The kind of {@link Throwable}.
     * @param runnable The runnable to {@link FailableRunnable#run()}.
     * @throws Throwable Thrown by {@link FailableRunnable#run()}.
     * @since 3.18.0
     */
    public <E extends Throwable> void runT(final FailableRunnable<E> runnable) throws Throwable {
        startResume();
        try {
            runnable.run();
        } finally {
            suspend();
        }
    }

    /**
     * Splits the time.
     *
     * <p>
     * This method sets the stop time of the watch to allow a time to be extracted. The start time is unaffected, enabling {@link #unsplit()} to continue the
     * timing from the original start point.
     * </p>
     *
     * @throws IllegalStateException if this StopWatch is not running.
     */
    public void split() {
        if (runningState != State.RUNNING) {
            throw new IllegalStateException("Stopwatch is not running.");
        }
        stopTimeNanos = System.nanoTime();
        splitState = SplitState.SPLIT;
    }

    /**
     * <p>
     * Splits the time to track the elapsed time between two consecutive {@code split()} calls.
     * The label specified is used to identify each split
     * </p>
     *
     * <p>
     * After calling {@link #stop()}, we can call {@link #getReport()} to have a report with all time between each {@code split()} call, example:
     * </p>
     *
     * <pre>
     * 1 00:14:00.000
     * 2 00:02:00.000
     * 3 00:04:00.000
     * </pre>
     *
     * @param label A number to identify this split
     *
     * @throws IllegalStateException
     *             if the StopWatch is not running.
     */
    public void split(int label) {
        split(String.valueOf(label));
    }

    /**
     * <p>
     * Splits the time to track the elapsed time between two consecutive {@code split()} calls.
     * The label specified is used to identify each split
     * </p>
     *
     * <p>
     * After calling {@link #stop()}, we can call {@link #getReport()} to have a report with all time between each {@code split()} call, example:
     * </p>
     *
     * <pre>
     * Baking cookies  00:14:00.000
     * Serving         00:02:00.000
     * Eating          00:04:00.000
     * </pre>
     *
     * @param label A message for string presentation.
     *
     * @throws IllegalStateException
     *             if the StopWatch is not running.
     */
    public void split(String label) {
        if (this.runningState != State.RUNNING) {
            throw new IllegalStateException("Stopwatch is not running.");
        }
        splits.add(new Split(label));
    }

    /**
     * Starts this StopWatch.
     *
     * <p>
     * This method starts a new timing session, clearing any previous values.
     * </p>
     *
     * @throws IllegalStateException if this StopWatch is already running.
     */
    public void start() {
        if (runningState == State.STOPPED) {
            throw new IllegalStateException("Stopwatch must be reset before being restarted.");
        }
        if (runningState != State.UNSTARTED) {
            throw new IllegalStateException("Stopwatch already started.");
        }
        startTimeNanos = System.nanoTime();
        startInstant = Instant.now();
        runningState = State.RUNNING;
        splits = new ArrayList<>();
    }

    /**
     * Starts or resumes this StopWatch.
     */
    private void startResume() {
        if (isStopped()) {
            start();
        } else if (isSuspended()) {
            resume();
        }
    }

    /**
     * Stops this StopWatch.
     *
     * <p>
     * This method ends a new timing session, allowing the time to be retrieved.
     * </p>
     *
     * @throws IllegalStateException if this StopWatch is not running.
     */
    public void stop() {
        if (runningState != State.RUNNING && runningState != State.SUSPENDED) {
            throw new IllegalStateException("Stopwatch is not running.");
        }
        if (runningState == State.RUNNING) {
            stopTimeNanos = System.nanoTime();
            stopInstant = Instant.now();
            split(StringUtils.EMPTY);
        }
        runningState = State.STOPPED;
    }

    /**
     * Stops the watch if necessary
     */
    private void stopIfNecessary() {
        if (this.runningState == State.RUNNING || this.runningState == State.SUSPENDED) {
            stop();
        }
    }

    /**
     * Suspends this StopWatch for later resumption.
     *
     * <p>
     * This method suspends the watch until it is resumed. The watch will not include time between the suspend and resume calls in the total time.
     * </p>
     *
     * @throws IllegalStateException if this StopWatch is not currently running.
     */
    public void suspend() {
        if (runningState != State.RUNNING) {
            throw new IllegalStateException("Stopwatch must be running to suspend.");
        }
        stopTimeNanos = System.nanoTime();
        stopInstant = Instant.now();
        runningState = State.SUSPENDED;
    }

    /**
     * Gets a summary of the split time that this StopWatch recorded as a string.
     *
     * <p>
     * The format used is ISO 8601-like, [<em>message</em> ]<em>hours</em>:<em>minutes</em>:<em>seconds</em>.<em>milliseconds</em>.
     * </p>
     *
     * @return the split time as a String
     * @since 2.1
     * @since 3.10 Returns the prefix {@code "message "} if the message is set.
     */
    public String toSplitString() {
        final String msgStr = Objects.toString(message, StringUtils.EMPTY);
        final String formattedTime = formatSplitTime();
        return msgStr.isEmpty() ? formattedTime : msgStr + StringUtils.SPACE + formattedTime;
    }

    /**
     * Gets a summary of the time that this StopWatch recorded as a string.
     *
     * <p>
     * The format used is ISO 8601-like, [<em>message</em> ]<em>hours</em>:<em>minutes</em>:<em>seconds</em>.<em>milliseconds</em>.
     * </p>
     *
     * @return the time as a String
     * @since 3.10 Returns the prefix {@code "message "} if the message is set.
     */
    @Override
    public String toString() {
        final String msgStr = Objects.toString(message, StringUtils.EMPTY);
        final String formattedTime = formatTime();
        return msgStr.isEmpty() ? formattedTime : msgStr + StringUtils.SPACE + formattedTime;
    }

    /**
     * Removes the split.
     *
     * <p>
     * This method clears the stop time. The start time is unaffected, enabling timing from the original start point to continue.
     * </p>
     *
     * @throws IllegalStateException if this StopWatch has not been split.
     */
    public void unsplit() {
        if (splitState != SplitState.SPLIT) {
            throw new IllegalStateException("Stopwatch has not been split.");
        }
        splitState = SplitState.UNSPLIT;
    }

    /**
     * Stops the watch and returns the list of splits with duration on each split (using milliseconds)
     * @return list of splits
     */
    public List<Split> getProcessedSplits() {
        return getProcessedSplits(TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the watch and returns the list of splits with duration on each split (using nanoseconds)
     * @return list of splits
     */
    public List<Split> getNanoProcessedSplits() {
        return getProcessedSplits(TimeUnit.NANOSECONDS);
    }

    /**
     * Stops the watch and returns the list of splits with duration on each split
     *
     * @param timeUnit the unit of time, not null. Any value will calculate with milliseconds precision unless
     * {@code TimeUnit.NANOSECONDS} is specified.
     * @return list of splits
     */
    public List<Split> getProcessedSplits(TimeUnit timeUnit) {
        stopIfNecessary();
        processSplits(timeUnit);
        final List<Split> result = new ArrayList<>(splits);

        // we remove the last split because it's an internal and automatic split
        result.remove(result.size() - 1);

        return result;
    }

    /**
     * Fill durations (time took) on each split
     *
     * @param timeUnit the unit of time, not null. Any value will calculate with milliseconds precision unless
     * {@code TimeUnit.NANOSECONDS} is specified.
     */
    private void processSplits(TimeUnit timeUnit) {
        // we need at least 2 splits to calculate the elapsed time
        if (splits.size() < 2) {
            return;
        }

        for (int i = 0; i < splits.size() - 1; i++) {
            final long durationNanos = splits.get(i + 1).getStartNanoTime() - splits.get(i).getStartNanoTime();
            final long duration = (timeUnit == TimeUnit.NANOSECONDS)
                    ? durationNanos
                    : TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
            splits.get(i).setDuration(duration);
        }

    }

    /**
     * <p>
     * Stops the watch and returns the splits report.
     * This report contains the elapsed time (on milliseconds) between each split
     * </p>
     *
     * @return the splits report
     */
    public String getReport() {
        return getReport(TimeUnit.MILLISECONDS);
    }

    /**
     * <p>
     * Stops the watch and returns the splits report.
     * This report contains the elapsed time (on nanoseconds) between each split
     * </p>
     *
     * @return the splits report
     */
    public String getNanoReport() {
        return getReport(TimeUnit.NANOSECONDS);
    }

    /**
     * <p>
     * Stops the watch and returns the splits report.
     * This report contains the elapsed time between each split
     * </p>
     *
     * @param timeUnit the unit of time, not null. Any value will calculate with milliseconds precision unless
     * {@code TimeUnit.NANOSECONDS} is specified.
     * @return the splits report
     */
    private String getReport(TimeUnit timeUnit) {
        final StringBuilder report = new StringBuilder();

        String duration;
        for (final Split split : getProcessedSplits(timeUnit)) {
            report.append(System.lineSeparator());
            report.append(split.getLabel()).append(StringUtils.SPACE);

            if (timeUnit == TimeUnit.NANOSECONDS) {
                duration = String.valueOf(split.getDuration());
            } else {
                duration = DurationFormatUtils.formatDurationHMS(split.getDuration());
            }

            report.append(duration);
        }

        return report.toString();
    }

    /**
     * Class to store details of each split
     */
    protected static class Split {

        /**
         * The start nano time of this split
         */
        private long startNanoTime = System.nanoTime();

        /**
         * The duration (time took) on this split
         * This field is filled when user calls getSplits() or tries to print the splits report
         */
        private long duration;

        /*
         * The label for this split
         */
        private String label;

        /**
         * Constructor with label
         * @param label Label for this split
         */
        public Split(String label) {
            this.label = label;
        }

        /**
         * <p>
         * Get the timestamp when this split was created
         * </p>
         *
         * @return startNanoTime
         */
        public long getStartNanoTime() {
            return startNanoTime;
        }

        /**
         * <p>
         * Get the label of this split
         * </p>
         *
         * @return label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Duration of this split
         * @return duration (time on ms or nano)
         */
        public long getDuration() {
            return duration;
        }

        /**
         * Set the duration of this split
         * @param duration time (on ms or nano)
         */
        private void setDuration(long duration) {
            this.duration = duration;
        }
    }

}
